package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.cachedPublicField
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.dex.DexRuntime

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.util.WeakHashMap
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File
import kotlin.math.roundToInt

/**
 * Wi-Fi visibility and standard-label options for the existing MIUI Wi-Fi pipeline.
 *
 * Standard mapping is applied to the host's concrete combine transform, so the original Flow
 * object remains intact (the binder and AOD consumers continue to receive the expected type).
 * Padding is applied after the verified `MiuiWifiViewBinder.bind` layout setup and is limited to
 * the host's `wifi_group` view.
 */
object WifiIconHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconHideWifiActivity by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_WIFI_ACTIVITY, false)
    }
    private val settingIconHideWifiType by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_WIFI_TYPE, false) }
    private val settingIconHideWifiUnavailable by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false)
    }
    private val settingIconWifiStandardMode by hookSetting(0) { Preferences.getInt(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE, 0) }
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
    private const val STANDARD_TRANSFORM_CACHE_KEY = "wifiStandardTransform"
    private const val STANDARD_TRANSFORM_OWNER_MARKER = "WifiViewModelInject"
    private const val WIFI_ACTIVE_CLASS =
        "com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel\$Active"

    private var hideActivity by generationState { false }
    private var hideType by generationState { false }
    private var hideUnavailable by generationState { false }
    private var standardMode by generationState { 0 }
    private var standardMap by generationState { WifiStandardPolicy.DEFAULT_MAP }
    private var paddingEnabled by generationState { false }
    private var paddingStart by generationState { 0f }
    private var paddingEnd by generationState { 0f }
    private var activityRight by generationState { false }
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
        }

        standardMap = WifiStandardPolicy.DEFAULT_MAP
    }

    override fun onHook() {
        retired = false
        IconTunerFlows.init(classLoader)
        hideActivity = settingIconHideWifiActivity
        hideType = settingIconHideWifiType
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
        if (!hideActivity && !hideType && !hideUnavailable && standardMode == 0 &&
            !paddingEnabled && !activityRight
        ) {
            DebugLog.hookSkippedDebug(TAG, "WifiIcon", "no icon tuner wifi switches enabled")
            return
        }

        // 1. Substitute a connected Wi-Fi model with the Hidden icon model. This preserves the
        // host's unavailable/no-internet branches and only hides an Active model.
        if (hideUnavailable) {
            val companionClass = "$WIFI_ICON_CLASS\$Companion".toClassOrNull()
            val activeClass = ACTIVE_CLASS.toClassOrNull()
            val hiddenInstance = runCatching {
                HIDDEN_CLASS.toClass().cachedPublicField("INSTANCE", accessible = false).get(null)
            }.getOrNull()
            val fromModel = companionClass?.findMethodOrNull { name("fromModel") }
            if (fromModel == null || activeClass == null || hiddenInstance == null) {
                DebugLog.hookSkipped(
                    TAG,
                    "$WIFI_ICON_CLASS\$Companion#fromModel",
                    "method/class/INSTANCE not found"
                )
            } else {
                fromModel.hook {
                    before { param ->
                        val model = param.args.getOrNull(0)
                        val showUnavailable = param.args.getOrNull(4)
                        val noFlag = showUnavailable !is Boolean || !showUnavailable
                        if (activeClass.isInstance(model) && noFlag) param.result = hiddenInstance
                    }
                }
            }
        }

        // 2. These are stable getter boundaries used by MiuiWifiViewBinder. A field write would be
        // lost because the factory assigns the flows after construction.
        if (hideActivity || hideType || standardMode == 1) hookVisibilityGetters()
        if (standardMode in 2..3) {
            val activeClass = ACTIVE_CLASS.toClassOrNull()
            if (activeClass == null) {
                DebugLog.hookSkipped(TAG, "Wi-Fi standard transform", "active model class not found")
            } else {
                hookStandardTransform(activeClass)
            }
        }
        if (paddingEnabled) hookPadding()
        DebugLog.hookRegistered(
            TAG,
            "WifiIcon: standardMode=$standardMode padding=$paddingEnabled activityRight=$activityRight"
        )
    }

    private fun hookVisibilityGetters() {
        val vmClass = VM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
            return
        }
        val getters = buildList {
            if (hideActivity) add("getActivityInOutRes")
            if (hideType || standardMode == 1) add("getWifiStandard")
        }
        getters.forEach { getter ->
            vmClass.findMethodOrNull { name(getter); noParams() }?.hook {
                before { param -> param.result = IconTunerFlows.zeroFlow }
            } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#$getter", "method not found")
        }
    }

    /** Changes only the integer emitted by the host's combine transform; the concrete Flow stays. */
    private fun hookStandardTransform(activeClass: Class<*>) {
        val transformClass = resolveStandardTransformClass() ?: run {
            DebugLog.hookSkipped(TAG, "Wi-Fi standard transform", "unique DexKit target not found")
            return
        }
        val invoke = transformClass.declaredMethods.singleOrNull {
            it.name == "invoke" && it.returnType == Any::class.java
        }?.apply { isAccessible = true } ?: run {
            DebugLog.hookSkipped(TAG, "Wi-Fi standard transform", "unique invoke method not found")
            return
        }
        deoptimize(invoke)
        invoke.hook {
            after { param ->
                runCatching {
                    val original = (param.result as? Number)?.toInt() ?: return@runCatching
                    val raw = rawStandard(param.args, activeClass)
                    param.result = WifiStandardPolicy.resolve(standardMode, raw, original, standardMap)
                }.onFailure { DebugLog.w(TAG, "Wi-Fi standard mapping failed", it) }
            }
        }
    }

    private fun resolveStandardTransformClass(): Class<*>? {
        val appInfo = hookParam.appInfo ?: return null
        val apkPath = appInfo.sourceDir ?: return null
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return null
        return DexRuntime.resolveClasses(
            cacheDir = File(baseDir, "cache"),
            apkPath = apkPath,
            classLoader = classLoader,
            queries = mapOf(
                STANDARD_TRANSFORM_CACHE_KEY to { bridge ->
                    val matches = bridge.findMethod {
                        matcher {
                            declaredClass {
                                className(STANDARD_TRANSFORM_OWNER_MARKER, StringMatchType.Contains)
                            }
                            name("invokeSuspend")
                            returnType(Any::class.java)
                            usingFields {
                                add {
                                    declaredClass(WIFI_ACTIVE_CLASS)
                                    name("ext")
                                }
                            }
                        }
                    }.toList()
                    matches.map { it.className }.distinct().singleOrNull()
                }
            ),
            validators = mapOf(
                STANDARD_TRANSFORM_CACHE_KEY to { type ->
                    type.name.contains(STANDARD_TRANSFORM_OWNER_MARKER) &&
                        type.declaredMethods.any { it.name == "invokeSuspend" && it.returnType == Any::class.java } &&
                        type.declaredMethods.count { it.name == "invoke" && it.returnType == Any::class.java } == 1
                }
            )
        )[STANDARD_TRANSFORM_CACHE_KEY]
    }

    /** Input ordering and FunctionN arity vary; select the network model by its host type. */
    private fun rawStandard(args: Array<Any?>, activeClass: Class<*>): Int? {
        val network = sequence {
            args.forEach { value ->
                if (activeClass.isInstance(value)) yield(value)
                (value as? Array<*>)?.forEach { nested ->
                    if (activeClass.isInstance(nested)) yield(nested)
                }
            }
        }.singleOrNull() ?: return null
        val ext = readField(network, "ext")
        readInt(ext, "wifiStandard")?.let { return it }
        return readInt(network, "wifiStandard")
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
        val binderClass = BINDER_CLASS.toClassOrNull() ?: return
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
        val network = interactor?.let { HostAccess.read(it, "wifiNetwork") }
        val standard = vm?.let { HostAccess.read(it, "wifiStandard") }
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

    private fun dpToPx(group: ViewGroup, value: Float): Int =
        (value * group.resources.displayMetrics.density).roundToInt().coerceIn(-2048, 2048)

    private fun safeFloat(value: Float): Float =
        value.takeIf(Float::isFinite)?.coerceIn(-48f, 48f) ?: 0f

    private fun readField(target: Any?, name: String): Any? =
        target?.let { runCatching { HostAccess.read(it, name) }.getOrNull() }

    private fun readInt(target: Any?, name: String): Int? =
        (readField(target, name) as? Number)?.toInt()

}
