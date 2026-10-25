package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.presentation.icon.MobileSignalEvent
import com.takekazex.hypertweak.presentation.icon.MobileSignalModel
import com.takekazex.hypertweak.presentation.icon.MobileSignalState

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Host mobile pipeline and reducer. Independent of stacked/Duo/carrier artwork or slots. */
internal class MobileSignalSource(
    owner: BaseHooker,
    private val generation: AtomicLong,
    private val active: () -> Boolean,
    private val replacesNative: () -> Boolean,
    private val context: () -> Context?,
    private val onAdapterReady: (Any, Long) -> Unit,
    private val scheduleRender: (Long) -> Unit
) : HookComponent(owner) {
    private companion object {
        const val TAG = "IconTuner"
        const val ADAPTER_CLASS = "com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter"
        const val SYSUI_COMPONENT_FIELD = "mSysUIComponent"
        const val STARTABLES_METHOD = "getStartables"
        const val PER_USER_STARTABLES_METHOD = "getPerUserStartables"
        const val ICONS_VM_CLASS = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel"
    }
    private val adapterFlowInstalled = AtomicBoolean(false)
    private val flowHandles = CopyOnWriteArrayList<HostFlowCollector.Handle>()
    private val bindings = LinkedHashMap<Int, SubBinding>()
    private val pendingBindings = IdentityHashMap<Any, Boolean>()
    @Volatile
    private var adapterFlowsReady = false
    private var factoryViewModelIds: Set<Int> = emptySet()
    @Volatile
    private var adapterReference: WeakReference<Any>? = null
    @Volatile
    private var mobileIconsViewModelReference: WeakReference<Any>? = null
    private var adapterDiscoveryGeneration = 0L
    private var adapterDiscoveryAttempt = 0
    private val session = MobileSignalSession()
    val signalState get() = session.state
    private data class SubBinding(
        val subId: Int,
        val miuiViewModel: Any,
        val visibility: MobileSignalVisibility.Registration?,
        val handles: List<HostFlowCollector.Handle>,
        val bindingGeneration: Long
    )

    private fun newSubscriptions() = HostFlowSubscriptions(
        acquired = { flowHandles += it },
        released = { flowHandles.removeAll(it.toSet()) }
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val enabled get() = active()
    private val renderStacked get() = replacesNative()
    val complete: Boolean get() = session.complete(adapterFlowsReady, factoryViewModelIds) {
        bindings[it]?.bindingGeneration == generation.get()
    }
    fun snapshot(): Any? = adapterReference?.get()
    fun restore(state: Any?) {
        val adapter = state ?: return
        adapterReference = WeakReference(adapter)
        scheduleAdapterRestore(adapter, generation.get())
    }
    fun retire() {
        flowHandles.forEach { it.cancel() }
        flowHandles.clear()
        bindings.values.toList().forEach { binding ->
            binding.handles.forEach { it.cancel() }
            if (binding.visibility != null) MobileSignalVisibility.unregister(binding.miuiViewModel)
        }
        bindings.clear()
        pendingBindings.clear()
        factoryViewModelIds = emptySet()
        session.clear()
        adapterReference = null
        mobileIconsViewModelReference = null
        adapterDiscoveryGeneration = generation.get()
        adapterDiscoveryAttempt = 0
        adapterFlowInstalled.set(false)
        adapterFlowsReady = false
    }
    fun hookCreateViewModel() {
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
                val result = param.result ?: return@after
                val bindingGeneration = generation.get()
                // Factory work is launched from the host pipeline scope. Registration and all
                // reducer updates are serialized with icon-controller operations on main.
                mainHandler.post {
                    if (!enabled || generation.get() != bindingGeneration) return@post
                    registerSubscription(subId, result, bindingGeneration)
                }
            }
        }
    }

    fun hookAdapterStart() {
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
                    .onFailure { DebugLog.w(TAG, "stacked adapter setup failed", it) }
            }
        }
    }

    /** Source attachment, valid even when there are currently no SIM rows to render. */
    val mobilePipelineConnected: Boolean get() = adapterFlowsReady

    fun recoverAdapter(adapter: Any) {
        if (!enabled) return
        setupAdapter(adapter)
        val icons = mobileIconsViewModelReference?.get() ?: return
        val flow = readField(icons, "mobileSubViewModels")
        restoreFactoryViewModelsOnMain(flow?.let(IconTunerFlows::readFlowValue), generation.get())
    }

    private fun setupAdapter(adapter: Any) {
        adapterReference = WeakReference(adapter)
        if (!adapterFlowInstalled.compareAndSet(false, true)) return
        val bindingGeneration = generation.get()
        val scope = readField(adapter, "scope")
        val controller = readField(adapter, "iconController")
        val iconsLazy = readField(adapter, "mobileIconsViewModel")
        val iconsVm = iconsLazy?.let(::unwrapLazy) ?: iconsLazy
        if (scope == null || controller == null || iconsVm == null) {
            adapterFlowInstalled.set(false)
            DebugLog.hookSkipped(TAG, "MobileUiAdapter fields", "scope/controller/mobileIconsViewModel missing")
            return
        }
        mobileIconsViewModelReference = WeakReference(iconsVm)
        val activeDataFlow = readField(iconsVm, "activeMobileDataSubscriptionId")
        val subscriptionIdsFlow = readField(iconsVm, "subscriptionIdsFlow")
        val mobileSubViewModelsFlow = readField(iconsVm, "mobileSubViewModels")
        val airplaneInteractor = readField(iconsVm, "airplaneModeInteractor")
        val airplaneFlow = airplaneInteractor?.let { readField(it, "isAirplaneMode") }
        if (activeDataFlow == null || subscriptionIdsFlow == null ||
            mobileSubViewModelsFlow == null || airplaneFlow == null
        ) {
            adapterFlowInstalled.set(false)
            DebugLog.hookSkipped(TAG, "MobileIconsViewModel flows", "required source flow missing")
            return
        }

        onAdapterReady(controller, bindingGeneration)

        val subscriptions = newSubscriptions()
        val isCurrent = { enabled && generation.get() == bindingGeneration }
        fun required(flow: Any, consumer: (Any?) -> Unit): Boolean =
            subscriptions.collect(scope, flow, isCurrent, replay = true, consumer = consumer)

        if (!required(activeDataFlow) { value ->
                val subId = (value as? Number)?.toInt() ?: MobileSignalState.INVALID_SUB_ID
                reduceOnMain(MobileSignalEvent.ActiveDataSubId(subId), bindingGeneration)
            } || !required(subscriptionIdsFlow) { value ->
                reduceSubscriptionsOnMain(extractSubscriptionIds(value), bindingGeneration)
            } || !required(mobileSubViewModelsFlow) { value ->
                // The public flow exposes AOSP VM entries; restoreFactoryViewModelsOnMain joins
                // them with MobileIconsViewModel.reuseCache to recover the full MIUI triples.
                restoreFactoryViewModelsOnMain(value, bindingGeneration)
            } || !required(airplaneFlow) { value ->
                reduceOnMain(MobileSignalEvent.AirplaneMode(value as? Boolean == true), bindingGeneration)
            }
        ) {
            subscriptions.clear()
            adapterFlowInstalled.set(false)
            DebugLog.w(TAG, "stacked signal not ready: one adapter flow did not bind")
            return
        }
        adapterFlowsReady = true
        DebugLog.d(TAG, "stacked signal adapter flows bound")
        scheduleRender(bindingGeneration)
    }

    /** Replays setup for an adapter whose start() callback ran before module hot reload. */
    fun scheduleAdapterRestore(adapter: Any, bindingGeneration: Long) {
        mainHandler.post {
            if (!enabled || generation.get() != bindingGeneration || adapterFlowInstalled.get()) return@post
            runCatching { setupAdapter(adapter) }
                .onFailure { DebugLog.w(TAG, "restored stacked adapter setup failed", it) }
        }
    }

    /**
     * Recovers the already-started MobileUiAdapter when the replacement generation has no saved
     * adapter reference. Hot reload normally carries the host object across generations, but the
     * first upgrade from an older module build cannot do that. SystemUI's Dagger component keeps
     * the same singleton in its startable-provider map, so resolving only this verified entry is
     * enough to reconnect without constructing or restarting any host component.
     */
    fun scheduleExistingAdapterDiscovery() {
        val bindingGeneration = generation.get()
        adapterDiscoveryGeneration = bindingGeneration
        adapterDiscoveryAttempt = 0
        mainHandler.post { discoverExistingAdapterOnMain(bindingGeneration) }
    }

    private fun discoverExistingAdapterOnMain(bindingGeneration: Long) {
        if (!enabled || generation.get() != bindingGeneration || adapterFlowInstalled.get()) return
        // Give SystemUI's already-started service array a chance to become visible first. Calling
        // the Dagger Provider too early can eagerly initialize unrelated startables during boot.
        val adapter = findExistingAdapter(allowProvider = adapterDiscoveryAttempt >= 3)
        if (adapter != null) {
            DebugLog.d(TAG, "recovered existing MobileUiAdapter from SysUI component")
            runCatching { setupAdapter(adapter) }
                .onFailure { DebugLog.w(TAG, "existing stacked adapter setup failed", it) }
            return
        }

        if (adapterDiscoveryGeneration != bindingGeneration) {
            adapterDiscoveryGeneration = bindingGeneration
            adapterDiscoveryAttempt = 0
        }
        adapterDiscoveryAttempt++
        if (adapterDiscoveryAttempt >= 30) {
            DebugLog.w(TAG, "existing MobileUiAdapter discovery timed out")
            return
        }
        mainHandler.postDelayed(
            { discoverExistingAdapterOnMain(bindingGeneration) },
            500L
        )
    }

    private fun findExistingAdapter(allowProvider: Boolean): Any? {
        val context = context()?.let { it.applicationContext ?: it } ?: return null
        val services = readField(context, "mServices")?.let { invokeNoArg(it, "get") }
        findAdapterInCollection(services)?.let { return it }

        if (!allowProvider) return null

        val component = readField(context, SYSUI_COMPONENT_FIELD)
            ?: readField(context, "mInitializer")?.let { invokeNoArg(it, "getSysUIComponent") }
            ?: return null
        val maps = listOf(STARTABLES_METHOD, PER_USER_STARTABLES_METHOD)
        maps.forEach { methodName ->
            val startables = invokeNoArg(component, methodName) as? Map<*, *> ?: return@forEach
            val provider = startables.entries.firstOrNull { (key, _) ->
                (key as? Class<*>)?.name == ADAPTER_CLASS
            }?.value ?: return@forEach
            val adapter = invokeNoArg(provider, "get")
            if (adapter != null && adapter.javaClass.name == ADAPTER_CLASS) return adapter
        }
        return null
    }

    private fun findAdapterInCollection(value: Any?): Any? {
        val entries: Iterable<*> = when (value) {
            is Iterable<*> -> value
            is Array<*> -> value.asIterable()
            else -> return null
        }
        return entries.firstOrNull { it?.javaClass?.name == ADAPTER_CLASS }
    }

    private fun registerSubscription(subId: Int, triple: Any, bindingGeneration: Long) {
        val aospViewModel = triplePart(triple, "getFirst")
        val scope = triplePart(triple, "getSecond")
        val miuiViewModel = triplePart(triple, "getThird")
        if (aospViewModel == null || scope == null || miuiViewModel == null) {
            DebugLog.w(TAG, "createViewModel returned incomplete Triple subId=$subId")
            return
        }
        // One subscription reaches this function twice on a normal boot: once from the
        // createViewModel after-hook and once from the mobileSubViewModels emission. The second
        // pass must not re-register the live VM — `isVisible()` then answers with the module's own
        // exposed flow, so the replacement gate would be re-derived from the module's mask (and
        // from the host Pair's false initial value) instead of from the host.
        val bound = bindings[subId]
        if (bound != null && bound.miuiViewModel === miuiViewModel &&
            bound.bindingGeneration == bindingGeneration
        ) {
            return
        }
        val originInteractor = invokeNoArg(miuiViewModel, "getOriginIconInteractor")
        val originalVisibleFlow = MobileSignalVisibility.hostVisibilityFlow(miuiViewModel) {
            invokeNoArg(miuiViewModel, "isVisible")
        }
        val signalFlow = originInteractor?.let { invokeNoArg(it, "getSignalLevelIcon") }
        val dataConnectedFlow = originInteractor?.let { invokeNoArg(it, "isDataConnected") }
        val inServiceFlow = originInteractor?.let { invokeNoArg(it, "isInService") }
        val roamingFlow = originInteractor?.let { invokeNoArg(it, "isRoaming") }
        val nonTerrestrialFlow = originInteractor?.let { invokeNoArg(it, "isNonTerrestrial") }
        val networkTypeFlow = invokeNoArg(miuiViewModel, "getShowName")
        val miuiInteractor = readField(miuiViewModel, "iconInteractor")
        val wifiAvailableFlow = miuiInteractor?.let { readField(it, "wifiAvailable") }
        if (originInteractor == null || originalVisibleFlow == null || signalFlow == null ||
            dataConnectedFlow == null || inServiceFlow == null || roamingFlow == null ||
            nonTerrestrialFlow == null
        ) {
            DebugLog.w(TAG, "createViewModel flow set incomplete subId=$subId")
            return
        }

        bindings.remove(subId)?.let { old ->
            old.handles.forEach { it.cancel() }
            if (old.visibility != null) MobileSignalVisibility.unregister(old.miuiViewModel)
        }
        // Duo only observes. It must not replace a hide-SIM hook's registration or clear its mask.
        val visibility = if (renderStacked) {
            MobileSignalVisibility.register(miuiViewModel, subId, originalVisibleFlow) ?: return
        } else null
        pendingBindings[miuiViewModel] = true
        val subscriptions = newSubscriptions()
        val isCurrent = {
            enabled && generation.get() == bindingGeneration &&
                (bindings[subId]?.miuiViewModel === miuiViewModel || pendingBindings.containsKey(miuiViewModel))
        }
        fun required(flow: Any, consumer: (Any?) -> Unit): Boolean =
            subscriptions.collect(scope, flow, isCurrent, replay = true, consumer = consumer)
        fun optional(flow: Any?, consumer: (Any?) -> Unit) {
            if (flow != null && !subscriptions.collect(scope, flow, isCurrent, replay = true, consumer = consumer))
                DebugLog.w(TAG, "optional type flow not collectable subId=$subId")
        }

        fun originalVisibilityConsumer(value: Any?): Unit {
            val pair = MobileSignalVisibility.pairValueOf(value) ?: return
            reduceOnMain(
                MobileSignalEvent.OriginalVisibility(subId, pair.first, pair.second),
                bindingGeneration
            )
        }

        /**
         * Collects the host Pair through its registration so the exposed flow keeps following the
         * host value. Without this the registration would answer every host reader with the pair
         * it saw when it was created (usually the false initial state), and the module's own
         * replacement gate would be reading that stale value back through the getter.
         */
        fun requiredOriginalVisibility(): Boolean = subscriptions.collectUsing(
            originalVisibleFlow, isCurrent, replay = true,
            start = { consumer, current ->
                val registration = visibility
                if (registration != null) MobileSignalVisibility.collectOriginal(
                    scope = scope, originalFlow = originalVisibleFlow, registration = registration,
                    isCurrent = current, consumer = consumer
                ) else HostFlowCollector.collect(scope, originalVisibleFlow, consumer, current)
            },
            consumer = ::originalVisibilityConsumer
        )

        // Register the host visibility flow with the same cancellation/generation gate as the
        // signal flows. It remains a Pair so the binder's detail chain keeps its second value.
        val allBound = requiredOriginalVisibility() && required(signalFlow) { value ->
            reduceOnMain(
                MobileSignalEvent.SignalModel(subId, parseSignalModel(value)),
                bindingGeneration
            )
        } && required(dataConnectedFlow) { value ->
            reduceOnMain(MobileSignalEvent.DataConnected(subId, value as? Boolean == true), bindingGeneration)
        } && required(inServiceFlow) { value ->
            reduceOnMain(MobileSignalEvent.InService(subId, value as? Boolean == true), bindingGeneration)
        } && required(roamingFlow) { value ->
            reduceOnMain(MobileSignalEvent.Roaming(subId, value as? Boolean == true), bindingGeneration)
        } && required(nonTerrestrialFlow) { value ->
            reduceOnMain(MobileSignalEvent.NonTerrestrial(subId, value as? Boolean == true), bindingGeneration)
        }
        if (!allBound) {
            subscriptions.clear()
            if (visibility != null) MobileSignalVisibility.unregister(miuiViewModel)
            pendingBindings.remove(miuiViewModel)
            DebugLog.w(TAG, "subscription flow set not ready subId=$subId")
            return
        }
        optional(networkTypeFlow) { value ->
            reduceOnMain(MobileSignalEvent.NetworkType(subId, value?.toString()), bindingGeneration)
        }
        optional(wifiAvailableFlow) { value ->
            reduceOnMain(MobileSignalEvent.WifiAvailable(subId, value as? Boolean == true), bindingGeneration)
        }
        bindings[subId] = SubBinding(
            subId = subId,
            miuiViewModel = miuiViewModel,
            visibility = visibility,
            handles = subscriptions.snapshot(),
            bindingGeneration = bindingGeneration
        )
        pendingBindings.remove(miuiViewModel)
        scheduleRender(bindingGeneration)
    }

    private fun reduceSubscriptionsOnMain(ids: List<Int>, bindingGeneration: Long) {
        if (generation.get() != bindingGeneration || !enabled) return
        val current = ids.toSet()
        bindings.values.filter { it.subId !in current }.toList().forEach { binding ->
            bindings.remove(binding.subId)
            binding.handles.forEach { it.cancel() }
            flowHandles.removeAll(binding.handles.toSet())
            if (binding.visibility != null) MobileSignalVisibility.unregister(binding.miuiViewModel)
        }
        session.subscriptions(ids)
        scheduleRender(bindingGeneration)
    }

    /** Re-registers existing `(AOSP VM, scope, MIUI VM)` triples after hot reload. */
    private fun restoreFactoryViewModelsOnMain(value: Any?, bindingGeneration: Long) {
        if (generation.get() != bindingGeneration || !enabled) return
        val entries: Iterable<*> = when (value) {
            is Map<*, *> -> value.values
            is Iterable<*> -> value
            else -> emptyList<Any?>()
        }
        val cachedTriples = LinkedHashMap<Int, Any>()
        (mobileIconsViewModelReference?.get()?.let { readField(it, "reuseCache") } as? Map<*, *>)
            ?.forEach { (key, cached) ->
                val triple = cached ?: return@forEach
                val subId = (key as? Number)?.toInt() ?: subscriptionIdOfTriple(triple)
                if (subId != null && triplePart(triple, "getFirst") != null &&
                    triplePart(triple, "getThird") != null
                ) {
                    cachedTriples[subId] = triple
                }
            }
        entries.forEach { entry ->
            val item = entry ?: return@forEach
            val directTriple = item.takeIf {
                triplePart(it, "getFirst") != null && triplePart(it, "getThird") != null
            }
            val subId = directTriple?.let(::subscriptionIdOfTriple) ?: subscriptionIdOf(item)
                ?: return@forEach
            val triple = directTriple ?: cachedTriples[subId] ?: return@forEach
            val miuiViewModel = triplePart(triple, "getThird")
            if (miuiViewModel != null && bindings[subId]?.miuiViewModel === miuiViewModel) {
                return@forEach
            }
            registerSubscription(subId, triple, bindingGeneration)
        }
        cachedTriples.forEach { (subId, triple) ->
            if (bindings[subId]?.miuiViewModel !== triplePart(triple, "getThird")) {
                registerSubscription(subId, triple, bindingGeneration)
            }
        }
        factoryViewModelIds = (extractSubscriptionIds(value) + cachedTriples.keys).toSet()
        scheduleRender(bindingGeneration)
    }

    private fun reduceOnMain(event: MobileSignalEvent, bindingGeneration: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { reduceOnMain(event, bindingGeneration) }
            return
        }
        if (!enabled || generation.get() != bindingGeneration) return
        if (session.reduce(event)) scheduleRender(bindingGeneration)
    }

    private fun parseSignalModel(value: Any?): MobileSignalModel {
        if (value == null) return MobileSignalModel.unknown()
        val name = value.javaClass.name
        if (name.contains("SignalIconModel\$CellularTypeIconModel\$Cellular")) {
            return MobileSignalModel.cellular(
                level = readInt(value, "level", 0),
                numberOfLevels = readInt(value, "numberOfLevels", 5),
                showExclamationMark = readBoolean(value, "showExclamationMark"),
                carrierNetworkChange = readBoolean(value, "carrierNetworkChange")
            )
        }
        if (name.contains("Satellite")) {
            return MobileSignalModel.satellite(
                level = readInt(value, "level", 0),
                numberOfLevels = readInt(value, "numberOfLevels", 5)
            )
        }
        return MobileSignalModel.unknown()
    }

    private fun extractSubscriptionIds(value: Any?): List<Int> {
        val list: Iterable<*> = when (value) {
            is Map<*, *> -> value.values
            is Iterable<*> -> value
            else -> return emptyList()
        }
        return list.mapNotNull { item ->
            when (item) {
                is Number -> item.toInt()
                null -> null
                else -> subscriptionIdOf(item)
            }
        }.filter { it != MobileSignalState.INVALID_SUB_ID }.distinct()
    }

    private fun subscriptionIdOfTriple(triple: Any): Int? =
        triplePart(triple, "getThird")?.let(::subscriptionIdOf)
            ?: triplePart(triple, "getFirst")?.let(::subscriptionIdOf)

    private fun subscriptionIdOf(value: Any): Int? =
        readIntOrNull(value, "subscriptionId")
            ?: (invokeNoArg(value, "getSubscriptionId") as? Number)?.toInt()
            ?: readIntOrNull(value, "subId")
            ?: (invokeNoArg(value, "getSubId") as? Number)?.toInt()
            // MiuiMobileIconVMImpl keeps the subscription id in its interactor rather than on
            // the VM itself. This path is needed when replaying existing triples after reload;
            // the createViewModel(int) hook supplies the id on a cold start.
            ?: readField(value, "originIconInteractor")?.let(::subscriptionIdOf)
            ?: readField(value, "iconInteractor")?.let(::subscriptionIdOf)

    private fun triplePart(triple: Any, getter: String): Any? = invokeNoArg(triple, getter)

    private fun unwrapLazy(value: Any): Any? = invokeNoArg(value, "get")

    private fun invokeNoArg(target: Any, name: String): Any? = runCatching { HostAccess.invoke(target, name) }.getOrNull()
    private fun readField(target: Any, name: String): Any? = runCatching { HostAccess.read(target, name) }.getOrNull()
    private fun readInt(target: Any, name: String, default: Int): Int = readIntOrNull(target, name) ?: default
    private fun readIntOrNull(target: Any, name: String): Int? = (readField(target, name) as? Number)?.toInt()
    private fun readBoolean(target: Any, name: String): Boolean = readField(target, name) as? Boolean ?: false
}
