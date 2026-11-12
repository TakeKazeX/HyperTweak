@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.cachedDeclaredMethod
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.rules.systemui.icon.EndpointKind
import com.takekazex.hypertweak.hook.rules.systemui.icon.FrameUpdateGate
import com.takekazex.hypertweak.hook.rules.systemui.icon.HandoverCoordinator
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeTypographySettings
import com.takekazex.hypertweak.hook.rules.systemui.icon.NetworkHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.ShadeSwitchMotionPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.TwoRowIconLayout
import com.takekazex.hypertweak.hook.rules.systemui.icon.WifiPipelineAdapter
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.presentation.icon.RendererId
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.settings.contract.SignalStyle
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * Compact control-center rows. Settings custom carrier names precede SIM subscription names,
 * with native text as the final fallback. The shared mobile reducer owns connectivity and masks.
 */
@SuppressLint("StaticFieldLeak")
internal object CarrierRenderer : IconRenderer() {
    override val hostCallbackOrder = 40
    // Published on settings acceptance; hook callbacks perform no storage reads.
    internal val networkVisibility by hookSetting(NetworkIconVisibility()) { IconTunerOptions.networkVisibility() }

    internal val settingCcHideDate by hookSetting(false) { Preferences.iconConfig().boolean(SystemUiSettingsKeys.KEY_CC_HIDE_DATE) }
    internal val settingCcCarrierTwoLine by hookSetting(false) { Preferences.iconConfig().boolean(SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE) }
    internal val settingCcNetworkFirstRow by hookSetting(false) {
        Preferences.iconConfig().boolean(SystemUiSettingsKeys.KEY_CC_NETWORK_FIRST_ROW)
    }
    internal val settingIconCellularTypeSmall5ga by hookSetting(false) {
        Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA)
    }
    internal val settingIconMobileRowStyle by hookSetting(0) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE) }
    internal val stackedSignal by hookSetting(false) { Preferences.iconConfig().signalStyle == SignalStyle.STACKED }
    internal val duoSignal by hookSetting(false) { Preferences.iconConfig().signalStyle == SignalStyle.DUO }
    internal val settingCcCarrierShowNonDataType by hookSetting(false) {
        Preferences.iconConfig().boolean(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE)
    }
    internal val hideTypeWhenDisconnected by hookSetting(false) {
        Preferences.cellularTypeHidesWhenDisconnected()
    }
    internal val settingCellularTypeKeepsOnWifi by hookSetting(false) { Preferences.cellularTypeKeepsOnWifi() }
    internal val settingCcCarrierShowBadge by hookSetting(true) {
        Preferences.iconConfig().boolean(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_BADGE)
    }
    internal val settingCcCarrierBadgeOne by hookSetting("1") { Preferences.iconConfig().string(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_ONE) }
    internal val settingCcCarrierBadgeTwo by hookSetting("2") { Preferences.iconConfig().string(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_TWO) }

    init {
        hostRecovery(HostRecoveryPhase.PRESENTATION, icons = true) {
            recoverExistingViews(it.context, it.views, it.progress, it.visible)
        }
    }

    internal const val TAG = "IconTuner"
    internal const val LAYOUT_CLASS = "com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout"
    internal const val ROW_CLASS = "com.android.systemui.controlcenter.shade.ControlCenterCarrierText"
    internal const val COMBINED_HEADER_CLASS =
        "com.android.systemui.controlcenter.shade.CombinedHeaderController"
    internal const val STATUS_ICON_CONTAINER_CLASS =
        "com.android.systemui.statusbar.views.MiuiStatusIconContainer"
    internal const val PANEL_MOTION_CACHE_KEY = "carrierBlockPanelMotionOwner"
    internal const val PANEL_MOTION_OWNER_MARKER =
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController\$controlCenterCallback"

    /** Fixed appearance, matching the stacked-signal slot: no user-facing knobs. */

    internal val handover = CarrierHandoverController(object : CarrierHandoverController.Host {
        override val enabled get() = CarrierRenderer.enabled
        override val statusBarId get() = CarrierRenderer.statusBarId
        override val fakeStatusBarId get() = CarrierRenderer.fakeStatusBarId
        override val wifiSignalId get() = CarrierRenderer.wifiSignalId
        override val mobileSignalId get() = CarrierRenderer.mobileSignalId
        override val mobileTypeId get() = CarrierRenderer.mobileTypeId
        override val activeDataSubId get() = mobileState.activeDataSubId
        override val mobileGroupId get() = CarrierRenderer.mobileGroupId
        override fun blocks(): Collection<CarrierBlock> = blocks.values
        override fun rowFor(view: View) = rowIndex[view.parent]
        override fun findStatusContainer(root: ViewGroup) = CarrierRenderer.findStatusContainer(root)
    })
    internal val rowRenderer = CarrierRowRenderer()
    internal val presentation = CarrierPresentation(object : CarrierPresentation.Host {
        override val carrierTextField get() = CarrierRenderer.carrierTextField
        override val badgeTexts get() = CarrierRenderer.badgeTexts
        override val enabled get() = CarrierRenderer.enabled
        override val rowOrder get() = CarrierRenderer.rowOrder
        override fun rowGeometry(layout: ViewGroup) = CarrierRenderer.rowGeometry(layout)
        override fun releaseMask(block: CarrierBlock) = CarrierRenderer.releaseMask(block)
        override fun releaseHandover() = handover.releaseHandover()
    }, rowRenderer)

    internal val main = Handler(Looper.getMainLooper())
    internal val generation = AtomicLong(0L)
    internal val installed = generationResource(AtomicBoolean(false)) { it.set(false) }

    /** Carrier rows supply their live host typography; these values are fallback only. */
    internal var typeConfig by generationState { MobileTypeConfig(
        textSizeSp = 13f,
        paddingStartSp = 1f,
        paddingEndSp = 1f
    ) }

    internal val blocks = generationMainResource(WeakHashMap<Any, CarrierBlock>()) { it.clear() }
    internal val rowIndex = generationMainResource(WeakHashMap<Any, CarrierRowParts>()) { it.clear() }
    internal val maskedContainers = generationMainResource(WeakHashMap<Any, CarrierMask>()) { it.clear() }
    internal var enabled by generationState { false }
    internal var showNonDataType by generationState { false }
    internal var keepTypeOnWifi by generationState { false }
    internal val shadeSwitching get() = PanelHub.state.shadeSwitching

    internal fun isShadeSwitching(): Boolean = shadeSwitching
    internal var networkOnFirstRow by generationState { false }
    internal var dataFirst by generationState { false }
    internal var rowOrder by generationState { listOf(0, 1) }
    internal var showBadge by generationState { true }
    internal var badgeTexts by generationState { listOf("1", "2") }
    internal var hostContext: Context? by generationState { null }
    internal var subscriptionManager: SubscriptionManager? by generationState { null }
    internal var subscriptionListener: SubscriptionManager.OnSubscriptionsChangedListener? by generationState { null }
    internal var simNames: Map<Int, String> by generationState { emptyMap() }
    internal val knownLayouts = generationMainResource(WeakHashMap<ViewGroup, Boolean>()) { it.clear() }
    internal var recoveryTask: Runnable? by generationState { null }
    internal var recoveryAttempt by generationState { 0 }
    internal var lastRecoveryEvidence: CarrierRecoveryEvidence? by generationState { null }


    internal var carrierLayoutId by generationState { 0 }
    internal var fakeStatusBarId by generationState { 0 }
    internal var statusBarId by generationState { 0 }
    internal var wifiSignalId by generationState { 0 }
    internal var mobileSignalId by generationState { 0 }
    internal var mobileTypeId by generationState { 0 }
    internal var mobileGroupId by generationState { 0 }

    internal var leftTextField: Field? by generationState { null }
    internal var rightTextField: Field? by generationState { null }
    internal var carrierTextField: Field? by generationState { null }
    internal var separatorField: Field? by generationState { null }
    internal var keyguardSeparatorField: Field? by generationState { null }

    /** All mutable view state is consumed on the main looper. */
    internal val mobileState: MobileSignalState get() = MobileSignalHub.state
    internal var slotIndices by generationState { emptyMap<Int, Int>() }
    internal val wifiLevel: Int? get() = NetworkHub.state.wifiLevel
    internal val wifiDefault: Boolean? get() = NetworkHub.state.wifiDefault
    internal val progress get() = PanelHub.state.progress
    internal val panelVisible get() = PanelHub.state.visible

    fun onPackageReady(context: Context) {
        HostAccess.onMain { bindEnvironment(context) }
    }

    override fun saveHotReloadState(): Any = onMainBlocking {
        listOf((knownLayouts.keys + blocks.keys.filterIsInstance<ViewGroup>()).distinct(),
            null, null, progress, panelVisible, hostContext)
    }

    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? List<*> ?: return
        HostAccess.onMain {
            if (!enabled) return@onMain
            val views = (saved.getOrNull(0) as? List<*>)?.filterIsInstance<View>().orEmpty()
            val context = saved.getOrNull(5) as? Context ?: hostContext ?: views.firstOrNull()?.context
            if (context == null) {
                DebugLog.w(TAG, "carrier snapshot has no host environment; awaiting explicit recovery")
                return@onMain
            }
            recoverExistingViews(context, views, saved.getOrNull(3) as? Float, saved.getOrNull(4) as? Boolean)
        }
    }

    override fun onPrepareHotReload() {
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)

        // Finish host restoration before the lifecycle removes this generation's hooks.
        onMainBlocking {
            subscriptionListener?.let { listener ->
                runCatching { subscriptionManager?.removeOnSubscriptionsChangedListener(listener) }
                    .onFailure { DebugLog.w(TAG, "SIM name listener cleanup failed", it) }
            }
            subscriptionListener = null
            subscriptionManager = null
            simNames = emptyMap()
            Choreographer.getInstance().removeFrameCallback(renderFrame)
            renderGate.cancel()
            handover.retire()
            maskedContainers.keys.toList().forEach { container ->
                runCatching { IconHostPorts.positions.carrierMask(container, CarrierMask()) }
            }
            maskedContainers.clear()
            blocks.values.toList().forEach({ removeBlock(it) })
            blocks.clear()
            rowRenderer.clear()
            knownLayouts.clear()
            hostContext = null
            lastRecoveryEvidence = null
            rowIndex.clear()
        }
        installed.set(false)
    }

    override fun onHook() {
        networkOnFirstRow = settingCcNetworkFirstRow
        IconTunerFlows.init(classLoader)
        typeConfig = typeConfig.copy(
            typography = MobileTypeTypographySettings.current,
            small5GaEnabled = settingIconCellularTypeSmall5ga
        )
        dataFirst = settingIconMobileRowStyle == 1
        val hideDate = settingCcHideDate
        val twoLine = settingCcCarrierTwoLine
        showNonDataType = settingCcCarrierShowNonDataType
        keepTypeOnWifi = settingCellularTypeKeepsOnWifi
        showBadge = settingCcCarrierShowBadge
        badgeTexts = listOf(
            settingCcCarrierBadgeOne,
            settingCcCarrierBadgeTwo
        ).mapIndexed(CarrierBlockPolicy::badgeText)
        // 开关 2 is the enabling switch for 开关 3/4: without the hidden date the second row would
        // overlap the date, and the settings page disables the dependent rows for the same reason.
        enabled = hideDate && twoLine
        if (!enabled) {
            DebugLog.hookSkippedDebug(TAG, "CarrierBlock", "disabled")
            return
        }
        if (!installed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        resolveIds()
        hookLayout()
        HandoverCoordinator.register(
            this, RendererId.CARRIER,
            target = { container, kind -> when (kind) {
                is EndpointKind.Signal -> signalHandoverTarget(container, kind.subId)
                is EndpointKind.Type -> duoHandoverTarget(container, false, kind.subId)
                EndpointKind.Wifi -> duoHandoverTarget(container, true, null)
                else -> null
            } },
            claims = { container, kind -> ownsNetwork(container, kind == EndpointKind.Wifi) },
            acquire = ::acquireDuoTarget, release = ::releaseDuoTarget
        )
        MobileSignalHub.subscribe(this, ::onMobileState)
        hookRowMaxWidth()
        PanelHub.subscribe(this, changed = {
            if (shadeSwitching) handover.releaseHandover()
            else handover.applyHandoverGuarded(if (panelVisible) progress else 0f)
        })
        NetworkHub.subscribe(this, ::onMobileState)
        hostContext?.let { context -> HostAccess.onMain { bindEnvironment(context) } }
        DebugLog.hookRegistered(TAG, "control-center two-line carrier block")
    }

    // ---------------------------------------------------------------- settings / ids

    internal fun resolveIds(context: Context? = hostContext) {
        context ?: return
        val resources = context.resources
        carrierLayoutId = id(resources, "normal_control_center_carrier_layout")
        fakeStatusBarId = id(resources, "normal_fake_control_center_status_bar")
        statusBarId = id(resources, "normal_control_center_status_bar")
        wifiSignalId = id(resources, "wifi_signal")
        mobileSignalId = id(resources, "mobile_signal")
        mobileTypeId = id(resources, "mobile_type")
        mobileGroupId = id(resources, "mobile_group")
    }

    internal fun id(resources: Resources, name: String): Int =
        runCatching { ResourceLookup.identifier(resources, name, "id", "com.android.systemui") }
            .getOrDefault(0)

    // ---------------------------------------------------------------- hooks

    internal fun hookLayout() {
        val layoutClass = LAYOUT_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, LAYOUT_CLASS, "class not found")
            return
        }
        val rowClass = ROW_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ROW_CLASS, "class not found")
            return
        }
        leftTextField = hierarchyField(layoutClass, "leftCarrierTextView")
        rightTextField = hierarchyField(layoutClass, "rightCarrierTextView")
        separatorField = hierarchyField(layoutClass, "carrierSeparatorView")
        keyguardSeparatorField = hierarchyField(layoutClass, "carrierSeparatorText")
        carrierTextField = hierarchyField(rowClass, "carrierTextView")
        if (leftTextField == null || rightTextField == null || carrierTextField == null) {
            DebugLog.hookSkipped(TAG, "$LAYOUT_CLASS fields", "carrier fields not found")
            return
        }
        if (layoutClass.findMethodOrNull { name("onMeasure"); paramCount(2) } == null ||
            layoutClass.findMethodOrNull { name("setCarrierMaxWidth"); paramCount(2) } == null ||
            rowClass.findMethodOrNull { name("setMaxWidth"); paramCount(1) } == null) {
            DebugLog.hookSkipped(TAG, "CarrierBlock", "measurement boundary unavailable")
            return
        }
        layoutClass.findMethodOrNull { name("onAttachedToWindow"); noParams() }?.let { method ->
            method.observe { after { param ->
                val layout = param.thisObject as? ViewGroup ?: return@after
                if (enabled) runCatching { installBlock(layout) }
                    .onFailure { DebugLog.w(TAG, "carrier attach failed", it) }
            } }
        }
        layoutClass.observeConstructors {
            after { param ->
                val layout = param.thisObject as? ViewGroup ?: return@after
                if (!enabled) return@after
                runCatching { installBlock(layout) }
                    .onFailure { DebugLog.w(TAG, "carrier block install failed", it) }
            }
        }
        layoutClass.findMethodOrNull { name("onMeasure"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.observe {
                before { param ->
                    val block = blocks[param.thisObject] ?: return@before
                    runCatching {
                        presentation.configureBlock(block)
                        if (block.compact) {
                            val width = View.MeasureSpec.getSize(param.args[0] as Int)
                            presentation.measureRows(block, width)
                        }
                    }.onFailure { DebugLog.w(TAG, "carrier row measurement failed", it) }
                }
                after { param ->
                    val block = blocks[param.thisObject] ?: return@after
                    if (block.compact) {
                        block.separator?.visibility = View.GONE
                        block.keyguardSeparator?.visibility = View.GONE
                    }
                }
            }
        }
        // The horizontal host budget schedules a delayed setMaxWidth. Neither that budget nor
        // its delayed write may overwrite the full-width vertical budget for an owned row.
        layoutClass.findMethodOrNull { name("setCarrierMaxWidth"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.observe { before { param ->
                if (ownsLayout(param.thisObject as? ViewGroup)) param.result = null
            } }
        }
        prepareDexTargets(name = CarrierTextCallbackFeature.id,
            resolve = ::resolveCarrierTextCallback, install = ::installCarrierTextCallback)
        // Native text notifications are deduplicated. Clearing an override equal to the SPN
        // still needs a repaint, because our fallback is the SIM name rather than that SPN.
        val customNameChanged = runCatching {
            hierarchyField(rowClass, "carrierTextController")?.type
                ?.cachedDeclaredMethod("onCustomCarrierChanged", Array<String>::class.java, accessible = true)
                ?.takeIf { it.returnType == Void.TYPE }?.apply { isAccessible = true }
        }.getOrNull()
        customNameChanged?.let { method ->
            deoptimize(method)
            method.observe { after {
                runCatching { scheduleRender() }
                    .onFailure { DebugLog.w(TAG, "custom carrier refresh failed", it) }
            } }
        } ?: DebugLog.hookSkipped(TAG, "custom carrier refresh", "controller callback unavailable")
        listOf("onMiuiThemeChanged", "onConfigChanged").forEach { methodName ->
            layoutClass.findMethodOrNull { name(methodName); paramCount(1) }?.let { method ->
                method.observe { after { param ->
                    val block = blocks[param.thisObject] ?: return@after
                    runCatching { presentation.configureBlock(block, restyle = true); scheduleRender() }
                        .onFailure { DebugLog.w(TAG, "carrier configuration update failed", it) }
                } }
            }
        }
    }

    internal fun hookRowMaxWidth() {
        val rowClass = ROW_CLASS.toClassOrNull() ?: run { DebugLog.hookSkipped("CarrierRenderer", "$ROW_CLASS", "not found on host"); return }
        rowClass.findMethodOrNull { name("setMaxWidth"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.observe { before { param ->
                if (ownsRow(param.thisObject as? ViewGroup)) param.result = null
            } }
        }
        rowClass.findMethodOrNull { name("onConfigurationChanged"); paramCount(1) }?.let { method ->
            method.observe { after { param ->
                val row = param.thisObject as? ViewGroup ?: return@after
                val block = blocks[row.parent] ?: return@after
                runCatching { presentation.configureBlock(block, restyle = true); scheduleRender() }
                    .onFailure { DebugLog.w(TAG, "carrier row restyle failed", it) }
            } }
        }
        rowClass.findMethodOrNull { name("updateHDText"); paramCount(2) }?.let { method ->
            method.observe { after { param ->
                if (ownsRow(param.thisObject as? ViewGroup)) runCatching {
                    (readField(param.thisObject, "hdText") as? View)?.visibility = View.GONE
                    (readField(param.thisObject, "plusText") as? View)?.visibility = View.GONE
                }
            } }
        }
    }

    // ---------------------------------------------------------------- state

    /** Hears every settled [MobileSignalHub] change, and the current state once on install. */
    internal fun onMobileState() {
        if (!enabled) return
        scheduleRender()
        main.post { handover.applyHandoverGuarded(progress) }
    }

    // ---------------------------------------------------------------- view installation

    fun ownsLayout(layout: ViewGroup?): Boolean = enabled && blocks[layout]?.compact == true

    fun ownsRow(row: ViewGroup?): Boolean = row != null && rowIndex.containsKey(row) &&
        ownsLayout(row.parent as? ViewGroup)

    internal fun rowGeometry(layout: ViewGroup): TwoRowIconLayout.Geometry? {
        val block = blocks[layout]?.takeIf { it.compact } ?: return null
        val inputs = block.geometryInputs
        inputs.begin()
        inputs.add(rowRenderer.iconHeightPx(layout.context))
        inputs.add((3 * layout.resources.displayMetrics.density).roundToInt())
        inputs.add(layout.paddingTop)
        inputs.add(layout.paddingBottom)
        inputs.add(mobileState.subscriptionOrder.size)
        for (index in block.rows.indices) {
            val parts = block.rows[index]
            inputs.add(if (parts.row.isVisible) 1 else 0)
            inputs.add(parts.row.measuredHeight)
            inputs.add(parts.networkSlot.layoutParams.height.coerceAtLeast(0))
        }
        if (block.geometry == null || inputs.changed) {
            val measured = block.rows.filter { it.row.isVisible }.map { it.row.measuredHeight }
            val rowHeight = maxOf(inputs[0],
                block.rows.maxOfOrNull { it.networkSlot.layoutParams.height.coerceAtLeast(0) } ?: 0)
            block.geometry = TwoRowIconLayout.geometry(measured, rowHeight, inputs[1], inputs[2], inputs[3],
                rowCount = mobileState.subscriptionOrder.size.takeIf { it > 0 } ?: measured.size)
        }
        return block.geometry
    }

    fun firstRowCenter(layout: ViewGroup): Int = rowGeometry(layout)?.firstCenter?.roundToInt() ?: 0

    /** Duo asks about this exact expanded container, never a global signal preference. */
    fun ownsNetworkContainer(container: Any): Boolean = maskedContainers[container]?.active == true

    fun ownsNetwork(container: Any, wifi: Boolean): Boolean = maskedContainers[container]?.let {
        if (wifi) it.wifi else it.cellular
    } == true

    /** Only expose a measured replacement in this expanded header, never another SIM or window. */
    fun duoHandoverTarget(container: Any, wifi: Boolean, subId: Int?): View? = blocks.values
        .firstOrNull { it.maskContainer === container && it.compact && it.layout.isShown }
        ?.rows?.firstNotNullOfOrNull { parts ->
            val target = if (wifi && parts.wifiReady) parts.wifi else if (!wifi &&
                parts.cellularReady && !parts.typeSuppressed && parts.typeBitmap != null && subId != null &&
                parts.model?.networkSubId == subId) parts.type else null
            target?.takeIf { handover.isUsable(it) && it.drawable != null && parts.row.isShown }
        }

    fun signalHandoverTarget(container: Any, subId: Int): View? = blocks.values
        .firstOrNull { it.maskContainer === container && it.compact && it.layout.isShown }
        ?.rows?.firstOrNull { it.model?.subId == subId && it.cellularReady }
        ?.signal?.takeIf { handover.isUsable(it) && it.drawable != null }

    /** Alpha has one writer during a Duo hand-over; ordinary masks must not reset it each frame. */
    fun acquireDuoTarget(target: View, owner: Any): Boolean = handover.acquireDuoTarget(target, owner)
    fun releaseDuoTarget(target: View, owner: Any) = handover.releaseDuoTarget(target, owner)

    // ---------------------------------------------------------------- rendering

    internal val renderGate = FrameUpdateGate()
    internal var renderTicket by generationState { 0L }
    internal val renderFrame = Choreographer.FrameCallback {
        renderGate.drain(renderTicket) {
            if (enabled) runCatching { renderAll() }
                .onFailure { DebugLog.w(TAG, "carrier frame render failed", it) }
        }
    }

    internal fun scheduleRender() {
        if (!enabled) return
        val ticket = renderGate.request() ?: return
        val token = generation.get()
        main.post {
            if (enabled && generation.get() == token && renderGate.isPending(ticket)) {
                renderTicket = ticket
                Choreographer.getInstance().postFrameCallback(renderFrame)
            }
        }
    }

    // ---------------------------------------------------------------- shared helpers

    internal fun slotOf(subId: Int): Int = runCatching {
        SubscriptionManager.getSlotIndex(subId)
    }.getOrDefault(CarrierBlockPolicy.INVALID_SLOT)

    /**
     * Cached resolution of the carrier-row callback.
     *
     * The installed hook needs one method, so the result is a descriptor. It used to be rescanned
     * on the SystemUI main thread on every start (577-991 ms, measured 2026-10-24), which lands
     * inside `bindApplication`.
     */
    internal fun resolveCarrierTextCallback(): Method? = DexRuntime.resolve(
        feature = CarrierTextCallbackFeature,
        apkPath = hookParam.appInfo?.sourceDir ?: return null,
        cacheDir = dexCacheDirectory,
        cacheEmpty = true,
        appInfo = hookParam.appInfo,
        compute = CarrierTextCallbackCache::descriptors,
        decode = { stored, _ -> CarrierTextCallbackCache.decode(stored, classLoader) },
    )

    internal fun installCarrierTextCallback(method: Method) {
            deoptimize(method)
            method.observe { after { param ->
                runCatching {
                    val row = readOuter(param.thisObject, ROW_CLASS) as? ViewGroup ?: return@runCatching
                    if (!ownsRow(row)) return@runCatching
                    rowIndex[row]?.let { parts ->
                        parts.nativeCarrierText = parts.carrierText.text
                        parts.carrierText.visibility = if (parts.carrierText.text.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                    scheduleRender()
                }.onFailure { DebugLog.w(TAG, "carrier name update failed", it) }
            } }
        scheduleRender()
    }

    internal fun readOuter(target: Any, ownerClassName: String): Any? = runCatching {
        val ownerClass = classLoader.loadClass(ownerClassName)
        val fields = mutableListOf<Field>()
        var current: Class<*>? = target.javaClass
        while (current != null) {
            fields += current.declaredFields.filter { ownerClass.isAssignableFrom(it.type) }
            current = current.superclass
        }
        fields.singleOrNull()?.apply { isAccessible = true }?.get(target)
    }.getOrNull()

    internal fun readField(target: Any, name: String): Any? = runCatching {
        field(target.javaClass, name)?.get(target)
    }.getOrNull()

    internal fun readInt(target: Any?, name: String): Int? = runCatching {
        val owner = target ?: return@runCatching null
        val resolved = field(owner.javaClass, name) ?: return@runCatching null
        val value = if (resolved.type == Int::class.javaPrimitiveType) {
            resolved.getInt(owner)
        } else {
            resolved.get(owner)
        }
        (value as? Number)?.toInt()
    }.getOrNull()

    /** Cached hierarchy lookup: the hand-over path reads a mobile group's `subId` every frame. */
    internal fun field(clazz: Class<*>, name: String): Field? = HostAccess.field(clazz, name)

    internal fun hierarchyField(clazz: Class<*>, name: String): Field? = field(clazz, name)

    internal fun <T> onMainBlocking(action: () -> T): T = HostAccess.onMain(action)
}
