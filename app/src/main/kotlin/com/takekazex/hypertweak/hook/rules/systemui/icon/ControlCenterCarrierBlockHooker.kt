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
import android.view.Gravity
import android.view.ViewTreeObserver
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone
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
        val wifi: ImageView,
        val type: ImageView,
        /** Fades the type glyph's bitmap so a Wi-Fi suppression keeps its box (see [BitmapAlphaFade]). */
        val typeFade: BitmapAlphaFade
    ) {
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

        /** False until this row has published a type glyph once; the first paint never ramps. */
        var typePainted = false

        /** True once a suppressed type has faded out and given its box back (no standing gap). */
        var typeCollapsed = false
    }

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

    /** Called by HookEntry once SystemUI's application context is available. */
    fun onPackageReady(context: Context) {
        hostContext = context
        resolveIds()
        val moduleContext = runCatching {
            context.createPackageContext(
                HostIconBridge.MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
        }.getOrNull()
        svgRepository = moduleContext?.let(::IconSvgRepository)
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
        listOf(blocks.keys.filterIsInstance<View>(), wifiScope, wifiInteractor, progress, panelVisible)
    }

    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? List<*> ?: return
        val token = generation.get()
        main.post {
            if (!enabled || generation.get() != token) return@post
            runCatching {
                recoverExistingViews((saved.getOrNull(0) as? List<*>)?.filterIsInstance<View>().orEmpty(),
                    saved.getOrNull(3) as? Float, saved.getOrNull(4) as? Boolean)
                val scope = saved.getOrNull(1)
                val interactor = saved.getOrNull(2)
                val context = hostContext
                if (scope != null && interactor != null && context != null) bindWifi(scope, interactor, context)
            }.onFailure { DebugLog.w(TAG, "carrier hot reload restore failed", it) }
        }
    }

    internal fun recoverExistingViews(views: List<View>, savedProgress: Float?, visible: Boolean?) {
        if (!enabled) return
        savedProgress?.let { progress = it.coerceIn(0f, 1f) }
        visible?.let { panelVisible = it }
        views.filter { it.javaClass.name == LAYOUT_CLASS && it.isAttachedToWindow }
            .filterIsInstance<ViewGroup>().forEach(::installBlock)
        scheduleRender()
        applyHandoverGuarded(progress)
        DebugLog.i(TAG, "hot reload carrier blocks=${blocks.size}")
    }

    internal fun recoverWifi(scope: Any, interactor: Any, context: Context) {
        if (enabled) bindWifi(scope, interactor, context)
    }

    override fun onPrepareHotReload() {
        val token = generation.incrementAndGet()
        enabled = false
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

    private fun resolveIds() {
        val context = hostContext ?: return
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
        val next = if (ready || state.airplaneMode) state else MobileSignalState()
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
                parts.cellularReady && subId != null && parts.model?.subId == subId) parts.type else null
            target?.takeIf { isUsable(it) && it.drawable != null && parts.row.isShown }
        }

    fun signalHandoverTarget(container: Any, subId: Int): View? = blocks.values
        .firstOrNull { it.maskContainer === container && it.compact && it.layout.isShown }
        ?.rows?.firstOrNull { it.model?.subId == subId && it.cellularReady }
        ?.signal?.takeIf { isUsable(it) && it.drawable != null }

    /** Alpha has one writer during a Duo hand-over; ordinary masks must not reset it each frame. */
    fun acquireDuoTarget(target: View, owner: Any): Boolean {
        val parts = rowIndex[target.parent] ?: return false
        val ready = if (target === parts.wifi) parts.wifiReady else (target === parts.type || target === parts.signal) && parts.cellularReady
        if (!ready || !isUsable(target) || (duoTargets[target]?.let { it !== owner } == true)) return false
        if (duoTargets[target] === owner) return true
        motions.remove(target)?.clear()
        target.alpha = 1f
        duoTargets[target] = owner
        return true
    }

    fun releaseDuoTarget(target: View, owner: Any) {
        if (duoTargets[target] !== owner) return
        duoTargets.remove(target)
        val parts = rowIndex[target.parent]
        target.alpha = if (parts != null &&
            (if (target === parts.wifi) parts.wifiReady else parts.cellularReady)) 1f else 0f
    }

    private fun installBlock(layout: ViewGroup) {
        if (blocks.containsKey(layout)) return
        if (carrierLayoutId == 0) resolveIds()
        if (layout.id == 0 || layout.id != carrierLayoutId) return
        val linear = layout as? LinearLayout ?: return
        val left = leftTextField?.get(layout) as? LinearLayout ?: return
        val right = rightTextField?.get(layout) as? LinearLayout ?: return
        val rows = listOfNotNull(buildRow(left, 0), buildRow(right, 1))
        if (rows.size != CarrierBlockPolicy.ROW_COUNT) return
        val block = Block(linear, rows, separatorField?.get(layout) as? View,
            keyguardSeparatorField?.get(layout) as? View)
        blocks[layout] = block
        rows.forEach { rowIndex[it.row] = it }
        try {
            rows.forEach { parts ->
                parts.row.addView(parts.signal, 0)
                parts.row.addView(parts.badge, 1)
                // Order is part of the contract: the cellular type leads and Wi-Fi trails, the same
                // order the status-icon row uses (`mobile` before `wifi`) in every state.
                parts.row.addView(parts.type)
                parts.row.addView(parts.wifi)
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
        return RowParts(slot, row, text, glyphView(context), TextView(context).apply {
            this.text = badgeTexts[slot]
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, glyphView(context), glyphView(context), BitmapAlphaFade(main)).also { parts ->
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
            parts.badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            parts.badge.setPaddingRelative((2 * density).roundToInt(), 0, (2 * density).roundToInt(), 0)
            parts.badge.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                (13 * density * parts.row.resources.configuration.fontScale).roundToInt()).apply { marginEnd = gap }
            listOf(parts.wifi, parts.type).forEach { view ->
                view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    icon).apply { marginStart = gap }
            }
            field(parts.row.javaClass, "showHdIcon")?.setBoolean(parts.row, false)
            (readField(parts.row, "hdText") as? View)?.visibility = View.GONE
            (readField(parts.row, "plusText") as? View)?.visibility = View.GONE
        }
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
            parts.typeFade.cancel()
            parts.typePainted = false
            parts.typeCollapsed = false
            listOf(parts.signal, parts.badge, parts.wifi, parts.type).forEach { it.visibility = View.GONE }
        }
        (readField(block.layout, "lastMaxWidth") as? IntArray)?.fill(0)
    }
    private fun removeBlock(block: Block) {
        stopObserving(block)
        block.attachListener?.let(block.layout::removeOnAttachStateChangeListener)
        releaseMask(block)
        restoreBlockStyle(block)
        block.rows.forEach { parts ->
            rowIndex.remove(parts.row)
            duoTargets.remove(parts.signal)
            duoTargets.remove(parts.wifi)
            duoTargets.remove(parts.type)
            parts.typeFade.cancel()
            listOf(parts.signal, parts.badge, parts.wifi, parts.type).forEach(parts.row::removeView)
        }
    }

    private fun measureRows(block: Block, width: Int) {
        block.rows.forEach { parts ->
            val fixed = listOf(parts.signal, parts.wifi, parts.type)
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
        }
    }

    private fun observeBlock(block: Block) {
        stopObserving(block)
        block.observer = block.layout.viewTreeObserver
        block.preDraw = ViewTreeObserver.OnPreDrawListener {
            runCatching {
                ControlCenterHeaderHooker.updateCarrierLayout(block.layout)
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

    private fun scheduleRender() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            renderAll()
        } else {
            main.post { renderAll() }
        }
    }

    private fun renderAll() {
        if (blocks.isEmpty()) return
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
            if (block.compact) block.rows.forEach { parts -> renderRow(parts, rows) }
            else releaseMask(block)
        }
    }

    private fun renderRow(parts: RowParts, rows: List<CarrierRowModel>) {
        val art = artwork
        val model = rows.firstOrNull { it.slot == parts.slot && it.visible }
        parts.model = model
        parts.badge.visibility = if (!showBadge || art == null || model == null) View.GONE else View.VISIBLE
        if (art == null || model == null) {
            // Before reducer/artwork readiness the host label remains usable. An actually absent
            // slot is hidden only when another subscription establishes a known row set.
            parts.row.visibility = if (model == null && rows.any { it.visible }) View.GONE else parts.rowState.visibility
            parts.signal.visibility = View.GONE
            parts.wifi.visibility = View.GONE
            parts.type.visibility = View.GONE
            parts.typeFade.cancel()
            parts.typeSuppressed = false
            parts.typePainted = false
            parts.typeCollapsed = false
            parts.wifiBitmap = null
            parts.typeBitmap = null
            return
        }
        parts.row.visibility = View.VISIBLE
        val tint = runCatching { parts.carrierText.currentTextColor }.getOrDefault(0xFFFFFFFF.toInt())
        parts.badge.setTextColor(tint)
        val density = parts.row.resources.displayMetrics.density
        parts.badge.background = GradientDrawable().apply {
            cornerRadius = 2 * density
            setStroke(density.roundToInt().coerceAtLeast(1), tint)
        }
        val context = parts.row.context
        val densityDpi = context.resources.displayMetrics.densityDpi
        val fontScale = context.resources.configuration.fontScale
        val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

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
        publish(parts.wifi, wifiBitmap, tint)
        parts.wifiBitmap = wifiBitmap

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
        parts.typeSuppressed = model.typeSuppressed
        publishType(parts, typeBitmap, tint, parts.typeSuppressed)
        parts.row.contentDescription = buildString {
            if (showBadge) append(parts.badge.text).append(", ")
            append(parts.carrierText.text)
            model.signalLevel?.let { append(", ").append(it).append("/4") }
            // A suppressed type is invisible; announcing it would contradict the glyph on screen.
            if (!parts.typeSuppressed) model.typeText?.let { append(", ").append(it) }
        }
    }

    /**
     * Publishes the type glyph and fades it before its box changes.
     *
     * A Wi-Fi suppression must leave **no empty gap**, so the box is released once the glyph has
     * faded out; a returning type takes its box back and fades in. Either way the trailing Wi-Fi
     * glyph is compensated for the width jump and slides into place, because the row lays its
     * trailing glyphs out after the name: an instant box change would make Wi-Fi jump sideways.
     */
    private fun publishType(parts: RowParts, bitmap: Bitmap?, tint: Int, suppressed: Boolean) {
        if (bitmap == null || bitmap.isRecycled) {
            parts.typeFade.cancel()
            parts.typePainted = false
            parts.typeCollapsed = false
            parts.type.visibility = View.GONE
            parts.typeBitmap = null
            return
        }
        // Faded away and still suppressed: the box stays released, and re-rendering must not take it
        // back (the box is what leaves the gap).
        if (suppressed && parts.typeCollapsed) return
        val wasCollapsed = parts.typeCollapsed
        publish(parts.type, bitmap, tint)
        parts.typeBitmap = bitmap
        val params = parts.type.layoutParams as? LinearLayout.LayoutParams
        val boxWidth = (params?.width ?: 0) + (params?.marginStart ?: 0)
        val apply: (Float) -> Unit = { alpha ->
            val frame = if (alpha >= 1f) bitmap else scaledAlphaBitmap(bitmap, alpha)
            parts.type.setImageBitmap(frame)
            parts.type.imageTintList = ColorStateList.valueOf(tint)
        }
        // Only a real box change moves the neighbours; a plain fade leaves the row alone.
        val settle: (Boolean) -> Unit = { collapsed ->
            parts.type.visibility = if (collapsed) View.GONE else View.VISIBLE
            if (parts.typeCollapsed != collapsed) {
                parts.typeCollapsed = collapsed
                // Collapsing frees `boxWidth` for the trailing glyph (Wi-Fi slides left); taking the
                // box back consumes it (Wi-Fi slides right). Offset first, then animate to zero.
                slideTrailing(parts, if (collapsed) boxWidth else -boxWidth)
            }
        }
        when {
            // The box was given back while the glyph was invisible: take it at alpha 0 and ramp in,
            // so the neighbours' slide and the glyph's fade are one motion.
            wasCollapsed && !suppressed -> {
                parts.typeCollapsed = false
                parts.typeFade.snap(visible = false, apply = apply)
                slideTrailing(parts, -boxWidth)
                parts.typeFade.animate(visible = true, apply = apply)
            }
            parts.typePainted -> parts.typeFade.animate(visible = !suppressed, apply = apply) {
                settle(suppressed)
            }
            // First paint of a freshly installed row: straight to the current state, no ramp and no
            // slide — opening the shade must never flash a type that has to stay hidden.
            else -> {
                parts.typePainted = true
                parts.typeFade.snap(visible = !suppressed, apply = apply)
                parts.typeCollapsed = suppressed
                parts.type.visibility = if (suppressed) View.GONE else View.VISIBLE
            }
        }
    }

    /** Offsets the trailing Wi-Fi glyph for a box change and slides it back to its real place. */
    private fun slideTrailing(parts: RowParts, delta: Int) {
        if (delta == 0 || parts.wifi.isGone) return
        parts.wifi.animate().cancel()
        parts.wifi.translationX = delta.toFloat()
        parts.wifi.animate().translationX(0f)
            .setDuration(BitmapAlphaFade.FADE_FRAMES * BitmapAlphaFade.FADE_FRAME_MS)
            .start()
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
                    renderAll()
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
                        target.alpha = 1f
                        continue
                    }
                    val root = target.rootView as? ViewGroup ?: continue
                    val motion = motions[target] ?: CarrierTypeMotion().also { motions[target] = it }
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
                if (view !in duoTargets) view.alpha = if (parts != null &&
                    (if (view === parts.type) parts.cellularReady else parts.wifiReady)) 1f else 0f
                true
            }
        }
        restoreEndpoints(sources)
    }

    private fun releaseHandover() {
        motions.values.forEach(CarrierTypeMotion::clear)
        motions.clear()
        restoreEndpoints()
        blocks.values.forEach { block ->
            block.rows.forEach { parts ->
                if (parts.wifi !in duoTargets) parts.wifi.alpha = if (parts.wifiReady) 1f else 0f
                if (parts.type !in duoTargets) parts.type.alpha = if (parts.cellularReady) 1f else 0f
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
                it.row.isLaidOut && it.signal.isVisible && it.signal.drawable != null &&
                    it.carrierText.isVisible && it.carrierText.width > 0 && !it.carrierText.text.isNullOrBlank()
            }
        val wifi = block.rows.any { it.wifi.isVisible && it.wifi.width > 0 && it.wifiBitmap != null }
        val mask = CarrierMask(cellular, wifi)
        val acquired = IconPositionHooker.setCarrierMask(container, mask)
        if (acquired) {
            block.maskContainer = container
            if (mask.active) maskedContainers[container] = mask else maskedContainers.remove(container)
        }
        block.rows.forEach { parts ->
            parts.cellularReady = acquired && cellular
            parts.wifiReady = acquired && wifi
            if (!parts.cellularReady || parts.signal !in duoTargets)
                parts.signal.alpha = if (parts.cellularReady) 1f else 0f
            // An in-flight overlay owns the type/Wi-Fi alpha until it is released.
            if (!parts.cellularReady || (parts.type !in motions && parts.type !in duoTargets))
                parts.type.alpha = if (parts.cellularReady) 1f else 0f
            if (!parts.wifiReady || (parts.wifi !in motions && parts.wifi !in duoTargets))
                parts.wifi.alpha = if (parts.wifiReady) 1f else 0f
        }
    }

    private fun releaseMask(block: Block) {
        block.rows.forEach { parts ->
            parts.cellularReady = false; parts.wifiReady = false
            parts.signal.alpha = 0f; parts.type.alpha = 0f; parts.wifi.alpha = 0f
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
