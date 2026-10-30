@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import com.takekazex.hypertweak.hook.base.cachedDeclaredMethod
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.FrameUpdateGate
import com.takekazex.hypertweak.hook.rules.systemui.icon.HostFlowSubscriptions
import com.takekazex.hypertweak.hook.rules.systemui.icon.HostIconBridge
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.ShadeSwitchMotionPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.SignalHandoverEvents
import com.takekazex.hypertweak.hook.rules.systemui.icon.TwoRowIconLayout
import com.takekazex.hypertweak.presentation.icon.IconSvgRepository
import com.takekazex.hypertweak.presentation.icon.MobileSignalState

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
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.SignalSvgStyle
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * Compact control-center rows. Settings custom carrier names precede SIM subscription names,
 * with native text as the final fallback. The shared mobile reducer owns connectivity and masks.
 */
@SuppressLint("StaticFieldLeak")
object ControlCenterCarrierBlockHooker : StaticHooker() {
    init { generationCleanup { assetExecutor.shutdownNow() } }
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingCcHideDate by hookSetting(false) { Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_HIDE_DATE, false) }
    private val settingCcCarrierTwoLine by hookSetting(false) { Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE, false) }
    private val settingCcNetworkFirstRow by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_NETWORK_FIRST_ROW, false)
    }
    private val settingIconCellularTypeSmall5ga by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA, false)
    }
    private val settingIconMobileRowStyle by hookSetting(0) { Preferences.getInt(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE, 0) }
    private val settingCcCarrierShowNonDataType by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE, false)
    }
    private val settingCellularTypeKeepsOnWifi by hookSetting(false) { Preferences.cellularTypeKeepsOnWifi() }
    private val settingCcCarrierShowBadge by hookSetting(true) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_BADGE, true)
    }
    private val settingCcCarrierBadgeOne by hookSetting("1") { Preferences.getString(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_ONE, "1") }
    private val settingCcCarrierBadgeTwo by hookSetting("2") { Preferences.getString(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_TWO, "2") }

    init {
        hostRecovery(HostRecoveryPhase.PRESENTATION, icons = true) {
            recoverExistingViews(it.context, it.views, it.progress, it.visible)
        }
        hostRecovery(HostRecoveryPhase.NETWORK, icons = true) {
            if (it.coroutineScope != null && it.wifiInteractor != null) recoverWifi(it.coroutineScope!!, it.wifiInteractor!!, it.context)
        }
    }

    private const val TAG = "IconTuner"
    private const val LAYOUT_CLASS = "com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout"
    private const val ROW_CLASS = "com.android.systemui.controlcenter.shade.ControlCenterCarrierText"
    private const val COMBINED_HEADER_CLASS =
        "com.android.systemui.controlcenter.shade.CombinedHeaderController"
    private const val STATUS_ICON_CONTAINER_CLASS =
        "com.android.systemui.statusbar.views.MiuiStatusIconContainer"
    private const val WIFI_VM_CLASS =
        "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel"
    private const val PANEL_MOTION_CACHE_KEY = "carrierBlockPanelMotionOwner"
    private const val PANEL_MOTION_OWNER_MARKER =
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController\$controlCenterCallback"

    /** Fixed appearance, matching the stacked-signal slot: no user-facing knobs. */

    private val handover = CarrierHandoverController(object : CarrierHandoverController.Host {
        override val enabled get() = ControlCenterCarrierBlockHooker.enabled
        override val artworkReady get() = artwork != null
        override val statusBarId get() = ControlCenterCarrierBlockHooker.statusBarId
        override val fakeStatusBarId get() = ControlCenterCarrierBlockHooker.fakeStatusBarId
        override val wifiSignalId get() = ControlCenterCarrierBlockHooker.wifiSignalId
        override val mobileSignalId get() = ControlCenterCarrierBlockHooker.mobileSignalId
        override val mobileTypeId get() = ControlCenterCarrierBlockHooker.mobileTypeId
        override val mobileGroupId get() = ControlCenterCarrierBlockHooker.mobileGroupId
        override fun blocks(): Collection<CarrierBlock> = blocks.values
        override fun rowFor(view: View) = rowIndex[view.parent]
        override fun findStatusContainer(root: ViewGroup) = ControlCenterCarrierBlockHooker.findStatusContainer(root)
    })
    private val rowRenderer = CarrierRowRenderer()
    private val presentation = CarrierPresentation(object : CarrierPresentation.Host {
        override val carrierTextField get() = ControlCenterCarrierBlockHooker.carrierTextField
        override val badgeTexts get() = ControlCenterCarrierBlockHooker.badgeTexts
        override val enabled get() = ControlCenterCarrierBlockHooker.enabled
        override val rowOrder get() = ControlCenterCarrierBlockHooker.rowOrder
        override fun rowGeometry(layout: ViewGroup) = ControlCenterCarrierBlockHooker.rowGeometry(layout)
        override fun releaseMask(block: CarrierBlock) = ControlCenterCarrierBlockHooker.releaseMask(block)
        override fun releaseHandover() = handover.releaseHandover()
    }, rowRenderer)

    private val main = Handler(Looper.getMainLooper())
    private fun newAssetExecutor() = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HyperTweak-CarrierBlockAssets").apply { isDaemon = true }
    }
    private var assetExecutor = newAssetExecutor()
    private val generation = AtomicLong(0L)
    private val installed = generationResource(AtomicBoolean(false)) { it.set(false) }
    private val artLock = Any()

    /** Use the regular network-type size, not the former compact 11sp variant. */
    private var typeConfig by generationState { MobileTypeConfig(
        textSizeSp = 14f,
        weight = 630,
        singleWeight = 400,
        paddingStartSp = 1f,
        paddingEndSp = 1f
    ) }

    private val blocks = generationMainResource(WeakHashMap<Any, CarrierBlock>()) { it.clear() }
    private val rowIndex = generationMainResource(WeakHashMap<Any, CarrierRowParts>()) { it.clear() }
    private val maskedContainers = generationMainResource(WeakHashMap<Any, CarrierMask>()) { it.clear() }
    private val wifiSubscriptions = HostFlowSubscriptions()
    private var enabled by generationState { false }
    private var showNonDataType by generationState { false }
    private var keepTypeOnWifi by generationState { false }
    private var shadeSwitching by generationState { false }
    private var hostShadeSwitching by generationState { false }
    private var shadeSwitchProgress by generationState { 0f }

    internal fun isShadeSwitching(): Boolean = shadeSwitching
    private var networkOnFirstRow by generationState { false }
    private var dataFirst by generationState { false }
    private var rowOrder by generationState { listOf(0, 1) }
    private var showBadge by generationState { true }
    private var badgeTexts by generationState { listOf("1", "2") }
    private var hostContext: Context? by generationState { null }
    private var subscriptionManager: SubscriptionManager? by generationState { null }
    private var subscriptionListener: SubscriptionManager.OnSubscriptionsChangedListener? by generationState { null }
    private var simNames: Map<Int, String> by generationState { emptyMap() }
    private var svgRepository: IconSvgRepository? by generationState { null }
    private var artwork: CarrierArtwork? by generationState { null }
    @Volatile
    private var artGeneration = Long.MIN_VALUE
    @Volatile
    private var artInFlight = Long.MIN_VALUE

    private val knownLayouts = generationMainResource(WeakHashMap<ViewGroup, Boolean>()) { it.clear() }
    private var recoveryTask: Runnable? by generationState { null }
    private var recoveryAttempt by generationState { 0 }
    private var lastRecoveryEvidence: CarrierRecoveryEvidence? by generationState { null }

    private var wifiScope: Any? by generationState { null }
    private var wifiInteractor: Any? by generationState { null }

    private var carrierLayoutId by generationState { 0 }
    private var fakeStatusBarId by generationState { 0 }
    private var statusBarId by generationState { 0 }
    private var wifiSignalId by generationState { 0 }
    private var mobileGroupId by generationState { 0 }
    private var mobileTypeId by generationState { 0 }
    private var mobileSignalId by generationState { 0 }

    private var leftTextField: Field? by generationState { null }
    private var rightTextField: Field? by generationState { null }
    private var carrierTextField: Field? by generationState { null }
    private var separatorField: Field? by generationState { null }
    private var keyguardSeparatorField: Field? by generationState { null }

    /** All mutable view state is consumed on the main looper. */
    private var mobileState by generationState { MobileSignalState() }
    private var wifiLevel: Int? by generationState { null }
    private var progress by generationState { 0f }
    private var panelVisible by generationState { false }

    fun onPackageReady(context: Context) {
        HostAccess.onMain { bindEnvironment(context) }
    }

    private fun bindEnvironment(context: Context) {
        val app = context.applicationContext ?: context
        hostContext = app
        resolveIds(context)
        if (svgRepository == null) {
            val moduleContext = runCatching {
                context.createPackageContext(HostIconBridge.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            }.onFailure { DebugLog.w(TAG, "carrier module resources unavailable", it) }.getOrNull()
            svgRepository = moduleContext?.let(::IconSvgRepository)
        }
        if (enabled) {
            bindSimNames(app)
            scheduleArtworkLoad(generation.get())
        }
    }

    /** Subscription display names include Settings SIM renames; carrier/SPN text does not. */
    // Runs with SystemUI's manifest-granted READ_PHONE_STATE, not the module app identity.
    @SuppressLint("MissingPermission")
    private fun bindSimNames(context: Context) {
        if (!enabled || subscriptionListener != null) return
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val token = generation.get()
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                if (!enabled || generation.get() != token) return
                runCatching {
                    simNames = manager.activeSubscriptionInfoList.orEmpty().mapNotNull { info ->
                        info.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                            ?.let { info.subscriptionId to it }
                    }.toMap()
                    scheduleRender()
                }.onFailure {
                    simNames = emptyMap()
                    scheduleRender()
                    DebugLog.w(TAG, "SIM name refresh failed; retaining native names", it)
                }
            }
        }
        runCatching {
            manager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
            subscriptionManager = manager
            subscriptionListener = listener
            listener.onSubscriptionsChanged()
        }.onFailure { DebugLog.w(TAG, "SIM name listener unavailable", it) }
    }

    /**
     * The shared reducer only needs to run while this feature, Duo or 堆叠 (signal) is on.
     *
     * Read straight from the preferences rather than from [enabled]: `HookEntry` attaches the
     * hookers in registration order and [StackedSignalHooker] decides whether to build the shared
     * pipeline before this object's `onHook()` has run.
     */
    val requiresMobileState: Boolean
        get() = enabled ||
            (settingCcHideDate &&
                settingCcCarrierTwoLine)

    override fun saveHotReloadState(): Any = onMainBlocking {
        listOf((knownLayouts.keys + blocks.keys.filterIsInstance<ViewGroup>()).distinct(),
            wifiScope, wifiInteractor, progress, panelVisible, hostContext)
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
            val scope = saved.getOrNull(1)
            val interactor = saved.getOrNull(2)
            if (scope != null && interactor != null) bindWifi(scope, interactor, context)
        }
    }

    internal fun recoverExistingViews(context: Context, views: List<View>, savedProgress: Float?, visible: Boolean?) {
        if (!enabled) return
        check(Looper.myLooper() == Looper.getMainLooper())
        // Context/resources must be ready BEFORE accepting a saved or discovered host.
        bindEnvironment(context)
        savedProgress?.let { progress = it.coerceIn(0f, 1f) }
        visible?.let { panelVisible = it }
        views.filter { it.javaClass.name == LAYOUT_CLASS }.filterIsInstance<ViewGroup>()
            .forEach { knownLayouts[it] = true }
        recoveryTask?.let(main::removeCallbacks)
        recoveryAttempt = 0
        lastRecoveryEvidence = null
        val token = generation.get()
        val task = Runnable {
            runCatching { recoverHosts(token) }.onFailure { error ->
                DebugLog.w(TAG, "carrier recovery attempt=$recoveryAttempt failed", error)
                if (enabled && generation.get() == token && recoveryAttempt < CarrierRecoveryPolicy.MAX_ATTEMPTS) {
                    recoveryTask?.let { main.postDelayed(it, CarrierRecoveryPolicy.RETRY_DELAY_MS) }
                } else recoveryTask = null
            }
        }
        recoveryTask = task
        task.run()
    }

    /** Reconcile saved, attached-window and controller-owned (possibly detached) hosts. */
    private fun recoverHosts(token: Long) {
        if (!enabled || generation.get() != token) return
        val context = hostContext ?: return
        recoveryAttempt++
        bindEnvironment(context)
        val app = context.applicationContext ?: context
        val component = HostAccess.read(app, "mSysUIComponent")
            ?: HostAccess.read(app, "mInitializer")?.let { HostAccess.invoke(it, "getSysUIComponent") }
        if (component != null) {
            runCatching {
                val header = HostAccess.provider(component, "combinedHeaderControllerProvider")
                val layout = header?.let { HostAccess.read(it, "controlCenterCarrierLayout") } as? ViewGroup
                if (layout != null) knownLayouts[layout] = true
                val scope = HostAccess.provider(component, "bgApplicationScopeProvider")
                val interactor = HostAccess.provider(component, "wifiInteractorImplProvider")
                if (scope != null && interactor != null) bindWifi(scope, interactor, context)
            }.onFailure { DebugLog.w(TAG, "carrier host/source discovery failed", it) }
        }
        fun visit(view: View) {
            if (view.javaClass.name == LAYOUT_CLASS && view is ViewGroup) knownLayouts[view] = true
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        knownLayouts.keys.toList().forEach { layout ->
            runCatching { installBlock(layout) }.onFailure { DebugLog.w(TAG, "carrier recovered host rejected", it) }
        }
        scheduleRender()
        handover.applyHandoverGuarded(progress)
        val evidence = CarrierRecoveryEvidence(
            environmentReady = carrierLayoutId != 0 && svgRepository != null,
            hosts = blocks.size,
            rows = blocks.values.sumOf { it.rows.size },
            artworkReady = artwork != null,
            wifiBound = wifiSubscriptions.isBound,
            mobileBound = IconHostPorts.mobileReady()
        )
        if (evidence != lastRecoveryEvidence) {
            lastRecoveryEvidence = evidence
            DebugLog.i(TAG, "carrier recovery attempt=$recoveryAttempt hosts=${evidence.hosts} " +
                "attached=${blocks.keys.filterIsInstance<View>().count { it.isAttachedToWindow }} " +
                "rows=${evidence.rows} missing=${evidence.missing}")
        }
        when (CarrierRecoveryPolicy.decide(enabled && generation.get() == token, recoveryAttempt, evidence)) {
            CarrierRecoveryPolicy.Decision.PREPARED -> {
                recoveryTask = null
                DebugLog.i(TAG, "carrier recovery prepared hosts=${evidence.hosts} rows=${evidence.rows}")
            }
            CarrierRecoveryPolicy.Decision.RETRY -> recoveryTask?.let { main.postDelayed(it, CarrierRecoveryPolicy.RETRY_DELAY_MS) }
            CarrierRecoveryPolicy.Decision.EXHAUSTED -> {
                recoveryTask = null
                DebugLog.w(TAG, "carrier recovery incomplete after $recoveryAttempt attempts missing=${evidence.missing}")
            }
            CarrierRecoveryPolicy.Decision.RETIRED -> Unit
        }
    }

    internal fun recoverWifi(scope: Any, interactor: Any, context: Context) {
        if (enabled) bindWifi(scope, interactor, context)
    }

    override fun onPrepareHotReload() {
        val token = generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)

        assetExecutor.shutdownNow()

        wifiSubscriptions.clear()

        synchronized(artLock) {
            artGeneration = Long.MIN_VALUE
            artInFlight = Long.MIN_VALUE
        }
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
            blocks.values.toList().forEach(::removeBlock)
            blocks.clear()
            rowRenderer.clear()
            knownLayouts.clear()
            hostContext = null
            svgRepository = null
            lastRecoveryEvidence = null
            rowIndex.clear()
            mobileState = MobileSignalState()
            progress = 0f
            panelVisible = false
        }
        installed.set(false)
        synchronized(artLock) { if (generation.get() == token) artGeneration = Long.MIN_VALUE }
    }

    override fun onHook() {
        if (assetExecutor.isShutdown) assetExecutor = newAssetExecutor()
        networkOnFirstRow = settingCcNetworkFirstRow
        IconTunerFlows.init(classLoader)
        typeConfig = typeConfig.copy(
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
        hookRowMaxWidth()
        hookExpansion()
        hookShadeSwitching()
        hookWifi()
        hostContext?.let { context -> HostAccess.onMain { bindEnvironment(context) } }
        DebugLog.hookRegistered(TAG, "control-center two-line carrier block")
    }

    // ---------------------------------------------------------------- settings / ids

    private fun resolveIds(context: Context? = hostContext) {
        context ?: return
        val resources = context.resources
        carrierLayoutId = id(resources, "normal_control_center_carrier_layout")
        fakeStatusBarId = id(resources, "normal_fake_control_center_status_bar")
        statusBarId = id(resources, "normal_control_center_status_bar")
        wifiSignalId = id(resources, "wifi_signal")
        mobileGroupId = id(resources, "mobile_group")
        mobileTypeId = id(resources, "mobile_type")
        mobileSignalId = id(resources, "mobile_signal")
    }

    private fun id(resources: android.content.res.Resources, name: String): Int =
        runCatching { resources.getIdentifier(name, "id", "com.android.systemui") }
            .getOrDefault(0)

    // ---------------------------------------------------------------- hooks

    private fun hookLayout() {
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
            method.hook { after { param ->
                val layout = param.thisObject as? ViewGroup ?: return@after
                if (enabled) runCatching { installBlock(layout) }
                    .onFailure { DebugLog.w(TAG, "carrier attach failed", it) }
            } }
        }
        layoutClass.hookAllConstructors {
            after { param ->
                val layout = param.thisObject as? ViewGroup ?: return@after
                if (!enabled) return@after
                runCatching { installBlock(layout) }
                    .onFailure { DebugLog.w(TAG, "carrier block install failed", it) }
            }
        }
        layoutClass.findMethodOrNull { name("onMeasure"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.hook {
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
            method.hook { before { param ->
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
            method.hook { after {
                runCatching { scheduleRender() }
                    .onFailure { DebugLog.w(TAG, "custom carrier refresh failed", it) }
            } }
        } ?: DebugLog.hookSkipped(TAG, "custom carrier refresh", "controller callback unavailable")
        listOf("onMiuiThemeChanged", "onConfigChanged").forEach { methodName ->
            layoutClass.findMethodOrNull { name(methodName); paramCount(1) }?.let { method ->
                method.hook { after { param ->
                    val block = blocks[param.thisObject] ?: return@after
                    runCatching { presentation.configureBlock(block, restyle = true); scheduleRender() }
                        .onFailure { DebugLog.w(TAG, "carrier configuration update failed", it) }
                } }
            }
        }
    }

    private fun hookRowMaxWidth() {
        val rowClass = ROW_CLASS.toClassOrNull() ?: return
        rowClass.findMethodOrNull { name("setMaxWidth"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook { before { param ->
                if (ownsRow(param.thisObject as? ViewGroup)) param.result = null
            } }
        }
        rowClass.findMethodOrNull { name("onConfigurationChanged"); paramCount(1) }?.let { method ->
            method.hook { after { param ->
                val row = param.thisObject as? ViewGroup ?: return@after
                val block = blocks[row.parent] ?: return@after
                runCatching { presentation.configureBlock(block, restyle = true); scheduleRender() }
                    .onFailure { DebugLog.w(TAG, "carrier row restyle failed", it) }
            } }
        }
        rowClass.findMethodOrNull { name("updateHDText"); paramCount(2) }?.let { method ->
            method.hook { after { param ->
                if (ownsRow(param.thisObject as? ViewGroup)) runCatching {
                    (readField(param.thisObject, "hdText") as? View)?.visibility = View.GONE
                    (readField(param.thisObject, "plusText") as? View)?.visibility = View.GONE
                }
            } }
        }
    }

    private fun hookExpansion() {
        val source = resolvePanelMotionClass() ?: run {
            DebugLog.hookSkipped(TAG, "control-center panel motion", "unique DexKit owner not found")
            return
        }
        source.declaredMethods.singleOrNull(::isExpansionChangedMethod)?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    if (shadeSwitching) return@after
                    val value = (param.args.getOrNull(0) as? Number)?.toFloat() ?: return@after
                    if (value < 0f || value > 1f) return@after
                    onPanelProgress(value)
                }
            }
        }
        // Visibility, rather than progress alone, tells us whether the panel was dismissed: the
        // panel can rest at 0 while the finger is still down.
        source.declaredMethods.singleOrNull(::isVisibilityChangedMethod)?.let { method ->
            method.hook {
                after { param ->
                    if (shadeSwitching) return@after
                    val visible = param.args.getOrNull(0) as? Boolean ?: return@after
                    panelVisible = visible
                    if (!visible) progress = 0f
                    handover.applyHandoverGuarded(if (visible) progress else 0f)
                }
            }
        }
        source.declaredMethods.singleOrNull(::isAppearanceChangedMethod)?.let { method ->
            method.hook {
                after { param ->
                    if (shadeSwitching) return@after
                    if (param.args.getOrNull(0) == true) panelVisible = true
                    handover.applyHandoverGuarded(if (panelVisible) progress else 0f)
                }
            }
        }
    }

    /** Shade switching owns the header motion; a carrier hand-over must not run on that path. */
    private fun hookShadeSwitching() {
        val combined = COMBINED_HEADER_CLASS.toClassOrNull() ?: return
        combined.findMethodOrNull { name("onSwitchingChanged"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    hostShadeSwitching = param.args.getOrNull(0) as? Boolean ?: false
                    runCatching { refreshShadeSwitchState() }
                        .onFailure { DebugLog.w(TAG, "shade-switch state update failed", it) }
                }
            }
        }
        combined.findMethodOrNull { name("onSwitchProgressChanged"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    shadeSwitchProgress = (param.args.getOrNull(0) as? Number)?.toFloat()
                        ?.coerceIn(0f, 1f) ?: return@after
                    runCatching { refreshShadeSwitchState() }
                        .onFailure { DebugLog.w(TAG, "shade-switch progress update failed", it) }
                }
            }
        }
    }

    private fun refreshShadeSwitchState() {
        val next = ShadeSwitchMotionPolicy.isActive(hostShadeSwitching, shadeSwitchProgress)
        if (next == shadeSwitching) return
        shadeSwitching = next
        if (next) {
            runCatching { handover.releaseHandover() }
                .onFailure { DebugLog.w(TAG, "shade-switch carrier release failed", it) }
            val controlCenterVisible = ShadeSwitchMotionPolicy.controlCenterAtRest(shadeSwitchProgress)
            runCatching { IconHostPorts.left.shadeStarted(controlCenterVisible) }
                .onFailure { DebugLog.w(TAG, "shade-switch left handover failed", it) }
            runCatching { SignalHandoverEvents.shade.started() }
                .onFailure { DebugLog.w(TAG, "shade-switch Duo handover failed", it) }
        } else {
            val controlCenterVisible = ShadeSwitchMotionPolicy.controlCenterAtRest(shadeSwitchProgress)
            runCatching { IconHostPorts.left.shadeFinished(controlCenterVisible) }
                .onFailure { DebugLog.w(TAG, "shade-switch left settle failed", it) }
            runCatching { SignalHandoverEvents.shade.finished(controlCenterVisible) }
                .onFailure { DebugLog.w(TAG, "shade-switch Duo settle failed", it) }
        }
    }

    private fun resolvePanelMotionClass(): Class<*>? {
        val appInfo = hookParam.appInfo ?: return null
        val apkPath = appInfo.sourceDir ?: return null
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return null
        return DexRuntime.resolveClasses(
            cacheDir = File(baseDir, "cache"),
            apkPath = apkPath,
            classLoader = classLoader,
            queries = mapOf(
                PANEL_MOTION_CACHE_KEY to { bridge ->
                    bridge.findMethod {
                        matcher {
                            declaredClass(PANEL_MOTION_OWNER_MARKER, StringMatchType.Contains)
                            name("onExpansionChanged")
                            paramCount(1)
                            returnType(Void.TYPE)
                        }
                    }.toList().asSequence()
                        .map { it.className }
                        .distinct()
                        .filter { name ->
                            runCatching { isPanelMotionClass(classLoader.loadClass(name)) }
                                .getOrDefault(false)
                        }
                        .singleOrNull()
                }
            ),
            validators = mapOf(PANEL_MOTION_CACHE_KEY to ::isPanelMotionClass)
        )[PANEL_MOTION_CACHE_KEY]
    }

    private fun isPanelMotionClass(type: Class<*>): Boolean =
        type.name.contains(PANEL_MOTION_OWNER_MARKER) &&
            type.declaredMethods.any(::isExpansionChangedMethod) &&
            type.declaredMethods.any(::isVisibilityChangedMethod) &&
            type.declaredMethods.any(::isAppearanceChangedMethod)

    private fun isExpansionChangedMethod(method: Method): Boolean =
        method.name == "onExpansionChanged" &&
            method.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType)) &&
            method.returnType == Void.TYPE

    private fun isVisibilityChangedMethod(method: Method): Boolean =
        method.name == "onVisibleChanged" &&
            method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
            method.returnType == Void.TYPE

    private fun isAppearanceChangedMethod(method: Method): Boolean =
        method.name == "onAppearanceChanged" && method.parameterCount == 2

    /** Wi-Fi level for 卡一. The host VM constructor carries its own interactor and scope. */
    private fun hookWifi() {
        val vm = WIFI_VM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, WIFI_VM_CLASS, "class not found")
            return
        }
        vm.declaredConstructors.filter { ctor ->
            ctor.parameterCount == 6 &&
                ctor.parameterTypes[3].name.endsWith(".WifiInteractorImpl") &&
                ctor.parameterTypes[4].name ==
                IconTunerFlows.hostClassName("kotlinx.coroutines", "CoroutineScope")
        }.forEach { ctor ->
            ctor.hook {
                after { param ->
                    val interactor = param.args.getOrNull(3) ?: return@after
                    val scope = param.args.getOrNull(4) ?: return@after
                    val context = param.args.getOrNull(1) as? Context ?: return@after
                    val token = generation.get()
                    main.post {
                        if (enabled && generation.get() == token) {
                            runCatching { bindWifi(scope, interactor, context) }
                                .onFailure { DebugLog.w(TAG, "carrier wifi binding failed", it) }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- state

    /** Called by [StackedSignalHooker] after every complete reducer emission. */
    fun onMobileState(state: MobileSignalState, ready: Boolean) {
        if (!enabled) return
        // The host briefly rebinds its subscription flows while connectivity changes. Keep a
        // previously verified row until that rebind completes, as the stacked signal does.
        val next = when {
            ready || state.airplaneMode -> state
            state.subscriptionOrder.isNotEmpty() &&
                state.subscriptionOrder.toSet() == mobileState.subscriptionOrder.toSet() -> return
            else -> MobileSignalState()
        }
        if (mobileState == next) return
        mobileState = next
        scheduleRender()
        main.post { handover.applyHandoverGuarded(progress) }
    }

    private fun bindWifi(scope: Any, interactor: Any, context: Context) {
        if (wifiInteractor === interactor && wifiSubscriptions.isBound) return
        wifiSubscriptions.clear()
        wifiScope = scope
        wifiInteractor = interactor
        wifiLevel = null
        val token = generation.get()
        val wifiMax = runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java)?.maxSignalLevel ?: 4
        }.getOrDefault(4).coerceAtLeast(1)
        val flow = readField(interactor, "wifiNetwork") ?: return
        if (!wifiSubscriptions.collect(
            scope, flow, { enabled && generation.get() == token }, replay = true,
            consumer = { value -> reduceWifi(value, wifiMax, token) }
        )) DebugLog.w(TAG, "carrier Wi-Fi level flow not collectable")
    }

    private fun reduceWifi(value: Any?, wifiMax: Int, token: Long) {
        if (!enabled || generation.get() != token) return
        val active = value?.javaClass?.name?.endsWith("WifiNetworkModel\$Active") == true
        val next = if (active) {
            (readInt(value, "level") ?: -1).takeIf { it in 0..wifiMax }
                ?.let { (it * 4f / wifiMax).roundToInt().coerceIn(0, 4) }
        } else {
            null
        }
        if (next == wifiLevel) return
        wifiLevel = next
        scheduleRender()
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
                parts.cellularReady && !parts.typeSuppressed && subId != null &&
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

    private fun installBlock(layout: ViewGroup) {
        if (blocks.containsKey(layout)) return
        // A native view supplies its own environment even when PackageReady was missed.
        bindEnvironment(layout.context)
        knownLayouts[layout] = true
        if (layout.id == 0 || layout.id != carrierLayoutId) return
        val linear = layout as? LinearLayout ?: return
        val left = leftTextField?.get(layout) as? LinearLayout ?: return
        val right = rightTextField?.get(layout) as? LinearLayout ?: return
        val rows = listOfNotNull(presentation.buildRow(left, 0), presentation.buildRow(right, 1))
        if (rows.size != CarrierBlockPolicy.ROW_COUNT) return
        val block = CarrierBlock(linear, rows, separatorField?.get(layout) as? View,
            keyguardSeparatorField?.get(layout) as? View)
        blocks[layout] = block
        rows.forEach { rowIndex[it.row] = it; rowIndex[it.networkSlot] = it }
        try {
            rows.forEach { parts ->
                parts.row.addView(parts.signal, 0)
                parts.row.addView(parts.badge, 1)
                // Type and Wi-Fi share one measured row position; connecting cannot add a new box.
                parts.row.addView(parts.airplaneText)
                parts.row.addView(parts.networkSlot)
            }
            presentation.configureBlock(block)
            block.attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { observeBlock(block); scheduleRender() }
                override fun onViewDetachedFromWindow(v: View) {
                    stopObserving(block)
                    releaseMask(block)
                    handover.releaseHandover()
                    // Do not remove children during the host's detach traversal.
                    main.post {
                        if (!layout.isAttachedToWindow && blocks[layout] === block) {
                            runCatching { removeBlock(block) }
                                .onFailure { DebugLog.w(TAG, "carrier detach cleanup failed", it) }
                            blocks.remove(layout)
                        }
                    }
                }
            }.also(layout::addOnAttachStateChangeListener)
            if (layout.isAttachedToWindow) observeBlock(block)
            scheduleRender()
            DebugLog.i(TAG, "carrier block installed rows=${rows.size}")
        } catch (error: Throwable) {
            removeBlock(block)
            blocks.remove(layout)
            throw error
        }
    }

    private fun removeBlock(block: CarrierBlock) {
        stopObserving(block)
        block.attachListener?.let(block.layout::removeOnAttachStateChangeListener)
        releaseMask(block)
        presentation.restoreBlockStyle(block)
        ControlCenterHeaderHooker.releaseCarrier(block.layout)
        block.rows.forEach { parts ->
            rowIndex.remove(parts.row)
            rowIndex.remove(parts.networkSlot)
            handover.retireTarget(parts.signal)
            handover.retireTarget(parts.wifi)
            handover.retireTarget(parts.type)
            parts.networkFade.clear()
            listOf(parts.signal, parts.badge, parts.airplaneText, parts.networkSlot).forEach(parts.row::removeView)
        }
    }

    private fun observeBlock(block: CarrierBlock) {
        stopObserving(block)
        block.observer = block.layout.viewTreeObserver
        block.preDraw = ViewTreeObserver.OnPreDrawListener {
            runCatching {
                presentation.updateRowGeometry(block)
                ControlCenterHeaderHooker.updateCarrierLayout(block.layout, geometryOnly = true)
                syncMask(block)
                if (!shadeSwitching && panelVisible && progress > 0f && progress < 1f) {
                    handover.applyHandoverGuarded(progress)
                }
            }.onFailure { DebugLog.w(TAG, "carrier presentation update failed", it) }
            true
        }.also { block.observer?.addOnPreDrawListener(it) }
    }

    private fun stopObserving(block: CarrierBlock) {
        block.preDraw?.let { listener ->
            block.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        block.preDraw = null
        block.observer = null
    }

    // ---------------------------------------------------------------- rendering

    private val renderGate = FrameUpdateGate()
    private var renderTicket by generationState { 0L }
    private val renderFrame = Choreographer.FrameCallback {
        renderGate.drain(renderTicket) {
            if (enabled) runCatching { renderAll() }
                .onFailure { DebugLog.w(TAG, "carrier frame render failed", it) }
        }
    }

    private fun scheduleRender() {
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

    private fun renderAll() {
        if (!enabled || blocks.isEmpty()) return
        // A failed SVG read must not pin the block to "no artwork": the next state change retries.
        if (artwork == null) scheduleArtworkLoad(generation.get())
        val rows = CarrierBlockPolicy.resolve(
            state = mobileState,
            wifiLevel = wifiLevel,
            config = CarrierBlockConfig(
                showNonDataType = showNonDataType,
                keepTypeOnWifi = keepTypeOnWifi,
                dataFirst = dataFirst,
                networkOnFirstRow = networkOnFirstRow
            ),
            slotOf = ::slotOf
        )
        rowOrder = rows.map { it.slot }
        val rendering = CarrierRenderInput(artwork, mobileState, simNames, showBadge, keepTypeOnWifi, typeConfig)
        blocks.values.toList().forEach { block ->
            if (!block.layout.isAttachedToWindow) return@forEach
            if (block.compact) {
                block.rows.forEach { parts -> rowRenderer.renderRow(parts, rows, rendering) }
                presentation.updateRowGeometry(block)
                ControlCenterHeaderHooker.updateCarrierLayout(block.layout, geometryOnly = true)
            } else releaseMask(block)
        }
    }

    private fun scheduleArtworkLoad(token: Long) {
        if (!enabled || artwork != null || svgRepository == null) return
        synchronized(artLock) {
            if (artGeneration == token || artInFlight == token) return
            artInFlight = token
        }
        assetExecutor.execute {
            val loaded = runCatching {
                val repository = svgRepository ?: return@runCatching null
                val single = repository.loadSignalSingle(SignalSvgStyle.HYPEROS3).getOrNull()
                    ?: return@runCatching null
                val wifi = repository.loadWifi().getOrNull() ?: return@runCatching null
                CarrierArtwork(single, wifi)
            }.onFailure { DebugLog.w(TAG, "carrier artwork load failed", it) }.getOrNull()
            main.post {
                synchronized(artLock) {
                    if (artInFlight == token) artInFlight = Long.MIN_VALUE
                }
                if (!enabled || generation.get() != token) return@post
                if (loaded != null) {
                    artwork = loaded
                    artGeneration = token
                    DebugLog.i(TAG, "carrier artwork ready")
                    scheduleRender()
                    handover.applyHandoverGuarded(progress)
                }
            }
        }
    }

    // ---------------------------------------------------------------- hand-over

    private fun onPanelProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        if (progress > 0f) panelVisible = true
        handover.applyHandoverGuarded(progress)
    }

    // ---------------------------------------------------------------- status row mask

    private fun syncMask(block: CarrierBlock) {
        if (!block.compact || !block.layout.isAttachedToWindow || !block.layout.isShown) {
            releaseMask(block)
            return
        }
        val header = block.layout.parent as? ViewGroup ?: return
        val row = header.findViewById<View>(statusBarId) as? ViewGroup ?: return
        val container = findStatusContainer(row) ?: return
        if (block.maskContainer !== container) releaseMask(block)
        val models = block.rows.mapNotNull { it.model }
        val cellular = CarrierBlockPolicy.replacesStatusSignal(models, mobileState.subscriptionOrder) && block.rows
            .filter { it.model?.visible == true }.all {
                it.row.isLaidOut && it.signal.isVisible && it.signal.drawable != null
            }
        val wifiReplacement = block.rows.any {
            it.wifi.isVisible && it.wifi.width > 0 && it.wifiBitmap != null
        }
        // Once the cellular fallback is drawn, a connecting but non-default native Wi-Fi icon
        // must not appear at the right edge. The fixed network slot still shows cellular there.
        val mask = CarrierMask(cellular, cellular || wifiReplacement)
        val acquired = IconHostPorts.positions.carrierMask(container, mask)
        if (acquired) {
            block.maskContainer = container
            if (mask.active) maskedContainers[container] = mask else maskedContainers.remove(container)
        }
        block.rows.forEach { parts ->
            parts.cellularReady = acquired && cellular
            parts.wifiReady = acquired && wifiReplacement && parts.wifiBitmap != null
            val signalReady = parts.cellularReady
            if (!signalReady || !handover.ownsTarget(parts.signal))
                parts.signal.alpha = if (signalReady) 1f else 0f
            parts.networkSlot.alpha = if (parts.cellularReady || parts.wifiReady) 1f else 0f
        }
    }

    private fun releaseMask(block: CarrierBlock) {
        block.rows.forEach { parts ->
            parts.cellularReady = false; parts.wifiReady = false
            parts.signal.alpha = 0f; parts.networkSlot.alpha = 0f
        }
        val container = block.maskContainer ?: return
        IconHostPorts.positions.carrierMask(container, CarrierMask())
        maskedContainers.remove(container)
        block.maskContainer = null
    }

    private fun findStatusContainer(root: ViewGroup): Any? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 512) {
            val view = queue.removeFirst()
            if (view.javaClass.name == STATUS_ICON_CONTAINER_CLASS) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
            }
        }
        return null
    }

    // ---------------------------------------------------------------- shared helpers

    private fun slotOf(subId: Int): Int = runCatching {
        SubscriptionManager.getSlotIndex(subId)
    }.getOrDefault(CarrierBlockPolicy.INVALID_SLOT)

    /**
     * Cached resolution of the carrier-row callback.
     *
     * The installed hook needs one method, so the result is a descriptor. It used to be rescanned
     * on the SystemUI main thread on every start (577-991 ms, measured 2026-10-24), which lands
     * inside `bindApplication`.
     */
    private fun resolveCarrierTextCallback(): Method? = DexRuntime.resolve(
        feature = CarrierTextCallbackFeature,
        apkPath = hookParam.appInfo?.sourceDir ?: return null,
        cacheDir = dexCacheDirectory,
        cacheEmpty = true,
        appInfo = hookParam.appInfo,
        compute = CarrierTextCallbackCache::descriptors,
        decode = { stored, _ -> CarrierTextCallbackCache.decode(stored, classLoader) },
    )

    private fun installCarrierTextCallback(method: Method) {
            deoptimize(method)
            method.hook { after { param ->
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

    private fun readOuter(target: Any, ownerClassName: String): Any? = runCatching {
        val ownerClass = classLoader.loadClass(ownerClassName)
        val fields = mutableListOf<Field>()
        var current: Class<*>? = target.javaClass
        while (current != null) {
            fields += current.declaredFields.filter { ownerClass.isAssignableFrom(it.type) }
            current = current.superclass
        }
        fields.singleOrNull()?.apply { isAccessible = true }?.get(target)
    }.getOrNull()

    private fun readField(target: Any, name: String): Any? = runCatching {
        field(target.javaClass, name)?.get(target)
    }.getOrNull()

    private fun readInt(target: Any?, name: String): Int? = runCatching {
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
    private fun field(clazz: Class<*>, name: String): Field? = HostAccess.field(clazz, name)

    private fun hierarchyField(clazz: Class<*>, name: String): Field? = field(clazz, name)

    private fun <T> onMainBlocking(action: () -> T): T = HostAccess.onMain(action)
}
