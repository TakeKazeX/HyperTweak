package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.presentation.icon.MobileSignalEvent
import com.takekazex.hypertweak.presentation.icon.MobileSignalState

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import com.takekazex.hypertweak.hook.base.HostAccess
import android.telephony.SubscriptionManager
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hides the non-active-data cellular row while retaining MIUI's original visibility Pair.
 *
 * The former hook read `defaultDataSubId` as an Int and wrote a module-created Boolean flow into
 * the concrete VM field. On OS4 that field is populated by the factory with a
 * `StateFlow<Pair<Boolean, Boolean>>`; this implementation follows the same adapter and factory
 * boundaries as [StackedSignalHooker], masks only the first Pair member, and falls back to the
 * original getter whenever no live registration exists.
 */
object HideCellularIconHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconStackedEnabled by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_STACKED_ENABLED, false) }
    private val settingIconHideNonDefaultSim by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM, false)
    }
    private val settingIconHideSimAuto by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO, false) }
    private val settingIconHideSimOne by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE, false) }
    private val settingIconHideSimTwo by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO, false) }
    private val settingIconHideMobileOnWifi by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI, false)
    }

    init {
        hostRecovery(HostRecoveryPhase.ADAPTER_POLICY, icons = true) {
            it.adapter?.let(::recoverAdapter)
        }
    }

    private const val TAG = "IconTuner"
    private const val ADAPTER_CLASS =
        "com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter"
    private const val ICONS_VM_CLASS =
        "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel"

    private val installed = generationResource(AtomicBoolean(false)) { it.set(false) }
    private val adapterFlowInstalled = generationResource(AtomicBoolean(false)) { it.set(false) }
    private val generation = AtomicLong(0L)
    private val flowHandles = generationMainResource(CopyOnWriteArrayList<HostFlowCollector.Handle>()) { it.clear() }
    private val bindings = generationMainResource(LinkedHashMap<Int, Binding>()) { it.clear() }
    private val pendingBindings = generationMainResource(IdentityHashMap<Any, Boolean>()) { it.clear() }

    private var enabled by generationState { false }

    private var hideSimOne by generationState { false }

    private var hideSimTwo by generationState { false }

    private var hideNonDefault by generationState { false }

    private var hideOnWifi by generationState { false }

    private var state by generationState { MobileSignalState() }
    private var adapterReference: java.lang.ref.WeakReference<Any>? by generationState { null }

    /**
     * Per-subscription WiFi availability, consumed on the main looper.
     *
     * Kept outside [state] because the WiFi flow can emit before `subscriptionsFlow` has published
     * the subscription ids; a reducer event for an unknown subId is discarded, so a boot-time WiFi
     * connection would otherwise never be applied. [applyMask] reads this map directly.
     */
    private val wifiBySub = generationMainResource(LinkedHashMap<Int, Boolean>()) { it.clear() }

    private data class Binding(
        val viewModel: Any,
        val visibilityHandle: HostFlowCollector.Handle,
        val wifiHandle: HostFlowCollector.Handle? = null
    )

    override fun onPrepareHotReload() {
        generation.incrementAndGet()

        HostAccess.onMain {
            hideSimOne = false
            hideSimTwo = false
            hideNonDefault = false
            hideOnWifi = false
            wifiBySub.clear()
            // The host binder consumes both members of this Pair. Restore the original first member
            // before cancellation/unregistration so a hot reload cannot leave a stopped false flow.
            MobileSignalVisibility.clearForHotReload()
            flowHandles.forEach { it.cancel() }
            flowHandles.clear()
            bindings.keys.toList().forEach { subId -> removeBinding(subId) }
            pendingBindings.clear()
            state = MobileSignalState()
            adapterFlowInstalled.set(false)
            installed.set(false)
        }
    }

    override fun saveHotReloadState(): Any? = adapterReference?.get()

    override fun restoreHotReloadState(state: Any?) {
        if (state != null) HostAccess.onMain { recoverAdapter(state) }
    }

    internal fun recoverAdapter(adapter: Any) {
        if (!enabled) return
        setupAdapter(adapter)
        val lazy = readField(adapter, "mobileIconsViewModel") ?: return
        val icons = unwrapLazy(lazy) ?: lazy
        val cache = readField(icons, "reuseCache") as? Map<*, *> ?: return
        val token = generation.get()
        cache.forEach { (key, value) ->
            val subId = (key as? Number)?.toInt() ?: return@forEach
            val triple = value ?: return@forEach
            val vm = triplePart(triple, "getThird") ?: return@forEach
            if (bindings[subId]?.viewModel !== vm) registerSubscription(subId, triple, token)
        }
        applyMask()
        DebugLog.i(TAG, "hot reload SIM visibility subscriptions=${bindings.size}")
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        val stackedEnabled = settingIconStackedEnabled
        hideNonDefault = settingIconHideNonDefaultSim || settingIconHideSimAuto
        hideSimOne = settingIconHideSimOne
        hideSimTwo = settingIconHideSimTwo
        hideOnWifi = settingIconHideMobileOnWifi
        if (stackedEnabled ||
            (!hideNonDefault && !hideSimOne && !hideSimTwo && !hideOnWifi)
        ) {
            DebugLog.hookSkippedDebug(
                TAG,
                "HideCellularIcon",
                if (stackedEnabled) "stacked signal owns visibility" else "hide sim options off"
            )
            return
        }
        enabled = true
        if (!installed.compareAndSet(false, true)) return
        if (!MobileSignalVisibility.installGetter(this)) {
            DebugLog.hookSkipped(TAG, "MiuiMobileIconVMImpl#isVisible", "getter bridge unavailable")
            return
        }
        hookCreateViewModel()
        hookAdapterStart()
        DebugLog.hookRegistered(TAG, "non-active-data MIUI Pair visibility mask")
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun hookCreateViewModel() {
        val vmClass = ICONS_VM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ICONS_VM_CLASS, "class not found")
            return
        }
        val method = vmClass.findMethodOrNull {
            name("createViewModel")
            paramCount(1)
        } ?: run {
            DebugLog.hookSkipped(TAG, "$ICONS_VM_CLASS#createViewModel", "method not found")
            return
        }
        method.hook {
            after { param ->
                val subId = (param.args.getOrNull(0) as? Number)?.toInt() ?: return@after
                val triple = param.result ?: return@after
                val bindingGeneration = generation.get()
                mainHandler.post {
                    if (enabled && generation.get() == bindingGeneration) {
                        registerSubscription(subId, triple, bindingGeneration)
                    }
                }
            }
        }
    }

    private fun hookAdapterStart() {
        val adapterClass = ADAPTER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ADAPTER_CLASS, "class not found")
            return
        }
        val method = adapterClass.findMethodOrNull { name("start"); noParams() } ?: run {
            DebugLog.hookSkipped(TAG, "$ADAPTER_CLASS#start", "method not found")
            return
        }
        method.hook {
            after { param ->
                runCatching { setupAdapter(param.thisObject) }
                    .onFailure { DebugLog.w(TAG, "hide-cellular adapter setup failed", it) }
            }
        }
    }

    private fun setupAdapter(adapter: Any) {
        adapterReference = java.lang.ref.WeakReference(adapter)
        if (!adapterFlowInstalled.compareAndSet(false, true)) return
        val scope = readField(adapter, "scope")
        val iconsLazy = readField(adapter, "mobileIconsViewModel")
        val iconsVm = iconsLazy?.let(::unwrapLazy) ?: iconsLazy
        if (scope == null || iconsVm == null) {
            adapterFlowInstalled.set(false)
            DebugLog.hookSkipped(TAG, "MobileUiAdapter fields", "scope/mobileIconsViewModel missing")
            return
        }
        val activeDataFlow = readField(iconsVm, "activeMobileDataSubscriptionId")
        val subscriptionsFlow = readField(iconsVm, "subscriptionIdsFlow")
        val airplaneInteractor = readField(iconsVm, "airplaneModeInteractor")
        val airplaneFlow = airplaneInteractor?.let { readField(it, "isAirplaneMode") }
        if (activeDataFlow == null || subscriptionsFlow == null || airplaneFlow == null) {
            adapterFlowInstalled.set(false)
            DebugLog.hookSkipped(TAG, "MobileIconsViewModel flows", "required source flow missing")
            return
        }
        val bindingGeneration = generation.get()
        val adapterHandles = ArrayList<HostFlowCollector.Handle>(3)
        fun required(flow: Any, consumer: (Any?) -> Unit): Boolean {
            val handle = HostFlowCollector.collect(
                scope = scope,
                flow = flow,
                isCurrent = { enabled && generation.get() == bindingGeneration },
                consumer = consumer
            ) ?: return false
            flowHandles += handle
            adapterHandles += handle
            IconTunerFlows.readFlowValue(flow)?.let { value ->
                mainHandler.post {
                    if (enabled && generation.get() == bindingGeneration) consumer(value)
                }
            }
            return true
        }
        if (!required(activeDataFlow) { value ->
                reduceOnMain(
                    MobileSignalEvent.ActiveDataSubId(
                        (value as? Number)?.toInt() ?: MobileSignalState.INVALID_SUB_ID
                    ),
                    bindingGeneration
                )
            } || !required(subscriptionsFlow) { value ->
                reduceOnMain(
                    MobileSignalEvent.Subscriptions(extractSubscriptionIds(value)),
                    bindingGeneration
                )
            } || !required(airplaneFlow) { value ->
                reduceOnMain(MobileSignalEvent.AirplaneMode(value as? Boolean == true), bindingGeneration)
            }
        ) {
            adapterHandles.forEach { it.cancel() }
            flowHandles.removeAll(adapterHandles.toSet())
            adapterFlowInstalled.set(false)
            DebugLog.w(TAG, "hide-cellular flow set not ready")
            return
        }
    }

    private fun registerSubscription(subId: Int, triple: Any, bindingGeneration: Long) {
        val actualMiuiViewModel = triplePart(triple, "getThird") ?: run {
            DebugLog.w(TAG, "createViewModel returned no MIUI VM subId=$subId")
            return
        }
        val scope = triplePart(triple, "getSecond") ?: run {
            DebugLog.w(TAG, "createViewModel returned no scope subId=$subId")
            return
        }
        // Resolved through the host field, never through the hooked getter: once this VM has a
        // registration, `isVisible()` answers with the module's own exposed flow.
        val originalVisibleFlow = MobileSignalVisibility.hostVisibilityFlow(actualMiuiViewModel) {
            invokeNoArg(actualMiuiViewModel, "isVisible")
        } ?: run {
            DebugLog.w(TAG, "MIUI VM visibility getter missing subId=$subId")
            return
        }

        removeBinding(subId)
        val registration = MobileSignalVisibility.register(
            actualMiuiViewModel,
            subId,
            originalVisibleFlow
        ) ?: return
        pendingBindings[actualMiuiViewModel] = true
        val isCurrent = {
            enabled && generation.get() == bindingGeneration &&
                (bindings[subId]?.viewModel === actualMiuiViewModel ||
                    pendingBindings.containsKey(actualMiuiViewModel))
        }
        val handle = MobileSignalVisibility.collectOriginal(
            scope = scope,
            originalFlow = originalVisibleFlow,
            registration = registration,
            isCurrent = isCurrent
        )
        if (handle == null) {
            pendingBindings.remove(actualMiuiViewModel)
            MobileSignalVisibility.unregister(actualMiuiViewModel)
            DebugLog.w(TAG, "MIUI visibility flow not collectable subId=$subId")
            return
        }
        // Same source the stacked type policy uses; only needed while the WiFi rule is on.
        val wifiAvailableFlow = if (hideOnWifi) {
            readField(actualMiuiViewModel, "iconInteractor")
                ?.let { readField(it, "wifiAvailable") }
        } else null
        val wifiHandle = wifiAvailableFlow?.let { flow ->
            HostFlowCollector.collect(
                scope = scope,
                flow = flow,
                isCurrent = isCurrent,
                consumer = { value ->
                    reduceOnMain(
                        MobileSignalEvent.WifiAvailable(subId, value as? Boolean == true),
                        bindingGeneration
                    )
                }
            )?.also { collected ->
                flowHandles += collected
                IconTunerFlows.readFlowValue(flow)?.let { initial ->
                    mainHandler.post {
                        reduceOnMain(
                            MobileSignalEvent.WifiAvailable(subId, initial as? Boolean == true),
                            bindingGeneration
                        )
                    }
                }
            }
        }
        flowHandles += handle
        bindings[subId] = Binding(actualMiuiViewModel, handle, wifiHandle)
        pendingBindings.remove(actualMiuiViewModel)
        applyMask()
    }

    private fun reduceOnMain(event: MobileSignalEvent, bindingGeneration: Long) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { reduceOnMain(event, bindingGeneration) }
            return
        }
        if (!enabled || generation.get() != bindingGeneration) return
        if (event is MobileSignalEvent.WifiAvailable) {
            wifiBySub[event.subId] = event.value
            applyMask()
            return
        }
        val subId = when (event) {
            is MobileSignalEvent.SignalModel -> event.subId
            is MobileSignalEvent.DataConnected -> event.subId
            is MobileSignalEvent.InService -> event.subId
            is MobileSignalEvent.Roaming -> event.subId
            is MobileSignalEvent.NonTerrestrial -> event.subId
            is MobileSignalEvent.OriginalVisibility -> event.subId
            else -> null
        }
        if (subId != null && subId !in state.subscriptions) return
        if (event is MobileSignalEvent.Subscriptions) {
            val ids = event.subIds.toSet()
            bindings.keys.toList().filter { it !in ids }.forEach(::removeBinding)
            wifiBySub.keys.toList().filter { it !in ids }.forEach(wifiBySub::remove)
        }
        state = state.reduce(event)
        applyMask()
    }

    private fun applyMask() {
        if (!enabled) return
        val complete = state.subscriptionOrder.isNotEmpty() &&
            state.subscriptionOrder.all { bindings.containsKey(it) }
        val mask = LinkedHashSet<Int>()
        // The pre-existing SIM options keep their historical behavior: the non-default row is
        // masked whenever any of them is active. The WiFi rule alone must not mask it.
        if (hideNonDefault || hideSimOne || hideSimTwo) {
            mask += state.nonDefaultMask(complete)
        }
        if (complete) {
            state.subscriptionOrder.forEach { subId ->
                val slot = runCatching { SubscriptionManager.getSlotIndex(subId) }
                    .getOrDefault(SubscriptionManager.INVALID_SIM_SLOT_INDEX)
                if ((slot == 0 && hideSimOne) || (slot == 1 && hideSimTwo)) mask += subId
            }
        }
        // WiFi connected hides every mobile row, signal strength included (first Pair member).
        // Only known subscriptions count, so a value left by a removed row cannot mask the rest.
        if (hideOnWifi &&
            wifiBySub.any { (subId, available) -> available && subId in state.subscriptions }
        ) {
            mask += state.subscriptionOrder
        }
        MobileSignalVisibility.setHiddenForSubIds(mask)
    }

    private fun removeBinding(subId: Int) {
        val binding = bindings.remove(subId) ?: return
        binding.visibilityHandle.cancel()
        flowHandles.remove(binding.visibilityHandle)
        binding.wifiHandle?.let {
            it.cancel()
            flowHandles.remove(it)
        }
        wifiBySub.remove(subId)
        MobileSignalVisibility.unregister(binding.viewModel)
        applyMask()
    }

    private fun extractSubscriptionIds(value: Any?): List<Int> {
        val list = value as? Iterable<*> ?: return emptyList()
        return list.mapNotNull { item ->
            when (item) {
                is Number -> item.toInt()
                null -> null
                else -> readIntOrNull(item, "subscriptionId")
                    ?: invokeNoArg(item, "getSubscriptionId")?.let { (it as? Number)?.toInt() }
            }
        }.filter { it != MobileSignalState.INVALID_SUB_ID }.distinct()
    }

    private fun triplePart(triple: Any, getter: String): Any? = invokeNoArg(triple, getter)

    private fun unwrapLazy(value: Any): Any? = invokeNoArg(value, "get")

    private fun invokeNoArg(target: Any?, name: String): Any? = target?.let {
        runCatching { findMethod(it.javaClass, name, 0)?.invoke(it) }.getOrNull()
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        findField(target.javaClass, name)?.get(target)
    }.getOrNull()

    private fun readIntOrNull(target: Any, name: String): Int? = runCatching {
        val field = findField(target.javaClass, name) ?: return@runCatching null
        val value = if (field.type == Int::class.javaPrimitiveType) field.getInt(target) else field.get(target)
        (value as? Number)?.toInt()
    }.getOrNull()

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { return HostAccess.requireDeclaredField(current, name, accessible = true) }
            current = current.superclass
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String, parameterCount: Int): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == parameterCount
            }?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return type.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == parameterCount
        }?.apply { isAccessible = true }
    }
}
