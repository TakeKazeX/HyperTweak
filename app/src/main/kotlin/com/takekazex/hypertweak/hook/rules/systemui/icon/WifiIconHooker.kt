package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.orSkip
import com.takekazex.hypertweak.hook.base.cachedPublicField
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy

import com.takekazex.hypertweak.hook.base.HostAccess

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlin.math.roundToInt

/**
 * Wi-Fi visibility and standard-label options for the existing MIUI Wi-Fi pipeline.
 *
 * Live icon and standard overrides are exposed through the native binder getters. Their original
 * producers continue to run so a settings change can rebind without waiting for a network event.
 * Padding is applied after the verified `MiuiWifiViewBinder.bind` layout setup and is limited to
 * the host's `wifi_group` view.
 */
object WifiIconHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconHideWifiActivity by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_WIFI_ACTIVITY, false)
    }
    private val settingIconHideWifiUnavailable by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false)
    }
    private val settingIconWifiStandardMode by hookSetting(0) {
        val key = IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE
        IconSettingsPreferencePolicy.wifiStandardMode(
            if (Preferences.contains(key)) Preferences.getInt(key, 0) else null,
            Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_WIFI_TYPE, false)
        )
    }
    private val settingIconWifiStandardMap by hookSetting("4,5,6,7,8") {
        Preferences.getString(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MAP, "4,5,6,7,8")
    }
    private val settingIconWifiPadding by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_WIFI_PADDING, false) }
    private val settingIconWifiPaddingStartVal by hookSetting(0f) {
        Preferences.getFloat(IconSettingsKeys.KEY_ICON_WIFI_PADDING_START_VAL, 0f)
    }
    private val settingIconWifiPaddingEndVal by hookSetting(0f) {
        Preferences.getFloat(IconSettingsKeys.KEY_ICON_WIFI_PADDING_END_VAL, 0f)
    }
    private val settingIconWifiActivityRight by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_WIFI_ACTIVITY_RIGHT, false)
    }

    init {
        hostRecovery(HostRecoveryPhase.SOURCES, icons = true) {
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
    private val wifiOverride = generationMainResource(AtomicReference<WifiOverride?>()) {
        it.getAndSet(null)?.subscriptions?.clear()
    }
    private data class WifiOverride(
        val vm: Any, val icon: Any?, val standard: Any?, val subscriptions: HostFlowSubscriptions
    )

    private var restoredDirection: Any? by generationState { null }
    private val standardGravity = generationMainResource(WeakHashMap<TextView, Int>()) { it.clear() }
    private val directionHandles = generationMainResource(ArrayList<HostFlowCollector.Handle>()) { it.clear() }
    @Volatile
    private var directionEpoch = 0L
    private var directionAttempt by generationState { 0L }
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var recoveryTask: Runnable? by generationState { null }
    private var retired = false
    init { generationCleanup { retired = true } }

    override fun onPrepareHotReload() {
        HostAccess.onMain {
            retired = true
            standardGravity.forEach { (view, original) ->
                (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                    params.gravity = original
                    view.layoutParams = params
                }
            }
            standardGravity.clear()
            recoveryTask?.let(recoveryHandler::removeCallbacks)
            recoveryTask = null
            directionEpoch = ++directionAttempt
            directionHandles.forEach { it.cancel() }
            directionHandles.clear()
            restoredDirection = null
            wifiOverride.getAndSet(null)?.subscriptions?.clear()
        }

        standardMap = WifiStandardPolicy.DEFAULT_MAP
    }

    override fun onHook() {
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
        hookActivityDirection()
        hookAccessoryLayout()
        if (hideUnavailable || standardMode in 2..3) hookWifiOverrides()

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
        if (hideActivity || standardMode == 1) hookVisibilityGetters()
        if (paddingEnabled) hookPadding()
        DebugLog.hookRegistered(
            TAG,
            "WifiIcon: standardMode=$standardMode padding=$paddingEnabled activityRight=$activityRight"
        )
    }

    private fun hookWifiOverrides() {
        val vmClass = VM_CLASS.toClassOrNull() ?: run { DebugLog.hookSkipped("WifiIconHooker", "$VM_CLASS", "not found on host"); return }
        fun hookGetter(name: String, returnType: String, select: (WifiOverride) -> Any?) {
            val getter = HostAccess.method(vmClass, name) ?: run { DebugLog.hookSkipped("WifiIconHooker", "HostAccess.method(vmClass, name)", "not found on host"); return }
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
                    wifiOverride.get()?.takeIf { it.vm === param.thisObject }?.let(select)?.let { param.result = it }
                }
            }
        }
        if (hideUnavailable) hookGetter("getWifiIcon", "StateFlow") { it.icon }
        if (standardMode in 2..3) hookGetter("getWifiStandard", "Flow") { it.standard }
        vmClass.declaredConstructors.singleOrNull { constructor ->
            constructor.parameterCount == 6 &&
                constructor.parameterTypes[3].name.endsWith(".WifiInteractorImpl") &&
                constructor.parameterTypes[4].name == IconTunerFlows.hostClassName("kotlinx.coroutines", "CoroutineScope")
        }?.hook {
            after { param ->
                val interactor = param.args.getOrNull(3) ?: return@after
                val scope = param.args.getOrNull(4) ?: return@after
                val vm = param.thisObject
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    bindWifiOverrides(vm, interactor, scope)
                } else {
                    recoveryHandler.post { bindWifiOverrides(vm, interactor, scope) }
                }
            }
        }
    }

    private fun bindWifiOverrides(vm: Any, interactor: Any, scope: Any) {
        if (retired || (!hideUnavailable && standardMode !in 2..3) || wifiOverride.get()?.vm === vm) return
        val subscriptions = HostFlowSubscriptions()
        runCatching {
            val iconFlow = readField(vm, "wifiIcon") ?: return
            val standardFlow = readField(vm, "wifiStandard") ?: return
            val networkFlow = readField(interactor, "wifiNetwork") ?: return
            val activeClass = ACTIVE_CLASS.toClass()
            val hidden = HIDDEN_CLASS.toClass().cachedPublicField("INSTANCE", accessible = false).get(null)
            var nativeIcon = IconTunerFlows.readFlowValue(iconFlow) ?: hidden
            var nativeStandard = (IconTunerFlows.readFlowValue(standardFlow) as? Number)?.toInt() ?: 0
            var network = IconTunerFlows.readFlowValue(networkFlow)
            val icon = if (hideUnavailable) checkNotNull(IconTunerFlows.createMutableStateFlow(nativeIcon)) else null
            val standard = if (standardMode in 2..3) checkNotNull(IconTunerFlows.createMutableStateFlow(nativeStandard)) else null
            fun publish() {
                val active = activeClass.isInstance(network)
                icon?.let { IconTunerFlows.setFlowValue(it, if (active) hidden else nativeIcon) }
                standard?.let {
                    val raw = if (active) readInt(readField(network, "ext"), "wifiStandard") else null
                    val mapped = WifiStandardPolicy.resolve(standardMode, raw, nativeStandard, standardMap)
                    IconTunerFlows.setFlowValue(it, mapped)
                }
            }
            val bound = subscriptions.collect(scope, networkFlow, { !retired }) { value ->
                network = value
                publish()
            } && (icon == null || subscriptions.collect(scope, iconFlow, { !retired }) { value ->
                nativeIcon = value ?: hidden
                publish()
            }) && (standard == null || subscriptions.collect(scope, standardFlow, { !retired }) { value ->
                nativeStandard = (value as? Number)?.toInt() ?: 0
                publish()
            })
            if (bound) {
                publish()
                wifiOverride.getAndSet(WifiOverride(vm, icon, standard, subscriptions))?.subscriptions?.clear()
                DebugLog.i(TAG, "native Wi-Fi flows rebound hideConnected=${icon != null} standardMode=$standardMode")
            } else {
                subscriptions.clear()
                DebugLog.hookSkipped(TAG, "Wi-Fi live overrides", "native Flow subscription unavailable")
            }
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
        val getters = buildList {
            if (hideActivity) add("getActivityInOutRes")
            if (standardMode == 1) add("getWifiStandard")
        }
        getters.forEach { getter ->
            vmClass.findMethodOrNull { name(getter); noParams() }.orSkip("WifiIconHooker", "vmClass")?.hook {
                before { param -> param.result = IconTunerFlows.zeroFlow }
            } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#$getter", "method not found")
        }
    }

    private fun hookPadding() {
        val binderClass = BINDER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BINDER_CLASS, "class not found")
            return
        }
        val bind = binderClass.findMethodOrNull { name("bind"); paramCount(2) } ?: run {
            DebugLog.hookSkipped(TAG, "$BINDER_CLASS#bind", "method not found")
            return
        }
        bind.hook {
            after { param ->
                runCatching {
                    val root = param.args.getOrNull(0) as? ViewGroup ?: return@runCatching
                    val groupId = root.resources.getIdentifier(
                        "wifi_group",
                        "id",
                        "com.android.systemui"
                    )
                    val group = if (groupId != 0) root.findViewById<ViewGroup>(groupId) else null
                    (group ?: root).setPaddingRelative(
                        dpToPx(root, paddingStart),
                        (group ?: root).paddingTop,
                        dpToPx(root, paddingEnd),
                        (group ?: root).paddingBottom
                    )
                }.onFailure { DebugLog.w(TAG, "Wi-Fi padding update failed", it) }
            }
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
            getter.hook {
                before { param ->
                    if (activityRight && !hideActivity) param.result = IconTunerFlows.falseFlow
                    else restoredDirection?.let { param.result = it }
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#getInoutLeft", "method not found")
    }

    /** The native XML anchors both accessories right; forcing arrows right must move the label. */
    @SuppressLint("RtlHardcoded") // Match the native inoutLeft collector's absolute LEFT/RIGHT.
    private fun hookAccessoryLayout() {
        if (!activityRight || hideActivity) return
        val binderClass = BINDER_CLASS.toClassOrNull() ?: run { DebugLog.hookSkipped("WifiIconHooker", "$BINDER_CLASS", "not found on host"); return }
        binderClass.findMethodOrNull { name("bind"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.hook { after { param ->
                if (retired || !activityRight || hideActivity) return@after
                runCatching {
                    val root = param.args.getOrNull(0) as? ViewGroup ?: return@runCatching
                    val standardId = root.resources.getIdentifier("wifi_standard", "id", "com.android.systemui")
                    val activityId = root.resources.getIdentifier("wifi_activity", "id", "com.android.systemui")
                    val standard = if (standardId != 0) root.findViewById<android.view.View>(standardId) as? TextView else null
                    standard?.let(::placeStandardLeft)
                    val activity = if (activityId != 0) root.findViewById<android.view.View>(activityId) else null
                    (activity?.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                        params.gravity = Gravity.RIGHT or Gravity.BOTTOM
                        activity.layoutParams = params
                    }
                }.onFailure { DebugLog.w(TAG, "Wi-Fi accessory placement failed", it) }
            } }
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
                collector.declaredFields.any { android.widget.ImageView::class.java.isAssignableFrom(it.type) }) {
                collector.findMethodOrNull { name("invokeSuspend"); paramCount(1) }?.let(::deoptimize)
            }
        }.onFailure { DebugLog.w(TAG, "Wi-Fi activity collector deoptimization failed", it) }
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
        val task = Runnable {
            recoveryTask = null
            if (!retired) owner.get()?.let { recoverDirection(it, attempt + 1) }
        }
        recoveryTask = task
        recoveryHandler.postDelayed(task, 250L shl attempt)
    }

    private fun recoverDirection(component: Any, attempt: Int) {
        if (retired) return
        val vm = HostAccess.provider(component, "wifiViewModelProvider")
        val interactor = HostAccess.provider(component, "wifiInteractorImplProvider")
        val scope = HostAccess.provider(component, "bgApplicationScopeProvider")
        if (vm != null && interactor != null && scope != null) bindWifiOverrides(vm, interactor, scope)
        val network = interactor?.let { HostAccess.read(it, "wifiNetwork") }
        val standard = wifiOverride.get()?.takeIf { it.vm === vm }?.standard
            ?: vm?.let { HostAccess.read(it, "wifiStandard") }
        if (vm == null || network == null || standard == null) {
            retryDirection(component, attempt)
            return
        }
        // Older module builds overwrote inoutLeft. Recompute the native boolean contract from
        // its real inputs; optimized SuspendLambda classes may have no declared constructor.
        // Keep the host flow type and native binder; do not change VM fields.
        // A MutableStateFlow implements the native getter's Flow contract directly.
        // Do not unwrap an optional ReadonlyStateFlow: optimized hosts may return the mutable itself.
        // Reuse the published mutable across retries: native binders may already collect it.
        val mutable = restoredDirection ?: IconTunerFlows.createMutableStateFlow(false)
            ?: run {
                retryDirection(component, attempt)
                return
            }
        val exposed = mutable
        val token = ++directionAttempt
        var metered = false
        var standardValue = (IconTunerFlows.readFlowValue(standard) as? Number)?.toInt() ?: 0
        fun publish() {
            val value = WifiActivityPolicy.nativeInoutLeft(metered, standardValue)
            IconTunerFlows.setFlowValue(mutable, value)
        }
        fun updateNetwork(value: Any?) {
            val ext = readField(value, "ext")
            metered = readField(ext, "meteredHint") as? Boolean ?: false
            publish()
        }
        updateNetwork(IconTunerFlows.readFlowValue(network))
        if (restoredDirection == null) restoredDirection = exposed
        if (scope == null) {
            retryDirection(component, attempt)
            return
        }
        val handles = listOf(
            HostFlowCollector.collect(scope, network, consumer = ::updateNetwork, isCurrent = { token == directionEpoch }),
            HostFlowCollector.collect(scope, standard, consumer = { value ->
                standardValue = (value as? Number)?.toInt() ?: 0; publish()
            }, isCurrent = { token == directionEpoch })
        )
        if (handles.any { it == null }) {
            handles.filterNotNull().forEach { it.cancel() }
            // Preserve a working subscription set. On the first recovery publish the native
            // input snapshot so an older delegate-less getter cannot abort all host recovery.
            if (restoredDirection == null) restoredDirection = exposed
            DebugLog.w(TAG, "Wi-Fi direction inputs unavailable; retaining native snapshot/previous subscriptions")
            retryDirection(component, attempt)
            return
        }
        directionEpoch = token
        directionHandles.forEach { it.cancel() }
        directionHandles.clear()
        // Re-read after binding: emissions during the tentative transaction were ignored.
        standardValue = (IconTunerFlows.readFlowValue(standard) as? Number)?.toInt() ?: 0
        updateNetwork(IconTunerFlows.readFlowValue(network))
        directionHandles.addAll(handles.filterNotNull())
        restoredDirection = exposed
    }

    internal fun nativeGlyph(root: View, signalId: Int): View? {
        if (signalId == 0) return null
        val signal = root.findViewById<View>(signalId) ?: return null
        val groupId = root.resources.getIdentifier("wifi_group", "id", root.context.packageName)
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
