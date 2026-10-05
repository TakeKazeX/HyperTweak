@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoSignalHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compact control-center rows. The host still owns carrier names and the shared mobile reducer
 * owns connectivity; only measurement, presentation and expanded-container masks are replaced.
 */
@SuppressLint("StaticFieldLeak")
object ControlCenterCarrierBlockHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val LAYOUT_CLASS = "com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout"
    private const val ROW_CLASS = "com.android.systemui.controlcenter.shade.ControlCenterCarrierText"
    private const val COMBINED_HEADER_CLASS =
        "com.android.systemui.controlcenter.shade.CombinedHeaderController"
    private const val STATUS_ICON_CONTAINER_CLASS =
        "com.android.systemui.statusbar.views.MiuiStatusIconContainer"
    private const val WIFI_VM_CLASS =
        "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel"
    private const val ROW_CALLBACK_OWNER_MARKER = "$ROW_CLASS\$mCarrierTextCallback"
    private const val PANEL_MOTION_CACHE_KEY = "carrierBlockPanelMotionOwner"
    private const val PANEL_MOTION_OWNER_MARKER =
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController\$controlCenterCallback"

    /**
     * The host's own expansion fraction, in resolution order. Both carry the same `float` the host
     * uses to slide its rows, which makes it the hand-over's follow-finger signal.
     */
    /** Fixed appearance, matching the stacked-signal slot: no user-facing knobs. */
    private const val SIGNAL_ALPHA_FG = 1f
    private const val SIGNAL_ALPHA_BG = 0.4f
    private const val SIGNAL_ALPHA_ERROR = 0.2f
    private const val SIGNAL_SVG_STYLE_IOS = 1

    private val main = Handler(Looper.getMainLooper())
    private val assetExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HyperTweak-CarrierBlockAssets").apply { isDaemon = true }
    }
    private val generation = AtomicLong(0L)
    private val installed = AtomicBoolean(false)
    private val artLock = Any()

    /** Use the regular network-type size, not the former compact 11sp variant. */
    @Volatile private var typeConfig = MobileTypeConfig(
        textSizeSp = 14f,
        weight = 630,
        singleWeight = 400,
        paddingStartSp = 1f,
        paddingEndSp = 1f
    )

    private val blocks = WeakHashMap<Any, Block>()
    private val rowIndex = WeakHashMap<Any, RowParts>()
    private val motions = IdentityHashMap<View, CarrierTypeMotion>()
    private val duoTargets = IdentityHashMap<View, Any>()
    private val endpointStates = IdentityHashMap<View, EndpointState>()
    private val maskedContainers = WeakHashMap<Any, CarrierMask>()
    private val wifiHandles = ArrayList<HostFlowCollector.Handle>()
    private val fieldCache = HashMap<Pair<Class<*>, String>, Field?>()

    @Volatile private var enabled = false
    @Volatile private var showNonDataType = false
    @Volatile private var keepTypeOnWifi = false
    @Volatile private var shadeSwitching = false
    @Volatile private var hostShadeSwitching = false
    @Volatile private var shadeSwitchProgress = 0f

    internal fun isShadeSwitching(): Boolean = shadeSwitching
    private var showBadge = true
    private var badgeTexts = listOf("1", "2")
    @Volatile private var hostContext: Context? = null
    @Volatile private var svgRepository: IconSvgRepository? = null
    @Volatile private var artwork: Artwork? = null
    @Volatile private var artGeneration = Long.MIN_VALUE
    @Volatile private var artInFlight = Long.MIN_VALUE

    private val knownLayouts = WeakHashMap<ViewGroup, Boolean>()
    private var recoveryTask: Runnable? = null
    private var recoveryAttempt = 0
    private var lastRecoveryEvidence: CarrierRecoveryEvidence? = null

    private var wifiScope: Any? = null
    private var wifiInteractor: Any? = null

    private var carrierLayoutId = 0
    private var fakeStatusBarId = 0
    private var statusBarId = 0
    private var wifiSignalId = 0
    private var mobileGroupId = 0
    private var mobileTypeId = 0

    private var leftTextField: Field? = null
    private var rightTextField: Field? = null
    private var carrierTextField: Field? = null
    private var separatorField: Field? = null
    private var keyguardSeparatorField: Field? = null

    /** All mutable view state is consumed on the main looper. */
    private var mobileState = MobileSignalState()
    private var wifiLevel: Int? = null
    private var progress = 0f
    private var panelVisible = false

    private class Artwork(val single: IconSvgSnapshot, val wifi: IconSvgSnapshot)

    private class ViewState(view: View) {
        val params = (view.layoutParams as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
        val visibility = view.visibility
        fun restore(view: View) {
            params?.let { view.layoutParams = LinearLayout.LayoutParams(it) }
            view.visibility = visibility
        }
    }

    private class RowParts(
        val slot: Int,
        val row: LinearLayout,
        val carrierText: TextView,
        val signal: ImageView,
        val badge: TextView,
        val networkSlot: FrameLayout,
        val wifi: ImageView,
        val type: ImageView
    ) {
        val airplaneText = TextView(row.context).apply {
            val id = resources.getIdentifier("airplane_mode", "string", "com.android.systemui")
            text = if (id != 0) context.getString(id) else carrierText.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val networkFade = CarrierNetworkCrossfade(type, wifi) { visible ->
            networkSlot.visibility = if (visible) View.VISIBLE else View.GONE
        }
        val rowState = ViewState(row)
        val textState = ViewState(carrierText)
        val gravity = row.gravity
        val baselineAligned = row.isBaselineAligned
        val contentDescription = row.contentDescription
        val textSize = carrierText.textSize
        val maxWidth = carrierText.maxWidth
        val ellipsize = carrierText.ellipsize
        val fontPadding = carrierText.includeFontPadding
        var showHd: Boolean? = null
        var hd: ViewState? = null
        var plus: ViewState? = null
        var wifiBitmap: Bitmap? = null
        var typeBitmap: Bitmap? = null
        var model: CarrierRowModel? = null
        var cellularReady = false
        var wifiReady = false
        var typeSuppressed = false
        var networkWidth = 0
        val badgeDrawable = GradientDrawable()
        var badgeTint: Int? = null
        var badgeDensity = 0f
        var rendered: RowRenderState? = null
    }

    private data class RowRenderState(
        val model: CarrierRowModel, val art: Artwork, val tint: Int,
        val densityDpi: Int, val fontScale: Float, val rtl: Boolean,
        val iconHeight: Int, val carrier: String, val badgeText: String,
        val single: Boolean, val roaming: Boolean, val keepType: Boolean,
        val config: MobileTypeConfig, val badge: Boolean
    )

    private class Block(
        val layout: LinearLayout,
        val rows: List<RowParts>,
        val separator: View?,
        val keyguardSeparator: View?
    ) {
        val orientation = layout.orientation
        val gravity = layout.gravity
        val separatorState = separator?.let(::ViewState)
        val keyguardSeparatorState = keyguardSeparator?.let(::ViewState)
        var compact = false
        var observer: ViewTreeObserver? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        var maskContainer: Any? = null
    }

    private class EndpointState(view: View) {
        var savedAlpha: Float = view.alpha
        var appliedAlpha: Float = view.alpha
    }

    /** Idempotent host environment binding, shared by package readiness and recovered views. */
    fun onPackageReady(context: Context) {
        StatusIconHostAccess.onMain { bindEnvironment(context) }
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
        if (enabled) scheduleArtworkLoad(generation.get())
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
            (Preferences.getBoolean(Preferences.KEY_CC_HIDE_DATE, false) &&
                Preferences.getBoolean(Preferences.KEY_CC_CARRIER_TWO_LINE, false))

    override fun saveHotReloadState(): Any = onMainBlocking {
        listOf((knownLayouts.keys + blocks.keys.filterIsInstance<ViewGroup>()).distinct(),
            wifiScope, wifiInteractor, progress, panelVisible, hostContext)
    }

    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? List<*> ?: return
        StatusIconHostAccess.onMain {
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
        val component = StatusIconHostAccess.read(app, "mSysUIComponent")
            ?: StatusIconHostAccess.read(app, "mInitializer")?.let { StatusIconHostAccess.invoke(it, "getSysUIComponent") }
        if (component != null) {
            runCatching {
                val header = StatusIconHostAccess.provider(component, "combinedHeaderControllerProvider")
                val layout = header?.let { StatusIconHostAccess.read(it, "controlCenterCarrierLayout") } as? ViewGroup
                if (layout != null) knownLayouts[layout] = true
                val scope = StatusIconHostAccess.provider(component, "bgApplicationScopeProvider")
                val interactor = StatusIconHostAccess.provider(component, "wifiInteractorImplProvider")
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
        applyHandoverGuarded(progress)
        val evidence = CarrierRecoveryEvidence(
            environmentReady = carrierLayoutId != 0 && svgRepository != null,
            hosts = blocks.size,
            rows = blocks.values.sumOf { it.rows.size },
            artworkReady = artwork != null,
            wifiBound = wifiHandles.isNotEmpty(),
            mobileBound = StackedSignalHooker.mobilePipelineConnected
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
        enabled = false
        main.removeCallbacksAndMessages(null)
        recoveryTask = null
        assetExecutor.shutdownNow()
        shadeSwitching = false
        hostShadeSwitching = false
        shadeSwitchProgress = 0f
        wifiHandles.forEach { it.cancel() }
        wifiHandles.clear()
        wifiScope = null
        wifiInteractor = null
        wifiLevel = null
        artwork = null
        synchronized(artLock) {
            artGeneration = Long.MIN_VALUE
            artInFlight = Long.MIN_VALUE
        }
        // Finish host restoration before the lifecycle removes this generation's hooks.
        onMainBlocking {
            Choreographer.getInstance().removeFrameCallback(renderFrame)
            renderGate.cancel()
            motions.values.forEach(CarrierTypeMotion::clear)
            motions.clear()
            duoTargets.clear()
            restoreEndpoints()
            maskedContainers.keys.toList().forEach { container ->
                runCatching { IconPositionHooker.setCarrierMask(container, CarrierMask()) }
            }
            maskedContainers.clear()
            blocks.values.toList().forEach(::removeBlock)
            blocks.clear()
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
        if (generation.get() == token) artGeneration = Long.MIN_VALUE
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        typeConfig = typeConfig.copy(
            small5GaEnabled = Preferences.getBoolean(
                Preferences.KEY_ICON_CELLULAR_TYPE_SMALL_5GA,
                false
            )
        )
        val hideDate = Preferences.getBoolean(Preferences.KEY_CC_HIDE_DATE, false)
        val twoLine = Preferences.getBoolean(Preferences.KEY_CC_CARRIER_TWO_LINE, false)
        showNonDataType = Preferences.getBoolean(
            Preferences.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE,
            false
        )
        keepTypeOnWifi = Preferences.cellularTypeKeepsOnWifi()
        showBadge = Preferences.getBoolean(Preferences.KEY_CC_CARRIER_SHOW_BADGE, true)
        badgeTexts = listOf(
            Preferences.getString(Preferences.KEY_CC_CARRIER_BADGE_ONE, "1"),
            Preferences.getString(Preferences.KEY_CC_CARRIER_BADGE_TWO, "2")
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
        hostContext?.let { scheduleArtworkLoad(generation.get()) }
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
                        configureBlock(block)
                        if (block.compact) {
                            val width = View.MeasureSpec.getSize(param.args[0] as Int)
                            measureRows(block, width)
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
        val callbackMethod = resolveCarrierTextCallback()
        callbackMethod?.let { method ->
            deoptimize(method)
            method.hook { after { param ->
                runCatching {
                    val row = readOuter(param.thisObject, ROW_CLASS) as? ViewGroup ?: return@runCatching
                    if (!ownsRow(row)) return@runCatching
                    rowIndex[row]?.let { parts ->
                        parts.carrierText.visibility = if (parts.carrierText.text.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                    scheduleRender()
                }.onFailure { DebugLog.w(TAG, "carrier name update failed", it) }
            } }
        } ?: DebugLog.hookSkipped(TAG, "carrier text callback", "unique DexKit target not found")
        listOf("onMiuiThemeChanged", "onConfigChanged").forEach { methodName ->
            layoutClass.findMethodOrNull { name(methodName); paramCount(1) }?.let { method ->
                method.hook { after { param ->
                    val block = blocks[param.thisObject] ?: return@after
                    runCatching { configureBlock(block, restyle = true); scheduleRender() }
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
                runCatching { configureBlock(block, restyle = true); scheduleRender() }
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
                    applyHandoverGuarded(if (visible) progress else 0f)
                }
            }
        }
        source.declaredMethods.singleOrNull(::isAppearanceChangedMethod)?.let { method ->
            method.hook {
                after { param ->
                    if (shadeSwitching) return@after
                    if (param.args.getOrNull(0) == true) panelVisible = true
                    applyHandoverGuarded(if (panelVisible) progress else 0f)
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
            runCatching { releaseHandover() }
                .onFailure { DebugLog.w(TAG, "shade-switch carrier release failed", it) }
            val controlCenterVisible = ShadeSwitchMotionPolicy.controlCenterAtRest(shadeSwitchProgress)
            runCatching { LeftContainerHooker.onShadeSwitchStarted(controlCenterVisible) }
                .onFailure { DebugLog.w(TAG, "shade-switch left handover failed", it) }
            runCatching { DuoSignalHooker.onShadeSwitchStarted() }
                .onFailure { DebugLog.w(TAG, "shade-switch Duo handover failed", it) }
        } else {
            val controlCenterVisible = ShadeSwitchMotionPolicy.controlCenterAtRest(shadeSwitchProgress)
            runCatching { LeftContainerHooker.onShadeSwitchFinished(controlCenterVisible) }
                .onFailure { DebugLog.w(TAG, "shade-switch left settle failed", it) }
            runCatching { DuoSignalHooker.onShadeSwitchFinished(controlCenterVisible) }
                .onFailure { DebugLog.w(TAG, "shade-switch Duo settle failed", it) }
        }
    }

    private fun resolvePanelMotionClass(): Class<*>? {
        val appInfo = hookParam.appInfo ?: return null
        val apkPath = appInfo.sourceDir ?: return null
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return null
        return DexKitManager.resolveClasses(
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
        main.post { applyHandoverGuarded(progress) }
    }

    private fun bindWifi(scope: Any, interactor: Any, context: Context) {
        if (wifiInteractor === interactor && wifiHandles.isNotEmpty()) return
        wifiHandles.forEach { it.cancel() }
        wifiHandles.clear()
        wifiScope = scope
        wifiInteractor = interactor
        wifiLevel = null
        val token = generation.get()
        val wifiMax = runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java)?.maxSignalLevel ?: 4
        }.getOrDefault(4).coerceAtLeast(1)
        val flow = readField(interactor, "wifiNetwork") ?: return
        val handle = HostFlowCollector.collect(
            scope = scope,
            flow = flow,
            consumer = { value -> reduceWifi(value, wifiMax, token) },
            isCurrent = { enabled && generation.get() == token }
        ) ?: run {
            DebugLog.w(TAG, "carrier Wi-Fi level flow not collectable")
            return
        }
        wifiHandles += handle
        IconTunerFlows.readFlowValue(flow)?.let { initial ->
            main.post { reduceWifi(initial, wifiMax, token) }
        }
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

    fun firstRowCenter(layout: ViewGroup): Int = blocks[layout]?.rows
        ?.firstOrNull { it.row.isVisible }?.row?.let { it.top + it.measuredHeight / 2 } ?: 0

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
                parts.model?.subId == subId) parts.type else null
            target?.takeIf { isUsable(it) && it.drawable != null && parts.row.isShown }
        }

    fun signalHandoverTarget(container: Any, subId: Int): View? = blocks.values
        .firstOrNull { it.maskContainer === container && it.compact && it.layout.isShown }
        ?.rows?.firstOrNull { it.model?.subId == subId && it.cellularReady }
        ?.signal?.takeIf { isUsable(it) && it.drawable != null }

    /** Alpha has one writer during a Duo hand-over; ordinary masks must not reset it each frame. */
    fun acquireDuoTarget(target: View, owner: Any): Boolean {
        val parts = rowIndex[target.parent] ?: return false
        val ready = when (target) {
            parts.wifi -> parts.wifiReady
            parts.type -> parts.cellularReady && !parts.typeSuppressed
            parts.signal -> parts.cellularReady
            else -> false
        }
        if (!ready || !isUsable(target) || (duoTargets[target]?.let { it !== owner } == true)) return false
        if (duoTargets[target] === owner) return true
        motions.remove(target)?.clear()
        if (target === parts.type || target === parts.wifi) {
            parts.networkFade.lease(target, true)
        }
        target.alpha = 1f
        duoTargets[target] = owner
        return true
    }

    fun releaseDuoTarget(target: View, owner: Any) {
        if (duoTargets[target] !== owner) return
        duoTargets.remove(target)
        val parts = rowIndex[target.parent]
        if (parts != null && (target === parts.wifi || target === parts.type)) {
            parts.networkFade.lease(target, false)
        } else {
            target.alpha = if (parts?.cellularReady == true) 1f else 0f
        }
    }

    private fun installBlock(layout: ViewGroup) {
        if (blocks.containsKey(layout)) return
        // A native view supplies its own environment even when PackageReady was missed.
        bindEnvironment(layout.context)
        knownLayouts[layout] = true
        if (layout.id == 0 || layout.id != carrierLayoutId) return
        val linear = layout as? LinearLayout ?: return
        val left = leftTextField?.get(layout) as? LinearLayout ?: return
        val right = rightTextField?.get(layout) as? LinearLayout ?: return
        val rows = listOfNotNull(buildRow(left, 0), buildRow(right, 1))
        if (rows.size != CarrierBlockPolicy.ROW_COUNT) return
        val block = Block(linear, rows, separatorField?.get(layout) as? View,
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
            configureBlock(block)
            block.attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { observeBlock(block); scheduleRender() }
                override fun onViewDetachedFromWindow(v: View) {
                    stopObserving(block)
                    releaseMask(block)
                    releaseHandover()
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

    private fun buildRow(row: LinearLayout, slot: Int): RowParts? {
        val text = carrierTextField?.get(row) as? TextView ?: return null
        val context = row.context
        val networkSlot = FrameLayout(context).apply {
            visibility = View.GONE
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val type = glyphView(context)
        val wifi = glyphView(context)
        networkSlot.addView(type, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        networkSlot.addView(wifi, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        return RowParts(slot, row, text, glyphView(context), TextView(context).apply {
            this.text = badgeTexts[slot]
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, networkSlot, wifi, type).also { parts ->
            parts.showHd = readField(row, "showHdIcon") as? Boolean
            parts.hd = (readField(row, "hdText") as? View)?.let(::ViewState)
            parts.plus = (readField(row, "plusText") as? View)?.let(::ViewState)
        }
    }

    private fun glyphView(context: Context): ImageView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun configureBlock(block: Block, restyle: Boolean = false) {
        val compact = enabled && ControlCenterHeaderHooker.supportsCompactLayout(block.layout)
        if (block.compact == compact && !restyle) return
        block.compact = compact
        block.rows.forEach { it.rendered = null }
        if (!compact) {
            restoreBlockStyle(block)
            releaseMask(block)
            releaseHandover()
            return
        }
        ControlCenterHeaderHooker.ensureCompactCarrierVisible(block.layout)
        block.layout.orientation = LinearLayout.VERTICAL
        block.layout.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        collapse(block.separator)
        collapse(block.keyguardSeparator)
        // Cancel any horizontal budget queued before we acquired this layout.
        val handler = readField(block.layout, "handler") as? Handler
        val tasks = readField(block.layout, "pendingTasks") as? MutableMap<*, *>
        tasks?.values?.filterIsInstance<Runnable>()?.forEach { handler?.removeCallbacks(it) }
        tasks?.clear()
        (readField(block.layout, "lastMaxWidth") as? IntArray)?.fill(0)
        block.rows.forEach { parts ->
            val density = parts.row.resources.displayMetrics.density
            val gap = (4 * density).roundToInt()
            val icon = iconHeightPx(parts.row.context)
            parts.row.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            parts.row.isBaselineAligned = false
            parts.row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (parts.slot == 0) 0 else (3 * density).roundToInt() }
            parts.carrierText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            parts.carrierText.includeFontPadding = false
            parts.carrierText.ellipsize = TextUtils.TruncateAt.END
            parts.carrierText.visibility = if (parts.carrierText.text.isNullOrBlank()) View.GONE else View.VISIBLE
            parts.signal.layoutParams = LinearLayout.LayoutParams(icon, icon).apply { marginEnd = gap }
            parts.networkWidth = icon
            parts.networkSlot.layoutParams = LinearLayout.LayoutParams(icon,
                CarrierBlockPolicy.typeHeight(icon, density, parts.row.resources.configuration.fontScale)).apply {
                marginStart = gap
            }
            parts.badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            parts.badge.setPaddingRelative((2 * density).roundToInt(), 0, (2 * density).roundToInt(), 0)
            parts.badge.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                (13 * density * parts.row.resources.configuration.fontScale).roundToInt()).apply { marginEnd = gap }
            field(parts.row.javaClass, "showHdIcon")?.setBoolean(parts.row, false)
            (readField(parts.row, "hdText") as? View)?.visibility = View.GONE
            (readField(parts.row, "plusText") as? View)?.visibility = View.GONE
        }
        ControlCenterHeaderHooker.updateCarrierLayout(block.layout)
    }

    private fun collapse(view: View?) {
        view ?: return
        view.visibility = View.GONE
        (view.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0; params.height = 0
            params.setMargins(0, 0, 0, 0); params.marginStart = 0; params.marginEnd = 0
            view.layoutParams = params
        }
    }

    private fun restoreBlockStyle(block: Block) {
        block.layout.orientation = block.orientation
        block.layout.gravity = block.gravity
        block.separator?.let { block.separatorState?.restore(it) }
        block.keyguardSeparator?.let { block.keyguardSeparatorState?.restore(it) }
        block.rows.forEach { parts ->
            parts.rowState.restore(parts.row)
            parts.textState.restore(parts.carrierText)
            if (Preferences.getBoolean(if (parts.slot == 0) Preferences.KEY_ICON_HIDE_CARRIER_ONE
                    else Preferences.KEY_ICON_HIDE_CARRIER_TWO, false)) parts.carrierText.visibility = View.GONE
            parts.row.gravity = parts.gravity
            parts.row.isBaselineAligned = parts.baselineAligned
            parts.row.contentDescription = parts.contentDescription
            parts.carrierText.setTextSize(TypedValue.COMPLEX_UNIT_PX, parts.textSize)
            parts.carrierText.maxWidth = parts.maxWidth
            parts.carrierText.ellipsize = parts.ellipsize
            parts.carrierText.includeFontPadding = parts.fontPadding
            parts.showHd?.let { field(parts.row.javaClass, "showHdIcon")?.setBoolean(parts.row, it) }
            (readField(parts.row, "hdText") as? View)?.let { parts.hd?.restore(it) }
            (readField(parts.row, "plusText") as? View)?.let { parts.plus?.restore(it) }
            parts.networkFade.clear()
            listOf(parts.signal, parts.badge, parts.airplaneText, parts.networkSlot, parts.wifi, parts.type)
                .forEach { it.visibility = View.GONE }
        }
        (readField(block.layout, "lastMaxWidth") as? IntArray)?.fill(0)
    }
    private fun removeBlock(block: Block) {
        stopObserving(block)
        block.attachListener?.let(block.layout::removeOnAttachStateChangeListener)
        releaseMask(block)
        restoreBlockStyle(block)
        ControlCenterHeaderHooker.releaseCarrier(block.layout)
        block.rows.forEach { parts ->
            rowIndex.remove(parts.row)
            rowIndex.remove(parts.networkSlot)
            duoTargets.remove(parts.signal)
            duoTargets.remove(parts.wifi)
            duoTargets.remove(parts.type)
            parts.networkFade.clear()
            listOf(parts.signal, parts.badge, parts.airplaneText, parts.networkSlot).forEach(parts.row::removeView)
        }
    }

    private fun measureRows(block: Block, width: Int) {
        block.rows.forEach { parts ->
            val fixed = listOf(parts.signal, parts.networkSlot)
                .filter { it.visibility != View.GONE }.sumOf { view ->
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    val lp = view.layoutParams as LinearLayout.LayoutParams
                    (if (lp.width >= 0) lp.width else view.measuredWidth) + lp.marginStart + lp.marginEnd
                }
            val textParams = parts.carrierText.layoutParams as? ViewGroup.MarginLayoutParams
            val padding = block.layout.paddingStart + block.layout.paddingEnd + parts.row.paddingStart +
                parts.row.paddingEnd + (textParams?.marginStart ?: 0) + (textParams?.marginEnd ?: 0)
            val available = CarrierBlockPolicy.textWidth(width, fixed + padding)
            val gap = (4 * parts.row.resources.displayMetrics.density).roundToInt()
            val badgeWidth = if (parts.badge.isVisible) CarrierBlockPolicy.badgeWidth(
                available,
                kotlin.math.ceil(parts.badge.paint.measureText(parts.badge.text.toString())).toInt() +
                    parts.badge.paddingStart + parts.badge.paddingEnd,
                gap,
                kotlin.math.ceil(parts.carrierText.paint.measureText("MMMM")).toInt()
            ) else 0
            val badgeParams = parts.badge.layoutParams as LinearLayout.LayoutParams
            val badgeGap = if (badgeWidth > 0) gap else 0
            if (badgeParams.width != badgeWidth || badgeParams.marginEnd != badgeGap) {
                badgeParams.width = badgeWidth
                badgeParams.marginEnd = badgeGap
                parts.badge.layoutParams = badgeParams
            }
            val max = CarrierBlockPolicy.textWidth(available, badgeWidth + badgeGap)
            if (parts.carrierText.maxWidth != max) parts.carrierText.maxWidth = max
            if (parts.airplaneText.maxWidth != max) parts.airplaneText.maxWidth = max
        }
    }

    private fun observeBlock(block: Block) {
        stopObserving(block)
        block.observer = block.layout.viewTreeObserver
        block.preDraw = ViewTreeObserver.OnPreDrawListener {
            runCatching {
                ControlCenterHeaderHooker.updateCarrierLayout(block.layout, geometryOnly = true)
                syncMask(block)
                if (!shadeSwitching && panelVisible && progress > 0f && progress < 1f) {
                    applyHandoverGuarded(progress)
                }
            }.onFailure { DebugLog.w(TAG, "carrier presentation update failed", it) }
            true
        }.also { block.observer?.addOnPreDrawListener(it) }
    }

    private fun stopObserving(block: Block) {
        block.preDraw?.let { listener ->
            block.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        block.preDraw = null
        block.observer = null
    }

    // ---------------------------------------------------------------- rendering

    private val renderGate = FrameUpdateGate()
    private var renderTicket = 0L
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
                keepTypeOnWifi = keepTypeOnWifi
            ),
            slotOf = ::slotOf
        )
        blocks.values.toList().forEach { block ->
            if (!block.layout.isAttachedToWindow) return@forEach
            if (block.compact) block.rows.forEach { parts -> renderRow(parts, rows) }
            else releaseMask(block)
        }
    }

    private fun renderRow(parts: RowParts, rows: List<CarrierRowModel>) {
        val art = artwork
        val model = rows.firstOrNull { it.slot == parts.slot && it.visible }
        parts.model = model
        parts.carrierText.visibility = if (mobileState.airplaneMode || parts.carrierText.text.isNullOrBlank()) View.GONE else View.VISIBLE
        parts.airplaneText.visibility = if (model?.airplane == true && art != null) View.VISIBLE else View.GONE
        parts.badge.visibility = if (mobileState.airplaneMode || !showBadge || art == null || model == null) View.GONE else View.VISIBLE
        if (art == null || model == null) {
            // Before reducer/artwork readiness the host label remains usable. An actually absent
            // slot is hidden only when another subscription establishes a known row set.
            parts.row.visibility = if (mobileState.airplaneMode || (model == null && rows.any { it.visible })) View.GONE else parts.rowState.visibility
            parts.signal.visibility = View.GONE
            parts.networkSlot.visibility = View.GONE
            parts.wifi.visibility = View.GONE
            parts.type.visibility = View.GONE
            parts.networkFade.clear()
            parts.typeSuppressed = false
            parts.wifiBitmap = null
            parts.typeBitmap = null
            parts.rendered = null
            return
        }
        parts.row.visibility = View.VISIBLE
        val tint = runCatching { parts.carrierText.currentTextColor }.getOrDefault(0xFFFFFFFF.toInt())
        val context = parts.row.context
        val density = context.resources.displayMetrics.density
        val densityDpi = context.resources.displayMetrics.densityDpi
        val fontScale = context.resources.configuration.fontScale
        val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val state = RowRenderState(model, art, tint, densityDpi, fontScale, rtl,
            iconHeightPx(context), parts.carrierText.text.toString(), parts.badge.text.toString(),
            mobileState.subscriptionOrder.size == 1,
            mobileState.subscriptions[model.subId]?.roaming == true, keepTypeOnWifi, typeConfig, showBadge)
        if (parts.rendered == state) return
        parts.badge.setTextColor(tint)
        parts.airplaneText.setTextColor(tint)
        if (parts.badgeTint != tint || parts.badgeDensity != density) {
            parts.badgeDrawable.cornerRadius = 2 * density
            parts.badgeDrawable.setStroke(density.roundToInt().coerceAtLeast(1), tint)
            parts.badgeTint = tint
            parts.badgeDensity = density
        }
        if (parts.badge.background !== parts.badgeDrawable) parts.badge.background = parts.badgeDrawable

        // In expanded Duo mode the battery ring stays on its own; the network type and Wi-Fi return
        // to the carrier row alongside each SIM's signal.
        // Signal strength: one single-signal glyph per card, so an enabled stacked icon is split
        // back into the two rows instead of being merged.
        val signalLevel = model.signalLevel
        val signalBitmap = signalLevel?.let { level ->
            runCatching {
                IconSvgRenderer.renderSingle(art.single.document, level, renderConfig(context))
            }.onFailure { DebugLog.w(TAG, "carrier signal render failed", it) }.getOrNull()
        }
        publish(parts.signal, signalBitmap, tint)

        // Wi-Fi glyph on 卡一 only; the data type follows the active data SIM (开关 4: both rows).
        val wifiBitmap = model.wifiLevel?.let { level ->
            runCatching {
                IconSvgRenderer.renderWifi(art.wifi.document, level, renderConfig(context))
            }.onFailure { DebugLog.w(TAG, "carrier wifi render failed", it) }.getOrNull()
        }
        val typeBitmap = model.typeText?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching {
                MobileTypeRenderer.render(
                    output = MobileTypeOutput(
                        text = text,
                        isSingle = mobileState.subscriptionOrder.size == 1,
                        isRoaming = mobileState.subscriptions[model.subId]?.roaming == true
                    ),
                    config = typeConfig,
                    iconHeightPx = CarrierBlockPolicy.typeHeight(iconHeightPx(context),
                        context.resources.displayMetrics.density, fontScale),
                    densityDpi = densityDpi,
                    fontScale = fontScale,
                    rtl = rtl
                )
            }.onFailure { DebugLog.w(TAG, "carrier type render failed", it) }.getOrNull()
        }
        publishNetwork(parts, typeBitmap, wifiBitmap, tint)
        parts.rendered = state.takeIf {
            (model.signalLevel == null || signalBitmap != null) &&
                (model.wifiLevel == null || wifiBitmap != null) &&
                (model.typeText.isNullOrBlank() || typeBitmap != null)
        }
        parts.row.contentDescription = buildString {
            if (showBadge) append(parts.badge.text).append(", ")
            if (model.airplane) append("Airplane mode") else append(parts.carrierText.text)
            model.signalLevel?.let { append(", ").append(it).append("/4") }
            if (!parts.typeSuppressed) model.typeText?.let { append(", ").append(it) }
            if (wifiBitmap != null) append(", Wi-Fi")
        }
    }

    /** One measured trailing position for cellular type and Wi-Fi, including the dual-display option. */
    private fun publishNetwork(parts: RowParts, type: Bitmap?, wifi: Bitmap?, tint: Int) {
        // Keep the previous drawable in the same box while its alpha exits. The model bitmap
        // below still becomes null immediately, so a stale Wi-Fi glyph cannot claim the mask.
        if (type != null || parts.type.drawable == null) publish(parts.type, type, tint)
        if (wifi != null || parts.wifi.drawable == null) publish(parts.wifi, wifi, tint)
        parts.typeBitmap = type
        parts.wifiBitmap = wifi
        val mode = CarrierNetworkChoice.select(
            cellular = type != null,
            wifiDefaultAndRendered = wifi != null,
            keepCellularType = keepTypeOnWifi
        )
        parts.typeSuppressed = mode == CarrierNetworkMode.WIFI
        val icon = iconHeightPx(parts.row.context)
        val gap = (4 * parts.row.resources.displayMetrics.density).roundToInt()
        val width = CarrierNetworkChoice.requiredWidth(
            minimum = icon,
            cellularWidth = type?.width ?: 0,
            wifiWidth = wifi?.width ?: 0,
            keepBoth = keepTypeOnWifi && type != null,
            gap = gap,
            previousWidth = parts.networkWidth
        )
        if (width != parts.networkWidth) {
            parts.networkWidth = width
            val params = parts.networkSlot.layoutParams
            if (params.width != width) { params.width = width; parts.networkSlot.layoutParams = params }
        }
        val height = maxOf(icon, type?.height ?: 0, wifi?.height ?: 0)
        val slotParams = parts.networkSlot.layoutParams
        if (slotParams.height != height) {
            slotParams.height = height
            parts.networkSlot.layoutParams = slotParams
        }
        val both = keepTypeOnWifi && type != null
        val typeGravity = if (both) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        val wifiGravity = if (both) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
        listOf(parts.type to typeGravity, parts.wifi to wifiGravity).forEach { (view, gravity) ->
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@forEach
            if (params.gravity != gravity) { params.gravity = gravity; view.layoutParams = params }
        }
        parts.networkFade.select(mode)
    }

    private fun publish(view: ImageView, bitmap: Bitmap?, tint: Int) {
        if (bitmap == null || bitmap.isRecycled) {
            view.visibility = View.GONE
            return
        }
        view.setImageBitmap(bitmap)
        // Bitmaps are already rendered in physical pixels. Avoid ImageView's intrinsic-density
        // scaling enlarging the Wi-Fi/type glyph and consuming the name's width a second time.
        val params = view.layoutParams
        if (params.width != bitmap.width || params.height != bitmap.height) {
            params.width = bitmap.width; params.height = bitmap.height
            view.layoutParams = params
        }
        view.imageTintList = ColorStateList.valueOf(tint)
        view.visibility = View.VISIBLE
    }

    private fun renderConfig(context: Context): IconSvgRenderConfig = IconSvgRenderConfig(
        iconHeightPx = iconHeightPx(context).coerceIn(1, 512),
        scale = 1f,
        alphaFg = SIGNAL_ALPHA_FG,
        alphaBg = SIGNAL_ALPHA_BG,
        alphaError = SIGNAL_ALPHA_ERROR,
        paddingStartPx = 0,
        paddingEndPx = 0,
        densityDpi = context.resources.displayMetrics.densityDpi,
        fontScale = context.resources.configuration.fontScale,
        configVersion = 3
    )

    private fun iconHeightPx(context: Context): Int {
        val resources = context.resources
        val height = runCatching {
            val id = resources.getIdentifier("status_bar_icon_height", "dimen", "com.android.systemui")
            if (id != 0) resources.getDimensionPixelSize(id) else null
        }.getOrNull()
        return CarrierBlockPolicy.iconHeight(height, resources.displayMetrics.density)
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
                val single = repository.loadSignalSingle(SIGNAL_SVG_STYLE_IOS).getOrNull()
                    ?: return@runCatching null
                val wifi = repository.loadWifi().getOrNull() ?: return@runCatching null
                Artwork(single, wifi)
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
                    applyHandoverGuarded(progress)
                }
            }
        }
    }

    // ---------------------------------------------------------------- hand-over

    private fun onPanelProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        if (progress > 0f) panelVisible = true
        applyHandoverGuarded(progress)
    }

    private fun applyHandoverGuarded(value: Float) {
        runCatching { applyHandover(value) }
            .onFailure { DebugLog.w(TAG, "carrier hand-over failed", it) }
    }

    /**
     * Carries the trailing network glyph through the same hand-over as Duo's middle Wi-Fi layer:
     * an overlay follows the host's expansion fraction from the collapsed status row into the label
     * row, and the label's own glyph fades in during the last quarter. The collapsed copy is
     * alpha-suppressed only after the overlay has produced a frame.
     */
    private fun applyHandover(value: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { applyHandoverGuarded(value) }
            return
        }
        val clamped = value.coerceIn(0f, 1f)
        if (!enabled) {
            releaseHandover()
            return
        }
        if (clamped <= 0f || clamped >= 1f) {
            // Shade switching can report only an endpoint. Do not start a second module-owned
            // animation there: the host is already animating the header and native status row.
            releaseHandover()
            return
        }
        if (artwork == null) { releaseHandover(); return }
        drawHandover(clamped)
    }

    private fun drawHandover(clamped: Float) {
        val used = HashSet<View>()
        val sources = HashSet<View>()
        for (block in blocks.values.toList()) {
            if (!block.compact || !block.layout.isShown) continue
            for (parts in block.rows) {
                val model = parts.model ?: continue
                val tint = parts.carrierText.currentTextColor
                val entries = ArrayList<Pair<ImageView, Pair<View?, Bitmap>>>(2)
                parts.wifiBitmap?.takeIf { parts.wifiReady }?.let { bitmap ->
                    entries += parts.wifi to (sourceGlyph(block, wifi = true, subId = null) to bitmap)
                }
                // A suppressed type stays out of the hand-over: its glyph is faded out on purpose,
                // and the overlay would otherwise draw it at full alpha while the panel opens.
                parts.typeBitmap?.takeIf { parts.cellularReady && !parts.typeSuppressed }?.let { bitmap ->
                    entries += parts.type to (sourceGlyph(block, wifi = false, subId = model.subId) to bitmap)
                }
                for ((target, glyph) in entries) {
                    val (source, bitmap) = glyph
                    if (target in duoTargets || target.visibility != View.VISIBLE || target.width <= 0 || target.height <= 0) continue
                    if (source == null) {
                        motions.remove(target)?.clear()
                        parts.networkFade.lease(target, false)
                        continue
                    }
                    val root = target.rootView as? ViewGroup ?: continue
                    val motion = motions[target] ?: CarrierTypeMotion().also { motions[target] = it }
                    parts.networkFade.lease(target, true)
                    val ready = motion.update(root, source, target, bitmap, tint, clamped)
                    used += target
                    sources += source
                    target.alpha = if (ready) CarrierHandover.destinationAlpha(clamped) else 1f
                    suppressEndpoint(source, ready)
                }
            }
        }
        motions.entries.removeIf { (view, motion) ->
            if (view in used) false else {
                motion.clear()
                val parts = rowIndex[view.parent]
                if (view !in duoTargets) {
                    if (parts != null) parts.networkFade.lease(view as ImageView, false)
                    else view.alpha = 0f
                }
                true
            }
        }
        restoreEndpoints(sources)
    }

    private fun releaseHandover() {
        motions.forEach { (view, motion) ->
            motion.clear()
            rowIndex[view.parent]?.networkFade?.lease(view as ImageView, false)
        }
        motions.clear()
        restoreEndpoints()
        blocks.values.forEach { block ->
            block.rows.forEach { parts ->
                if (parts.wifi !in duoTargets && parts.type !in duoTargets) {
                    parts.networkFade.applyAlphas()
                }
            }
        }
    }

    /**
     * The glyph the user is looking at while the panel is still closed: the collapsed mirror of the
     * status bar inside the shade header (`ControlCenterFakeStatusIcons`), which carries the same
     * bound icons as the home bar.
     */
    private fun sourceGlyph(block: Block, wifi: Boolean, subId: Int?): View? {
        if (fakeStatusBarId == 0) return null
        val header = block.layout.parent as? ViewGroup ?: return null
        // The fake row is the visible stand-in while the panel is dragged.
        val fakeRow = header.findViewById<View>(fakeStatusBarId) as? ViewGroup
        if (fakeRow != null && fakeRow.isAttachedToWindow &&
            !com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoSignalHooker
                .hasActiveProxy(fakeRow)
        ) {
            glyphIn(fakeRow, wifi, subId)?.let { return it }
        }
        // At rest the fake row is gone and the real row owns the glyph, so this is the row a settle
        // ramp has to travel from.
        val realRow = if (statusBarId != 0) {
            header.findViewById<View>(statusBarId) as? ViewGroup
        } else {
            null
        }
        if (realRow == null || !realRow.isAttachedToWindow) return null
        return glyphIn(realRow, wifi, subId)
    }

    private fun glyphIn(row: ViewGroup, wifi: Boolean, subId: Int?): View? {
        if (wifi) return row.findViewById<View>(wifiSignalId)?.takeIf(::isUsable)
        val group = mobileGroup(row, subId ?: return null) ?: return null
        return group.findViewById<View>(mobileTypeId)?.takeIf(::isUsable)
    }

    private fun mobileGroup(root: ViewGroup, subId: Int): ViewGroup? {
        // The status-icon container holds one holder view per subscription; the mobile holder is
        // the one carrying that SIM's `subId` (the same boundary DuoSignalHooker reads).
        val container = findStatusContainer(root) as? ViewGroup
        if (container != null) {
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index) as? ViewGroup ?: continue
                if (readInt(child, "subId") == subId) return child
            }
        }
        if (mobileGroupId == 0) return null
        val found = root.findViewById<View>(mobileGroupId) as? ViewGroup ?: return null
        return if (readInt(found, "subId") == subId) found else null
    }

    /**
     * A hand-over source is a geometric anchor, not something that has to be on screen: at rest the
     * real control-center row's glyph is already masked (invisible) by the time the settle ramp runs,
     * and its last frame is exactly where the travel has to start from.
     */
    private fun isUsable(view: View): Boolean =
        view.isAttachedToWindow && view.width > 0 && view.height > 0

    private fun suppressEndpoint(view: View, ready: Boolean) {
        val entry = endpointStates[view] ?: EndpointState(view).also { endpointStates[view] = it }
        if (abs(view.alpha - entry.appliedAlpha) > .001f) entry.savedAlpha = view.alpha
        val alpha = if (ready) 0f else entry.savedAlpha
        if (view.alpha != alpha) view.alpha = alpha
        entry.appliedAlpha = alpha
    }

    private fun restoreEndpoints(retained: Set<View> = emptySet()) {
        if (endpointStates.isEmpty()) return
        val iterator = endpointStates.entries.iterator()
        while (iterator.hasNext()) {
            val (view, entry) = iterator.next()
            if (view in retained) continue
            if (abs(view.alpha - entry.appliedAlpha) < .001f) view.alpha = entry.savedAlpha
            iterator.remove()
        }
    }

    // ---------------------------------------------------------------- status row mask

    private fun syncMask(block: Block) {
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
        val acquired = IconPositionHooker.setCarrierMask(container, mask)
        if (acquired) {
            block.maskContainer = container
            if (mask.active) maskedContainers[container] = mask else maskedContainers.remove(container)
        }
        block.rows.forEach { parts ->
            parts.cellularReady = acquired && cellular
            parts.wifiReady = acquired && wifiReplacement && parts.wifiBitmap != null
            val signalReady = parts.cellularReady
            if (!signalReady || parts.signal !in duoTargets)
                parts.signal.alpha = if (signalReady) 1f else 0f
            parts.networkSlot.alpha = if (parts.cellularReady || parts.wifiReady) 1f else 0f
        }
    }

    private fun releaseMask(block: Block) {
        block.rows.forEach { parts ->
            parts.cellularReady = false; parts.wifiReady = false
            parts.signal.alpha = 0f; parts.networkSlot.alpha = 0f
        }
        val container = block.maskContainer ?: return
        IconPositionHooker.setCarrierMask(container, CarrierMask())
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

    private fun resolveCarrierTextCallback(): Method? {
        val apkPath = hookParam.appInfo?.sourceDir ?: return null
        return DexKitManager.withBridge(apkPath) { bridge ->
            bridge.findMethod {
                matcher {
                    declaredClass {
                        className(ROW_CALLBACK_OWNER_MARKER, StringMatchType.Contains)
                    }
                    name("onCarrierTextChanged")
                    paramCount(3)
                    returnType(Void.TYPE)
                }
            }.toList().filter { data ->
                data.className.contains(ROW_CALLBACK_OWNER_MARKER) &&
                    data.methodName == "onCarrierTextChanged" &&
                    data.paramCount == 3 && data.returnTypeName == Void.TYPE.name
            }.mapNotNull { data ->
                runCatching { data.getMethodInstance(classLoader) }
                    .onFailure { DebugLog.w(TAG, "failed to inspect ${data.className}#${data.methodName}", it) }
                    .getOrNull()
            }.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.singleOrNull()
        }
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
    private fun field(clazz: Class<*>, name: String): Field? {
        val key = clazz to name
        if (fieldCache.containsKey(key)) return fieldCache[key]
        var current: Class<*>? = clazz
        var resolved: Field? = null
        while (current != null && resolved == null) {
            resolved = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            current = current.superclass
        }
        fieldCache[key] = resolved
        return resolved
    }

    private fun hierarchyField(clazz: Class<*>, name: String): Field? = field(clazz, name)

    private fun <T> onMainBlocking(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        main.post {
            try {
                result = runCatching(action)
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "carrier block main-thread cleanup timed out" }
        return checkNotNull(result).getOrThrow()
    }
}
