package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.cachedPublicField
import com.takekazex.hypertweak.hook.base.orSkip
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Wi-Fi visibility and standard-label options for the existing MIUI Wi-Fi pipeline.
 *
 * Live icon and standard overrides are exposed through the native binder getters. Their original
 * producers continue to run, and the module flows re-publish on a settings change, so the bound
 * binder updates without a rebind or a network event. The activity arrows and their direction are
 * [LiveFlowGate]s. Padding and accessory placement are applied after the verified
 * `MiuiWifiViewBinder.bind` layout setup, limited to the host's `wifi_group` view, and re-applied
 * (or restored) on the recorded roots when a setting changes.
 */
object WifiPipelineAdapter : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconHideWifiActivity by hookSetting(false) { Preferences.iconConfig().wifi.hideWifiActivity }
    private val settingIconHideWifiUnavailable by hookSetting(false) { Preferences.iconConfig().wifi.hideWifiUnavailable }
    private val settingIconWifiStandardMode by hookSetting(0) { Preferences.iconConfig().wifi.wifiStandardMode }
    private val settingIconWifiStandardMap by hookSetting("4,5,6,7,8") { Preferences.iconConfig().wifi.wifiStandardMap }
    private val settingIconWifiPadding by hookSetting(false) { Preferences.iconConfig().wifi.wifiPadding }
    private val settingIconWifiPaddingStartVal by hookSetting(0f) { Preferences.iconConfig().wifi.wifiPaddingStartVal }
    private val settingIconWifiPaddingEndVal by hookSetting(0f) { Preferences.iconConfig().wifi.wifiPaddingEndVal }
    private val settingIconWifiActivityRight by hookSetting(false) { Preferences.iconConfig().wifi.wifiActivityRight }

    init {
        hostRecovery(HostRecoveryPhase.SOURCES, icons = true) {
            val scope = it.coroutineScope
            val interactor = it.wifiInteractor
            val context = it.context
            DebugLog.d(TAG, "Wi-Fi source recovery scope=${scope != null} interactor=${interactor != null} retired=$retired")
            if (scope != null && interactor != null) {
                HostAccess.onMain { if (!retired) runCatching { bindNetwork(scope, interactor, context, null) }
                    .onFailure { error -> DebugLog.w(TAG, "Wi-Fi source recovery failed", error) } }
            } else {
                // Without them Wi-Fi stays unbound until the host builds a new WifiViewModel.
                DebugLog.w(TAG, "Wi-Fi source recovery skipped scope=${scope != null} interactor=${interactor != null}")
            }
            it.component?.let(::recoverViewModel)
        }
    }

    private const val TAG = "IconTuner"
    private const val WIFI_ICON_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon"
    private const val ACTIVE_CLASS = "com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel\$Active"
    private const val HIDDEN_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon\$Hidden"
    private const val VM_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel"
    private const val BINDER_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.binder.MiuiWifiViewBinder"
    private var hideActivity by generationState { false }
    private var hideUnavailable by generationState { false }
    private var standardMode by generationState { 0 }
    private var standardMap by generationState { WifiStandardPolicy.DEFAULT_MAP }
    private var paddingEnabled by generationState { false }
    private var paddingStart by generationState { 0f }
    private var paddingEnd by generationState { 0f }
    private var activityRight by generationState { false }
    /** No member of a value may retain its weak VM key. */
    private class VmState {
        var interactor: WeakReference<Any>? = null
        var scope: WeakReference<Any>? = null
        var icon: Any? = null
        var standard: Any? = null
        var subscriptions = HostFlowSubscriptions()
        var publish: (() -> Unit)? = null
        var restore: (() -> Unit)? = null
        var updateNetwork: ((Any?) -> Unit)? = null
        var updateDirection: ((Any?) -> Unit)? = null
        var activityGate: LiveFlowGate? = null
        var directionGate: LiveFlowGate? = null
        var restoredDirection: Any? = null
        var directionHandle: HostFlowCollector.Handle? = null
        var directionEpoch = 0L
        var directionAttempt = 0L
        val lease = VmLease()
    }
    private val vmStates = WeakVmRegistry<Any, VmState>()
    private var authority: WeakReference<Any>? = null
    private val standardGravity = generationMainResource(WeakHashMap<TextView, Int>()) { it.clear() }
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var recoveryTask: Runnable? by generationState { null }
    private var retired = false
    @Volatile private var lifecycleEpoch = 0L
    private val networkSource = WifiPipelineSource()
    init { generationCleanup { retired = true } }

    override fun onPrepareHotReload() {
        lifecycleEpoch++
        HostAccess.onMain {
            retired = true
            networkSource.retire()
            standardGravity.forEach { (view, original) ->
                (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                    params.gravity = original
                    view.layoutParams = params
                }
            }
            standardGravity.clear()
            activityGravity.forEach { (view, original) ->
                (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                    params.gravity = original
                    view.layoutParams = params
                }
            }
            activityGravity.clear()
            originalPadding.forEach { (group, padding) ->
                group.setPaddingRelative(padding[0], padding[1], padding[2], padding[3])
            }
            originalPadding.clear()
            boundRoots.clear()
            recoveryTask?.let(recoveryHandler::removeCallbacks)
            recoveryTask = null
            vmStates.snapshot().forEach { (_, state) ->
                state.lease.renew()
                state.directionEpoch = ++state.directionAttempt
                state.directionHandle?.cancel()
                state.directionHandle = null
                state.activityGate?.set(false)
                state.directionGate?.set(false)
                state.restore?.invoke()
                state.subscriptions.clear()
                state.updateNetwork = null
                state.updateDirection = null
            }
            authority = null
        }

        standardMap = WifiStandardPolicy.DEFAULT_MAP
    }

    override fun onHook() {
        lifecycleEpoch++
        retired = false
        IconTunerFlows.init(classLoader)
        hideActivity = settingIconHideWifiActivity
        hideUnavailable = settingIconHideWifiUnavailable
        standardMode = settingIconWifiStandardMode.coerceIn(0, 3)
        standardMap = WifiStandardPolicy.parseMap(
            settingIconWifiStandardMap
        )
        paddingEnabled = settingIconWifiPadding
        paddingStart = safeFloat(
            settingIconWifiPaddingStartVal
        )
        paddingEnd = safeFloat(
            settingIconWifiPaddingEndVal
        )
        activityRight = settingIconWifiActivityRight
        // Existing binders still collect the retained gate flows that retirement passed through.
        // Re-arm those same gates before a new getter/bind/network event can happen.
        HostAccess.onMain { restoreVmGates() }
        // Every Wi-Fi option stays hooked while it is off: a settings change re-publishes the
        // module flows the binder already collects, or re-applies the layout to the bound views.
        hookActivityDirection()
        hookBinderLayout()
        hookWifiOverrides()

        // The verified OS4 factory receives isWifiDefault as its third boolean.
        // Keep the host flow intact so a route change immediately restores/hides the icon.
        run {
            val companionClass = "$WIFI_ICON_CLASS\$Companion".toClassOrNull()
            val activeClass = ACTIVE_CLASS.toClassOrNull()
            val hiddenInstance = runCatching {
                HIDDEN_CLASS.toClass().cachedPublicField("INSTANCE", accessible = false).get(null)
            }.getOrNull()
            val fromModel = companionClass?.declaredMethods?.singleOrNull {
                it.name == "fromModel" && it.returnType.name == WIFI_ICON_CLASS &&
                    it.parameterTypes.map(Class<*>::getName) == listOf(
                        "com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel",
                        "android.content.Context", "boolean", "boolean", "boolean", "int")
            }
            if (fromModel == null || activeClass == null || hiddenInstance == null) {
                DebugLog.hookSkipped(
                    TAG,
                    "$WIFI_ICON_CLASS\$Companion#fromModel",
                    "method/class/INSTANCE not found"
                )
            } else {
                // ART may inline this static factory into its flow transform/default wrapper.
                // Deoptimize the verified callers too, so an already-running VM uses the hook.
                companionClass.declaredMethods.filter { it.name == "fromModel\$default" }
                    .forEach { deoptimize(it) }
                "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModelInject\$wifiIcon\$2"
                    .toClassOrNull()?.declaredMethods?.singleOrNull {
                        it.name == "invokeSuspend" && it.returnType == Any::class.java &&
                            it.parameterTypes.contentEquals(arrayOf(Any::class.java))
                    }?.let { deoptimize(it) }
                deoptimize(fromModel)
                fromModel.hook {
                    before { param ->
                        val model = param.args.getOrNull(0)
                        if (!retired && WifiRoutePolicy.hide(
                                activeClass.isInstance(model), param.args.getOrNull(4) as? Boolean, hideConnected = false
                            )) param.result = hiddenInstance
                    }
                }
            }
        }

        // 2. These are stable getter boundaries used by MiuiWifiViewBinder. A field write would be
        // lost because the factory assigns the flows after construction.
        hookVisibilityGetters()
        DebugLog.hookRegistered(
            TAG,
            "WifiIcon: standardMode=$standardMode padding=$paddingEnabled activityRight=$activityRight"
        )
    }

    private fun hookWifiOverrides() {
        val vmClass = VM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, VM_CLASS, "not found on host")
            return
        }
        fun hookGetter(name: String, returnType: String, select: (VmState) -> Any?) {
            val getter = HostAccess.method(vmClass, name) ?: run {
                DebugLog.hookSkipped(TAG, "$VM_CLASS#$name", "not found on host")
                return
            }
            if (getter.returnType.name != IconTunerFlows.hostClassName("kotlinx.coroutines.flow", returnType)) return
            deoptimize(getter)
            VM_CLASS.replace("WifiViewModel", "LocationBasedWifiViewModel").toClassOrNull()
                ?.let { HostAccess.method(it, name) }
                ?.takeIf { it.returnType == getter.returnType }?.let(::deoptimize)
            val collector = if (name == "getWifiIcon") "3" else "5"
            "$BINDER_CLASS\$bind\$1\$1\$$collector".toClassOrNull()
                ?.let { HostAccess.method(it, "invokeSuspend", Any::class.java) }
                ?.takeIf { it.returnType == Any::class.java }?.let(::deoptimize)
            getter.hook {
                after { param ->
                    vmStates.get(param.thisObject)?.let(select)?.let { param.result = it }
                }
            }
        }
        hookGetter("getWifiIcon", "StateFlow") { it.icon }
        hookGetter("getWifiStandard", "Flow") { it.standard }
        vmClass.declaredConstructors.singleOrNull { constructor ->
            constructor.parameterCount == 6 &&
                constructor.parameterTypes[3].name.endsWith(".WifiInteractorImpl") &&
                constructor.parameterTypes[4].name == IconTunerFlows.hostClassName("kotlinx.coroutines", "CoroutineScope")
        }?.hook {
            after { param ->
                val interactor = param.args.getOrNull(3) ?: return@after
                val scope = param.args.getOrNull(4) ?: return@after
                val vm = param.thisObject
                val context = param.args.getOrNull(1) as? Context
                val token = lifecycleEpoch
                if (context != null) HostAccess.onMain { if (!retired && lifecycleEpoch == token) runCatching { bindNetwork(scope, interactor, context, vm) }
                    .onFailure { error -> DebugLog.w(TAG, "Wi-Fi source binding failed", error) } }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    bindWifiOverrides(vm, interactor, scope)
                } else {
                    recoveryHandler.post { if (lifecycleEpoch == token) bindWifiOverrides(vm, interactor, scope) }
                }
            }
        }
    }

    private fun bindNetwork(scope: Any, interactor: Any, context: Context, vm: Any?) {
        val changed = !networkSource.isBoundTo(interactor)
        networkSource.bind(scope, interactor, context)
        if (changed) {
            authority = vm?.let(::WeakReference)
            vmStates.snapshot().forEach { (_, state) ->
                if (state.interactor?.get() !== interactor) {
                    state.updateNetwork?.invoke(null)
                    state.updateDirection?.invoke(null)
                }
            }
        } else if (authority?.get() == null && vm != null) authority = WeakReference(vm)
        networkSource.onNetwork = { value ->
            vmStates.snapshot().forEach { (_, state) ->
                if (state.interactor?.get() === interactor) {
                    runCatching { state.updateNetwork?.invoke(value); state.updateDirection?.invoke(value) }
                        .onFailure { DebugLog.w(TAG, "Wi-Fi VM network update failed", it) }
                }
            }
        }
    }

    private fun bindWifiOverrides(vm: Any, interactor: Any, scope: Any) {
        if (retired) return
        val state = vmStates.getOrPut(vm) { VmState() }
        if (state.interactor?.get() === interactor && state.subscriptions.isBound) return
        state.subscriptions.clear()
        val ticket = state.lease.renew()
        state.interactor = WeakReference(interactor)
        state.scope = WeakReference(scope)
        val weakVm = WeakReference(vm)
        val subscriptions = HostFlowSubscriptions()
        runCatching {
            val iconFlow = readField(vm, "wifiIcon") ?: return
            val standardFlow = readField(vm, "wifiStandard") ?: return
            val networkFlow = readField(interactor, "wifiNetwork") ?: return
            val activeClass = ACTIVE_CLASS.toClass()
            val hidden = HIDDEN_CLASS.toClass().cachedPublicField("INSTANCE", accessible = false).get(null)
            var nativeIcon = IconTunerFlows.readFlowValue(iconFlow) ?: hidden
            var nativeStandard = (IconTunerFlows.readFlowValue(standardFlow) as? Number)?.toInt() ?: 0
            var network = if (networkSource.isBoundTo(interactor)) IconTunerFlows.readFlowValue(networkFlow) else null
            val icon = state.icon ?: checkNotNull(IconTunerFlows.createMutableStateFlow(nativeIcon)).also { state.icon = it }
            val standard = state.standard ?: checkNotNull(IconTunerFlows.createMutableStateFlow(nativeStandard)).also { state.standard = it }
            fun current() = !retired && state.lease.isCurrent(ticket)
            fun isAuthority(): Boolean {
                val candidate = weakVm.get() ?: return false
                if (!networkSource.isBoundTo(interactor)) return false
                if (authority?.get() == null) authority = WeakReference(candidate)
                return authority?.get() === candidate
            }
            fun publish() {
                val active = activeClass.isInstance(network)
                IconTunerFlows.setFlowValue(icon, if (hideUnavailable && active) hidden else nativeIcon)
                val raw = if (active) readInt(readField(network, "ext"), "wifiStandard") else null
                val mapped = if (standardMode == 1) 0 else WifiStandardPolicy.resolve(standardMode, raw, nativeStandard, standardMap)
                IconTunerFlows.setFlowValue(standard, mapped)
            }
            val bound = subscriptions.collect(scope, iconFlow, ::current) { value ->
                nativeIcon = value ?: hidden; publish()
            } && subscriptions.collect(scope, standardFlow, ::current) { value ->
                nativeStandard = (value as? Number)?.toInt() ?: 0
                if (isAuthority()) NetworkHub.publish { it.copy(wifiStandard = (value as? Number)?.toInt()) }
                publish()
            }
            if (!bound) {
                subscriptions.clear()
                DebugLog.hookSkipped(TAG, "Wi-Fi live overrides", "native Flow subscription unavailable")
                return
            }
            subscriptions.collect(scope, readField(vm, "activityInOutRes"), ::current, replay = true) { value ->
                if (isAuthority()) NetworkHub.publish { it.copy(wifiActivity = (value as? Number)?.toInt()) }
            }
            state.subscriptions = subscriptions
            state.publish = ::publish
            state.restore = {
                IconTunerFlows.setFlowValue(icon, nativeIcon)
                IconTunerFlows.setFlowValue(standard, nativeStandard)
            }
            state.updateNetwork = { value -> network = value; publish() }
            if (isAuthority()) NetworkHub.publish { it.copy(wifiStandard = nativeStandard) }
            publish()
        }.onFailure {
            subscriptions.clear()
            DebugLog.w(TAG, "Wi-Fi live overrides failed", it)
        }
    }

    private fun hookVisibilityGetters() {
        val vmClass = VM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
            return
        }
        // The activity arrows stay hooked while the option is off, so a toggle only flips the gate
        // and the bound binder re-emits; resource 0 is the host's "no arrows" value.
        vmClass.findMethodOrNull { name("getActivityInOutRes"); noParams() }?.let { getter ->
            val returnType = getter.returnType
            getter.hook {
                after { param ->
                    if (param.throwable != null) return@after
                    val original = param.result ?: return@after
                    val state = vmStates.getOrPut(param.thisObject) { VmState() }
                    val gate = state.activityGate ?: LiveFlowGate("Wi-Fi activity", hideActivity).also { state.activityGate = it }
                    gate.set(hideActivity)
                    gate.wrap(original, returnType) { value -> if (value is Number) 0 else value }
                        ?.let { param.result = it }
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#getActivityInOutRes", "method not found")
    }

    private val boundRoots = generationMainResource(WeakHashMap<ViewGroup, Unit>()) { it.clear() }
    private val originalPadding = generationMainResource(WeakHashMap<View, IntArray>()) { it.clear() }
    private val activityGravity = generationMainResource(WeakHashMap<View, Int>()) { it.clear() }

    private fun restoreVmGates() {
        vmStates.snapshot().forEach { (_, state) ->
            state.activityGate?.set(hideActivity)
            state.directionGate?.set(activityRight && !hideActivity)
        }
    }

    /**
     * Applies every Wi-Fi option to the bound icon in place: the activity and direction gates and
     * the override flows re-emit through the binder that collects them, and the padding and
     * accessory placement are re-applied to the views the binder already laid out.
     */
    fun refreshSettings() {
        HostAccess.onMain {
            if (retired) return@onMain
            hideActivity = settingIconHideWifiActivity
            hideUnavailable = settingIconHideWifiUnavailable
            standardMode = settingIconWifiStandardMode.coerceIn(0, 3)
            standardMap = WifiStandardPolicy.parseMap(settingIconWifiStandardMap)
            paddingEnabled = settingIconWifiPadding
            paddingStart = safeFloat(settingIconWifiPaddingStartVal)
            paddingEnd = safeFloat(settingIconWifiPaddingEndVal)
            activityRight = settingIconWifiActivityRight
            restoreVmGates()
            vmStates.snapshot().forEach { (_, state) -> state.publish?.invoke() }
            boundRoots.keys.toList().forEach(::applyBinderLayout)
            DebugLog.i(TAG, "Wi-Fi settings refreshed hideActivity=$hideActivity hideConnected=$hideUnavailable " +
                "standardMode=$standardMode padding=$paddingEnabled activityRight=$activityRight roots=${boundRoots.size}")
        }
    }

    /** Records each root the native binder lays out and applies the current padding/placement. */
    private fun hookBinderLayout() {
        val binderClass = BINDER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BINDER_CLASS, "class not found")
            return
        }
        val bind = binderClass.findMethodOrNull { name("bind"); paramCount(2) } ?: run {
            DebugLog.hookSkipped(TAG, "$BINDER_CLASS#bind", "method not found")
            return
        }
        deoptimize(bind)
        bind.hook {
            after { param ->
                if (retired) return@after
                val root = param.args.getOrNull(0) as? ViewGroup ?: return@after
                boundRoots[root] = Unit
                applyBinderLayout(root)
            }
        }
        binderClass.findMethodOrNull {
            name("access\$bind\$updateWifiStandardLayoutParams"); paramCount(2)
        }?.let { method ->
            deoptimize(method)
            method.hook { after { param ->
                if (retired || !activityRight || hideActivity) return@after
                runCatching { (param.args.getOrNull(0) as? TextView)?.let(::placeStandardLeft) }
                    .onFailure { DebugLog.w(TAG, "Wi-Fi standard placement failed", it) }
            } }
        }
        // AOT may inline the getter into the native collector. Match its verified fields before
        // deoptimizing the caller so freshly rebound hosts consume the replacement Flow.
        runCatching {
            val collector = "$BINDER_CLASS\$bind\$1\$1\$6".toClassOrNull()
            if (collector?.declaredFields?.any { it.type.name.endsWith(".LocationBasedWifiViewModel") } == true &&
                collector.declaredFields.any { ImageView::class.java.isAssignableFrom(it.type) }) {
                collector.findMethodOrNull { name("invokeSuspend"); paramCount(1) }?.let(::deoptimize)
            }
        }.onFailure { DebugLog.w(TAG, "Wi-Fi activity collector deoptimization failed", it) }
    }

    /** Applies, or restores, the padding and accessory placement of one bound Wi-Fi root. */
    @SuppressLint("RtlHardcoded") // Match the native inoutLeft collector's absolute LEFT/RIGHT.
    private fun applyBinderLayout(root: ViewGroup) {
        runCatching {
            val groupId = ResourceLookup.identifier(root.resources, "wifi_group", "id", "com.android.systemui")
            val group = (if (groupId != 0) root.findViewById<ViewGroup>(groupId) else null) ?: root
            if (paddingEnabled) {
                originalPadding.getOrPut(group) {
                    intArrayOf(group.paddingStart, group.paddingTop, group.paddingEnd, group.paddingBottom)
                }
                group.setPaddingRelative(dpToPx(root, paddingStart), group.paddingTop,
                    dpToPx(root, paddingEnd), group.paddingBottom)
            } else {
                originalPadding.remove(group)?.let { group.setPaddingRelative(it[0], it[1], it[2], it[3]) }
            }
        }.onFailure { DebugLog.w(TAG, "Wi-Fi padding update failed", it) }
        runCatching {
            val standardId = ResourceLookup.identifier(root.resources, "wifi_standard", "id", "com.android.systemui")
            val activityId = ResourceLookup.identifier(root.resources, "wifi_activity", "id", "com.android.systemui")
            val standard = if (standardId != 0) root.findViewById<View>(standardId) as? TextView else null
            val activity = if (activityId != 0) root.findViewById<View>(activityId) else null
            val params = activity?.layoutParams as? FrameLayout.LayoutParams
            if (activityRight && !hideActivity) {
                standard?.let(::placeStandardLeft)
                if (activity != null && params != null) {
                    activityGravity.putIfAbsent(activity, params.gravity)
                    params.gravity = Gravity.RIGHT or Gravity.BOTTOM
                    activity.layoutParams = params
                }
            } else {
                standard?.let(::restoreStandard)
                val original = activity?.let(activityGravity::remove)
                if (activity != null && params != null && original != null) {
                    params.gravity = original
                    activity.layoutParams = params
                }
            }
        }.onFailure { DebugLog.w(TAG, "Wi-Fi accessory placement failed", it) }
    }

    private fun restoreStandard(view: TextView) {
        val original = standardGravity.remove(view) ?: return
        (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.gravity = original
            view.layoutParams = params
        }
    }

    /** `inoutLeft=false` is the verified host value for placing the activity indicator right. */
    private fun hookActivityDirection() {
        val vmClass = VM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
            return
        }
        vmClass.findMethodOrNull { name("getInoutLeft"); noParams() }?.let { getter ->
            deoptimize(getter)
            val returnType = getter.returnType
            getter.hook {
                after { param ->
                    if (param.throwable != null) return@after
                    // `inoutLeft=false` places the arrows right; the gate forces it while selected.
                    val state = vmStates.getOrPut(param.thisObject) { VmState() }
                    val gate = state.directionGate ?: LiveFlowGate("Wi-Fi arrows right", activityRight && !hideActivity).also { state.directionGate = it }
                    gate.set(activityRight && !hideActivity)
                    val base = state.restoredDirection ?: param.result ?: return@after
                    param.result = gate.wrap(base, returnType) { false }
                        ?: if (gate.hidden) IconTunerFlows.falseFlow else base
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#getInoutLeft", "method not found")
    }

    @SuppressLint("RtlHardcoded") // Keep the label opposite the native absolute-right arrow.
    private fun placeStandardLeft(view: TextView) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        standardGravity.putIfAbsent(view, params.gravity)
        val desired = Gravity.LEFT or Gravity.BOTTOM
        if (params.gravity != desired) {
            params.gravity = desired
            view.layoutParams = params
        }
    }

    internal fun recoverViewModel(component: Any) {
        HostAccess.onMain {
            recoveryTask?.let(recoveryHandler::removeCallbacks)
            recoveryTask = null
            recoverDirection(component, 0)
        }
    }

    private fun retryDirection(component: Any, attempt: Int) {
        if (retired || attempt >= 3) {
            if (!retired) DebugLog.w(TAG, "Wi-Fi direction recovery incomplete after bounded retries")
            return
        }
        val owner = WeakReference(component)
        val token = lifecycleEpoch
        val task = Runnable {
            recoveryTask = null
            if (!retired && lifecycleEpoch == token) owner.get()?.let { recoverDirection(it, attempt + 1) }
        }
        recoveryTask = task
        recoveryHandler.postDelayed(task, 250L shl attempt)
    }

    private fun recoverDirection(component: Any, attempt: Int) {
        if (retired) return
        val vm = HostAccess.provider(component, "wifiViewModelProvider")
        val interactor = HostAccess.provider(component, "wifiInteractorImplProvider")
        val scope = HostAccess.provider(component, "bgApplicationScopeProvider")
        if (vm == null || interactor == null || scope == null) {
            retryDirection(component, attempt)
            return
        }
        if (authority?.get() == null && networkSource.isBoundTo(interactor)) authority = WeakReference(vm)
        bindWifiOverrides(vm, interactor, scope)
        recoverVmDirection(vm, interactor, scope)
        // An earlier generation may have observed more than the component provider's VM.
        // Reattach each surviving VM to its own native inputs and retained replacement flows.
        vmStates.snapshot().forEach { (other, state) ->
            if (other !== vm) {
                val source = state.interactor?.get()
                val ownerScope = state.scope?.get()
                if (source != null && ownerScope != null) {
                    bindWifiOverrides(other, source, ownerScope)
                    recoverVmDirection(other, source, ownerScope)
                }
            }
        }
    }

    private fun recoverVmDirection(vm: Any, interactor: Any, scope: Any) {
        val state = vmStates.getOrPut(vm) { VmState() }
        val network = readField(interactor, "wifiNetwork") ?: return
        val standard = state.standard ?: readField(vm, "wifiStandard") ?: return
        val mutable = state.restoredDirection ?: IconTunerFlows.createMutableStateFlow(false) ?: return
        val token = ++state.directionAttempt
        var metered = false
        var standardValue = (IconTunerFlows.readFlowValue(standard) as? Number)?.toInt() ?: 0
        fun publish() = IconTunerFlows.setFlowValue(mutable, WifiActivityPolicy.nativeInoutLeft(metered, standardValue))
        fun updateNetwork(value: Any?) {
            metered = readField(readField(value, "ext"), "meteredHint") as? Boolean ?: false
            publish()
        }
        updateNetwork(if (networkSource.isBoundTo(interactor)) IconTunerFlows.readFlowValue(network) else null)
        val handle = HostFlowCollector.collect(scope, standard, consumer = { value ->
            standardValue = (value as? Number)?.toInt() ?: 0; publish()
        }, isCurrent = { token == state.directionEpoch && !retired }) ?: run {
            DebugLog.w(TAG, "Wi-Fi direction inputs unavailable; retaining previous subscription")
            return
        }
        state.directionEpoch = token
        state.directionHandle?.cancel()
        state.directionHandle = handle
        state.restoredDirection = mutable
        state.updateDirection = { value -> if (token == state.directionEpoch) updateNetwork(value) }
        standardValue = (IconTunerFlows.readFlowValue(standard) as? Number)?.toInt() ?: 0
        updateNetwork(if (networkSource.isBoundTo(interactor)) IconTunerFlows.readFlowValue(network) else null)
    }

    internal fun nativeGlyph(root: View, signalId: Int): View? {
        if (signalId == 0) return null
        val signal = root.findViewById<View>(signalId) ?: return null
        val groupId = ResourceLookup.identifier(root.resources, "wifi_group", "id", root.context.packageName)
        var group = signal.parent as? View
        while (group != null && group !== root) {
            if (group.id == groupId) return group
            group = group.parent as? View
        }
        return signal
    }

    private fun dpToPx(group: ViewGroup, value: Float): Int =
        (value * group.resources.displayMetrics.density).roundToInt().coerceIn(-2048, 2048)

    private fun safeFloat(value: Float): Float =
        value.takeIf(Float::isFinite)?.coerceIn(-48f, 48f) ?: 0f

    private fun readField(target: Any?, name: String): Any? =
        target?.let { runCatching { HostAccess.read(it, name) }.getOrNull() }

    private fun readInt(target: Any?, name: String): Int? =
        (readField(target, name) as? Number)?.toInt()

}
