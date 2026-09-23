@file:Suppress("StaticFieldLeak")
package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.content.Context
import androidx.core.view.isVisible
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Picture
import android.telephony.SubscriptionManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.ViewTreeObserver
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HostFlowCollector
import com.takekazex.hypertweak.hook.rules.systemui.icon.HostIconBridge
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSvgRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSvgRepository
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterCarrierBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconPositionHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalState
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.PlatformLevel
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * OS4 battery-layout adapter. Original battery objects remain attached and receive host callbacks.
 * Only the parent's View-typed layout reference is borrowed; the icon container never receives a
 * module View (its children must implement the host StatusIconDisplayable interface).
 */
object DuoSignalHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED
    private const val TAG = "DuoSignal"
    private const val AIRPLANE_SLOT = "airplane"
    private const val BATTERY = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
    private const val CONTAINER = "com.android.systemui.statusbar.views.MiuiStatusBatteryContainer"
    private const val WIFI_VM = "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel"
    private val main = Handler(Looper.getMainLooper())
    private val bindings = IdentityHashMap<View, Binding>()
    private val fields = HashMap<Pair<Class<*>, String>, Field?>()
    private val wifiHandles = ArrayList<HostFlowCollector.Handle>()
    @Volatile private var enabled = false
    @Volatile private var epoch = 0
    private var componentSizes = DuoSizes()
    private var panelProgress = 0f
    private var panelVisible = false
    private var panelStretchHeight = 0f
    private var shadeSwitchSuspended = false
    private var shadeSwitchSettledToControlCenter = false
    private var expandedStyle = DuoExpandedStyle.RESTORE_NATIVE
    private var iconSizeDp = DuoLayout.DEFAULT_ICON_SIZE_DP.toFloat()
    @Volatile private var small5GaEnabled = false
    private var mobile = MobileSignalState()
    private var slotIndices: Map<Int, Int> = emptyMap()
    private var network = DuoNetwork()
    private var connectivity: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private var networkPending: Runnable? = null
    private var batteryLayoutField: Field? = null
    private var statusIconsField: Field? = null
    private var visibilityMethod: Method? = null
    private var tintMethod: Method? = null
    private var darkMethod: Method? = null
    private var wifiScope: Any? = null
    private var wifiInteractor: Any? = null
    private data class CellularSignalAssets(
        val single: IconSvgRenderer.Document,
        val stacked: IconSvgRenderer.Document
    )
    private var cellularSignalAssets: CellularSignalAssets? = null
    // Only applicationContext is retained, for restoring the host flow after a reload.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var wifiContext: Context? = null

    private class Binding(val battery: View, val parent: ViewGroup, val icons: View, val surface: DuoSurface) {
        val view = DuoView(battery.context)
        var observer: ViewTreeObserver? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var hostHideBattery = false
        val privacyId = battery.resources.getIdentifier("mini_state_container", "id", battery.context.packageName)
        var privacyView: View? = null
        val privacyRect = RectF()
        var privacyInset = 0
        val panelMotion = DuoPanelMotion()
        val signalMotions = HashMap<Int, SignalRowMotion>()
        val signalTargets = HashMap<Int, View>()
        val signalSourceBounds = RectF()
        val signalPictureCrop = RectF()
        var carrierTarget: View? = null
        var proxyRoot: View? = null
        val proxyX = DuoOwnedTranslation()
        val proxyY = DuoOwnedTranslation()
        var networkMotionProgress = 0f
        var networkMotionSettle: Runnable? = null
        val networkIds = listOf("wifi_signal", "mobile_type", "mobile_signal").associateWith {
            battery.resources.getIdentifier(it, "id", battery.context.packageName)
        }
        var missingSince = -1L
        var expiry: Runnable? = null
        var active = false
        var failed = false
        var batteryGlyph: HostBatteryDrawable? = null
        var dotsInMotion = false
        var reconciling = false
        // Preserve whether the host/user already ignored the native airplane slot before Duo.
        // Duo may add that slot while active, but must never unhide something the host chose to hide.
        var hostIgnoredAirplane: Boolean? = null
    }

    private class DuoView(context: Context) : View(context) {
        val icon = DuoDrawable().also { it.callback = this }
        var drawFailed: (() -> Unit)? = null
        /** User glyph height in dp; both measurement and explicit layout derive their box from it. */
        var iconSizeDp = DuoLayout.DEFAULT_ICON_SIZE_DP.toFloat()
        private val transition = DuoNetworkTransition()
        private val contentLayers = LinkedHashMap<DuoRepresentation, DuoDrawable>()
        private val ring = DuoDrawable().apply { hideNetwork = true; hiddenSignalRows = setOf(0, 1); hideSignalDots = true }
        private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val percentMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var percentText = ""
        private var percentMark = ""
        private var percentLetterSpacing = 0f
        private var percentMarkLetterSpacing = 0f
        private var percentMarkVerticalOffset = 0f
        private var percentGapPx = 0f
        private var percentBelow = false
        private var animator: ValueAnimator? = null

        /** Mirrors the native percent TextViews beside the glyph or below an expanded battery ring. */
        fun setPercent(
            enabled: Boolean,
            below: Boolean,
            container: View?,
            value: TextView?,
            mark: TextView?,
            fallbackText: String? = null
        ) {
            val rawText = value?.text?.toString().orEmpty()
            val nextText = rawText.ifBlank { fallbackText.orEmpty() }
            val sourceReady = nextText.isNotEmpty()
            val activeValue = value?.takeIf {
                enabled && sourceReady && (below ||
                    (container?.isVisible == true && it.isVisible))
            }
            val drawValue = enabled && sourceReady && (below || activeValue != null)
            val activeMark = mark?.takeIf {
                drawValue && (below || it.isVisible)
            }
            val nextMark = if (drawValue) activeMark?.text?.toString().orEmpty().ifBlank {
                if (below) "%" else ""
            } else ""
            val nextLetterSpacing = activeValue?.letterSpacing ?: 0f
            val nextMarkLetterSpacing = activeMark?.letterSpacing ?: 0f
            val density = resources.displayMetrics.density
            val oldSlot = leadingPercentSlotWidthPx()
            val changed = percentBelow != (below && drawValue) ||
                percentText != (if (drawValue) nextText else "") ||
                percentMark != nextMark ||
                percentLetterSpacing != nextLetterSpacing ||
                percentMarkLetterSpacing != nextMarkLetterSpacing ||
                (activeValue != null && (percentPaint.textSize != activeValue.textSize ||
                    percentPaint.typeface != activeValue.typeface ||
                    percentPaint.color != activeValue.currentTextColor)) ||
                (below && drawValue && activeValue == null && percentPaint.color != icon.foreground) ||
                (activeMark != null && (percentMarkPaint.textSize != activeMark.textSize ||
                    percentMarkPaint.typeface != activeMark.typeface ||
                    percentMarkPaint.color != activeMark.currentTextColor))
            if (activeValue != null) {
                percentPaint.set(activeValue.paint)
                percentPaint.color = activeValue.currentTextColor
                percentText = nextText
                percentLetterSpacing = nextLetterSpacing
            } else if (below && drawValue) {
                percentPaint.textSize = 12f * resources.displayMetrics.density *
                    resources.configuration.fontScale
                percentPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                percentPaint.color = icon.foreground
                percentText = nextText
                percentLetterSpacing = 0f
            } else {
                percentText = ""
                percentLetterSpacing = 0f
            }
            if (activeMark != null) {
                percentMarkPaint.set(activeMark.paint)
                percentMarkPaint.color = activeMark.currentTextColor
                percentMark = nextMark
                percentMarkLetterSpacing = nextMarkLetterSpacing
                percentMarkVerticalOffset = (activeMark.paddingTop - activeMark.paddingBottom) / 2f
            } else {
                percentMark = ""
                percentMarkLetterSpacing = 0f
                percentMarkVerticalOffset = 0f
                if (below && drawValue) {
                    percentMarkPaint.set(percentPaint)
                    percentMarkPaint.textSize = percentPaint.textSize * 0.72f
                    percentMarkLetterSpacing = 0f
                }
            }
            percentBelow = below && drawValue
            percentGapPx = if (percentText.isNotEmpty() && !percentBelow) 2f * density else 0f
            if (oldSlot != leadingPercentSlotWidthPx()) requestLayout()
            if (changed || oldSlot != leadingPercentSlotWidthPx()) invalidate()
        }

        /** Width added before the icon, excluding the view's own start/end padding. */
        fun leadingPercentSlotWidthPx(): Int {
            if (percentText.isEmpty() || percentBelow) return 0
            val width = spacedTextWidth(percentText, percentPaint, percentLetterSpacing) +
                spacedTextWidth(percentMark, percentMarkPaint, percentMarkLetterSpacing) + percentGapPx
            return ceil(width.toDouble()).toInt().coerceAtLeast(0)
        }

        private fun spacedTextWidth(text: String, paint: Paint, letterSpacing: Float): Float =
            if (text.isEmpty()) 0f else paint.measureText(text) +
                letterSpacing * paint.textSize * (text.length - 1).coerceAtLeast(0)

        private fun drawSpacedText(canvas: Canvas, text: String, startX: Float, baseline: Float,
                                   paint: Paint, letterSpacing: Float) {
            if (text.isEmpty()) return
            if (letterSpacing == 0f || text.length == 1) {
                canvas.drawText(text, startX, baseline, paint)
                return
            }
            var x = startX
            text.forEachIndexed { index, character ->
                val value = character.toString()
                canvas.drawText(value, x, baseline, paint)
                x += paint.measureText(value)
                if (index < text.lastIndex) x += letterSpacing * paint.textSize
            }
        }

        private fun centeredBaseline(paint: Paint, offset: Float = 0f): Float {
            val metrics = paint.fontMetrics
            return height / 2f - (metrics.ascent + metrics.descent) / 2f + offset
        }

        fun setBatteryOnly(enabled: Boolean, drawable: Drawable?) {
            val nextDrawable = drawable.takeIf { enabled }
            val changed = icon.batteryOnly != enabled || icon.innerBatteryDrawable !== nextDrawable
            icon.batteryOnly = enabled
            icon.innerBatteryDrawable = nextDrawable
            if (changed) invalidate()
        }

        fun submit(content: DuoContent, small5GaEnabled: Boolean, cellularSignal: Picture?) {
            if (icon.content == content && icon.cellularSignalPicture === cellularSignal &&
                icon.small5GaEnabled == small5GaEnabled) return
            icon.small5GaEnabled = small5GaEnabled
            icon.content = content
            icon.cellularSignalPicture = cellularSignal
            val key = DuoRepresentation.of(content)
            contentLayers.getOrPut(key) { DuoDrawable() }.apply {
                this.content = content
                cellularSignalPicture = cellularSignal
            }
            if (transition.submit(key, ValueAnimator.areAnimatorsEnabled())) {
                animator?.cancel()
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 200L
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        transition.advance(it.animatedValue as Float)
                        pruneLayers()
                        invalidate()
                    }
                    start()
                }
            }
            pruneLayers()
            invalidate()
        }

        private fun pruneLayers() {
            val target = icon.content?.let(DuoRepresentation::of)
            contentLayers.keys.removeAll { it != target && it !in transition.weights }
        }

        fun finishTransition() {
            animator?.cancel()
            animator = null
            transition.finish()
            pruneLayers()
        }

        override fun onDetachedFromWindow() {
            finishTransition()
            super.onDetachedFromWindow()
        }
        override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean = who === icon || super.verifyDrawable(who)
        /** The carrier takes the retained battery's box in full; the drawable fits its glyph. */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val desired = DuoLayout.iconSizePx(resources.displayMetrics.density, iconSizeDp)
            // WRAP_CONTENT must report an intrinsic desired size. Feeding MeasureSpec.getSize()
            // back into resolveSize() makes an AT_MOST spec consume the parent's whole allowance.
            // position() aligns the box with the current parent's end edge once active.
            setMeasuredDimension(
                resolveSize(maxOf(desired + paddingStart + paddingEnd + leadingPercentSlotWidthPx(),
                    suggestedMinimumWidth), widthMeasureSpec),
                resolveSize(maxOf(desired, suggestedMinimumHeight), heightMeasureSpec)
            )
        }
        override fun onDraw(canvas: Canvas) {
            val iconSide = minOf(height, DuoLayout.iconSizePx(resources.displayMetrics.density, iconSizeDp))
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            val iconLeft = if (rtl) paddingLeft else width - paddingRight - iconSide
            icon.setBounds(iconLeft, 0, iconLeft + iconSide, iconSide)
            runCatching {
                // The ring is stable; only network representations crossfade.
                ring.sizes = icon.sizes
                ring.content = icon.content
                ring.foreground = icon.foreground
                ring.bounds = icon.bounds
                ring.draw(canvas)
                transition.weights.forEach { (key, weight) ->
                    contentLayers[key]?.apply {
                        sizes = icon.sizes
                        hidePowerTrack = true
                        hiddenSignalRows = icon.hiddenSignalRows
                        hideNetwork = icon.hideNetwork
                        hideSignalDots = icon.hideSignalDots
                        batteryOnly = icon.batteryOnly
                        innerBatteryDrawable = icon.innerBatteryDrawable
                        bounds = icon.bounds
                        foreground = icon.foreground
                        small5GaEnabled = icon.small5GaEnabled
                        alpha = (weight * 255).roundToInt()
                        draw(canvas)
                    }
                }
                if (percentBelow && percentText.isNotEmpty()) {
                    drawPercentBelow(canvas, iconLeft, iconSide)
                } else if (percentText.isNotEmpty()) {
                    val startX = if (rtl) iconLeft + iconSide + percentGapPx else paddingLeft.toFloat()
                    drawSpacedText(canvas, percentText, startX, centeredBaseline(percentPaint),
                        percentPaint, percentLetterSpacing)
                    val markX = startX + spacedTextWidth(percentText, percentPaint, percentLetterSpacing)
                    drawSpacedText(canvas, percentMark, markX,
                        centeredBaseline(percentMarkPaint, percentMarkVerticalOffset),
                        percentMarkPaint, percentMarkLetterSpacing)
                }
            }.onFailure { drawFailed?.invoke() }
        }

        private fun drawPercentBelow(canvas: Canvas, iconLeft: Int, iconSide: Int) {
            val valueWidth = spacedTextWidth(percentText, percentPaint, percentLetterSpacing)
            val markWidth = spacedTextWidth(percentMark, percentMarkPaint, percentMarkLetterSpacing)
            val width = valueWidth + markWidth
            val valueMetrics = percentPaint.fontMetrics
            val markMetrics = percentMarkPaint.fontMetrics
            val textHeight = maxOf(valueMetrics.descent - valueMetrics.ascent,
                markMetrics.descent - markMetrics.ascent)
            if (width <= 0f || textHeight <= 0f) return
            val scale = minOf(1f, iconSide * PERCENT_BELOW_MAX_WIDTH_FRACTION / width,
                iconSide * PERCENT_BELOW_MAX_HEIGHT_FRACTION / textHeight)
            val save = canvas.save()
            try {
                canvas.translate(iconLeft + iconSide / 2f, iconSide * PERCENT_BELOW_CENTER_Y)
                canvas.scale(scale * icon.sizes.percent, scale * icon.sizes.percent)
                val startX = -width / 2f
                val baseline = -(valueMetrics.ascent + valueMetrics.descent) / 2f
                drawSpacedText(canvas, percentText, startX, baseline, percentPaint, percentLetterSpacing)
                val markX = startX + valueWidth
                drawSpacedText(canvas, percentMark, markX,
                    baseline + percentMarkVerticalOffset, percentMarkPaint, percentMarkLetterSpacing)
            } finally {
                canvas.restoreToCount(save)
            }
        }

        private companion object {
            const val PERCENT_BELOW_MAX_WIDTH_FRACTION = 0.72f
            const val PERCENT_BELOW_MAX_HEIGHT_FRACTION = 0.42f
            const val PERCENT_BELOW_CENTER_Y = 0.84f
        }
    }

    override fun onHook() {
        small5GaEnabled = Preferences.getBoolean(
            Preferences.KEY_ICON_CELLULAR_TYPE_SMALL_5GA,
            false
        )
        enabled = PlatformLevel.isOs4 && isMainProcess &&
            Preferences.getBoolean(Preferences.KEY_ICON_DUO_ENABLED, false)
        if (!enabled) return
        epoch++
        expandedStyle = if (Preferences.getInt(Preferences.KEY_ICON_DUO_EXPANDED, 1) == 0)
            DuoExpandedStyle.KEEP_DUO else DuoExpandedStyle.RESTORE_NATIVE
        iconSizeDp = DuoLayout.safeSizeDp(
            Preferences.getInt(
                Preferences.KEY_ICON_DUO_SIZE,
                DuoLayout.DEFAULT_ICON_SIZE_DP
            ).toFloat()
        )
        componentSizes = DuoSizes(
            ring = DuoSizes.ratio(Preferences.getInt(Preferences.KEY_ICON_DUO_RING_SCALE, 100)),
            wifi = DuoSizes.ratio(Preferences.getInt(Preferences.KEY_ICON_DUO_WIFI_SCALE, 100)),
            cellular = DuoSizes.ratio(Preferences.getInt(Preferences.KEY_ICON_DUO_CELLULAR_SCALE, 100)),
            type = DuoSizes.ratio(Preferences.getInt(Preferences.KEY_ICON_DUO_TYPE_SCALE, 100)),
            dots = DuoSizes.ratio(Preferences.getInt(Preferences.KEY_ICON_DUO_DOTS_SCALE, 100)),
            airplane = DuoSizes.ratio(Preferences.getInt(Preferences.KEY_ICON_DUO_AIRPLANE_SCALE, 100)),
            battery = DuoSizes.ratio(Preferences.getInt(Preferences.KEY_ICON_DUO_BATTERY_SCALE, 100)),
            percent = DuoSizes.ratio(Preferences.getInt(Preferences.KEY_ICON_DUO_PERCENT_SCALE, 100))
        )
        IconTunerFlows.init(classLoader)
        val batteryClass = BATTERY.toClassOrNull() ?: run { enabled = false; return }
        val parentClass = CONTAINER.toClassOrNull() ?: run { enabled = false; return }
        batteryLayoutField = field(parentClass, "mBattery")?.takeIf { it.type == View::class.java }
        statusIconsField = field(parentClass, "mStatusIcon")
        visibilityMethod = batteryClass.declaredMethods.singleOrNull {
            it.name == "updateVisibility\$6" && it.parameterCount == 0
        }?.apply { isAccessible = true }
        if (batteryLayoutField == null || statusIconsField == null || visibilityMethod == null) {
            enabled = false
            DebugLog.hookSkipped(TAG, "battery layout", "unsupported signature")
            return
        }
        tintMethod = "com.android.systemui.plugins.DarkIconDispatcher".toClassOrNull()?.methods?.singleOrNull {
            it.name == "getTint" && it.parameterCount == 3
        }
        darkMethod = "com.android.systemui.statusbar.DarkIconDispatcherExt".toClassOrNull()?.methods?.singleOrNull {
            it.name == "getDarkIntensity" && it.parameterCount == 3
        }
        // No hooks on global View/ImageView draw, visibility or tint methods.
        batteryClass.findMethodOrNull { name("onAttachedToWindow"); paramCount(0) }?.hook {
            after { param -> (param.thisObject as? View)?.let { battery ->
                val token = epoch
                main.post { if (enabled && epoch == token && battery.isAttachedToWindow) guarded { attach(battery) } }
            } }
        }
        batteryClass.findMethodOrNull { name("onDetachedFromWindow"); paramCount(0) }?.hook {
            before { param -> (param.thisObject as? View)?.let { battery -> guarded { detach(battery) } } }
        }
        visibilityMethod?.hook {
            after { param -> (param.thisObject as? View)?.let { battery -> guarded {
                bindings[battery]?.let { reconcile(it) }
            } } }
        }
        for (name in listOf("onBatteryLevelChanged", "onPowerSaveChanged", "onChargeStateChanged", "updateLightDarkTint", "updateAll")) {
            batteryClass.declaredMethods.filter { it.name == name }.forEach { method ->
                method.hook { after { param -> (param.thisObject as? View)?.let { battery -> guarded {
                    bindings[battery]?.let { reconcile(it) }
                } } } }
            }
        }
        // The parent uses mBattery in both measure and layout. Deoptimize these consumers so
        // compiled host code observes the borrowed reference, then reconcile after every layout.
        for (name in listOf("onMeasure", "onLayout")) {
            parentClass.declaredMethods.filter { it.name == name }.forEach { method ->
                deoptimize(method)
                if (name == "onLayout") method.hook { after { param ->
                    bindings.values.firstOrNull { it.parent === param.thisObject }?.let { binding ->
                        guarded { reconcile(binding); if (binding.active) position(binding) }
                    }
                } }
            }
        }
        parentClass.declaredMethods.singleOrNull { it.name == "setIsHideBattery" && it.parameterCount == 1 }?.hook {
            before { param ->
                val binding = bindings.values.firstOrNull { it.parent === param.thisObject } ?: return@before
                binding.hostHideBattery = param.args[0] as? Boolean ?: return@before
                if (binding.active) param.args[0] = false
            }
        }
        hookProxyCompensation()
        hookWifi()
        DebugLog.hookRegistered(TAG, "OS4 battery container, expanded=$expandedStyle")
    }

    val requiresMobileState: Boolean get() = enabled

    /** Reuses the existing mobile reducer; collector-only mode never masks native mobile flows. */
    fun onMobileState(state: MobileSignalState, ready: Boolean) {
        if (!enabled) return
        val next = if (ready || state.airplaneMode) state else MobileSignalState()
        val nextSlots = next.subscriptionOrder.associateWith {
            runCatching { SubscriptionManager.getSlotIndex(it) }.getOrDefault(-1)
        }
        if (mobile == next && slotIndices == nextSlots) return
        slotIndices = nextSlots
        mobile = next
        refresh()
    }

    private fun loadCellularSignalAssets(context: Context): CellularSignalAssets? {
        cellularSignalAssets?.let { return it }
        return runCatching {
            val moduleContext = context.createPackageContext(
                HostIconBridge.MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val repository = IconSvgRepository(moduleContext)
            CellularSignalAssets(
                single = repository.loadSignalSingle(0).getOrThrow().document,
                stacked = repository.loadSignalStacked(0).getOrThrow().document
            ).also { cellularSignalAssets = it }
        }.onFailure { DebugLog.w(TAG, "cellular signal artwork unavailable", it) }
            .getOrNull()
    }

    private fun renderCellularSignal(context: Context, content: DuoContent): Picture? {
        if (content.airplaneMode || content.wifiLevel != null ||
            (content.networkLabel == null && !content.noService)) return null
        val levels = content.cellularSignalLevels.take(2)
        if (levels.isEmpty()) return null
        val assets = loadCellularSignalAssets(context) ?: return null
        return runCatching {
            IconSvgRenderer.signalPicture(if (levels.size > 1) assets.stacked else assets.single, levels)
        }.onFailure { DebugLog.w(TAG, "cellular signal vector recording failed", it) }.getOrNull()
    }

    private fun originalBatteryDrawable(binding: Binding): Drawable? {
        val battery = binding.battery
        val name = if (read(battery, "mBatteryStyle") == 1) "mHollowBatteryIconView" else "mBatteryIconView"
        val source = read(battery, name) as? View ?: return null
        if (source.width <= 0 || source.height <= 0) return null
        return binding.batteryGlyph?.takeIf { it.source === source }
            ?: HostBatteryDrawable(source, listOfNotNull(
                read(source, "textPaint") as? Paint, read(source, "hollowTextPaint") as? Paint
            )).also { binding.batteryGlyph = it }
    }

    private fun attach(battery: View) {
        if (bindings.containsKey(battery)) return
        val parent = battery.parent as? ViewGroup ?: return
        if (parent.javaClass.name != CONTAINER || batteryLayoutField?.get(parent) !== battery) return
        val surface = surface(battery)
        // Keep dormant expanded bindings too: switching from native to Duo must not require
        // that the already-attached control-center header happens to be reinflated.
        if (surface == DuoSurface.UNSUPPORTED) return
        val icons = statusIconsField?.get(parent) as? View ?: return
        ensureConnectivity(battery.context)
        val binding = Binding(battery, parent, icons, surface)
        binding.hostHideBattery = read(parent, "mIsHideBattery") as? Boolean ?: false
        binding.view.iconSizeDp = iconSizeDp
        binding.view.setPaddingRelative((4f * battery.resources.displayMetrics.density).roundToInt(), 0, 0, 0)
        captureAirplaneMaskBaseline(binding)
        bindings[battery] = binding
        binding.view.drawFailed = {
            binding.failed = true
            main.post { guarded { reconcile(binding) } }
        }
        // Use the parent's own LayoutParams class, required by measureChildWithMargins.
        val original = battery.layoutParams
        // OS4 LayoutParams has no declared constructor (DEX constructor elision). The parent
        // only casts to MarginLayoutParams, so use the platform copy constructor.
        binding.view.layoutParams = (if (original is ViewGroup.MarginLayoutParams)
            ViewGroup.MarginLayoutParams(original) else ViewGroup.MarginLayoutParams(original)).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.view.visibility = View.GONE
        binding.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        try { parent.addView(binding.view, parent.indexOfChild(battery) + 1) } catch (error: Throwable) {
            bindings.remove(battery)
            throw error
        }
        binding.observer = parent.viewTreeObserver
        binding.preDraw = ViewTreeObserver.OnPreDrawListener {
            guarded { reconcile(binding); updatePanelBinding(binding) }
            // This listener observes one icon. It must never veto the whole shade/window frame,
            // including while the host expansion animation keeps requesting layout.
            true
        }.also { binding.observer?.addOnPreDrawListener(it) }
        reconcile(binding)
    }

    private fun surface(view: View): DuoSurface {
        var node = view.parent
        repeat(20) {
            val current = node as? View ?: return DuoSurface.UNSUPPORTED
            when (current.javaClass.name) {
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView" -> return DuoSurface.HOME
                "com.android.systemui.qs.MiuiQSHeaderView",
                "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon" -> return DuoSurface.EXPANDED
                "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons" -> return DuoSurface.COLLAPSED_PROXY
            }
            node = current.parent
        }
        return DuoSurface.UNSUPPORTED
    }

    /** Use current parent geometry, never the GONE battery's stale portrait coordinates. */
    private fun position(binding: Binding) {
        val parent = binding.parent
        val view = binding.view
        if (parent.width <= 0 || parent.height <= 0) return
        val box = DuoLayout.box(parent.width, parent.height, parent.paddingLeft, parent.paddingTop,
            parent.paddingRight, parent.paddingBottom,
            DuoLayout.iconSizePx(view.resources.displayMetrics.density, view.iconSizeDp),
            view.paddingStart + view.paddingEnd + view.leadingPercentSlotWidthPx(),
            parent.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        view.measure(View.MeasureSpec.makeMeasureSpec(box.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(box.height, View.MeasureSpec.EXACTLY))
        if (view.left != box.left || view.top != box.top || view.width != box.width || view.height != box.height)
            view.layout(box.left, box.top, box.left + box.width, box.top + box.height)
    }

    private fun reconcile(binding: Binding) {
        if (binding.reconciling) return
        binding.reconciling = true
        try {
            expandedStyle = if (Preferences.getInt(Preferences.KEY_ICON_DUO_EXPANDED, 1) == 0)
                DuoExpandedStyle.KEEP_DUO else DuoExpandedStyle.RESTORE_NATIVE
            val battery = binding.battery
            if (binding.active && batteryLayoutField?.get(binding.parent) !== binding.view) binding.failed = true
            val percent = (read(battery, "mLevel") as? Int)?.takeIf { read(battery, "mFirstLevel") == false }
            val charging = read(battery, "mCharging") as? Boolean
            val powerSave = read(battery, "mPowerSave") as? Boolean
            val freshContent = if (percent != null && charging != null && powerSave != null)
                DuoPolicy.content(DuoBattery(percent, charging, powerSave), mobile, network) { subId ->
                    slotIndices[subId] ?: -1
                } else null
            val content = presentationContent(binding, freshContent)
            val privacyState = read(binding.parent, "mPrivacyState")?.toString()
            val privacyShowing = DuoPrivacyGeometry.isTransition(privacyState)
            val visible = (read(battery, "mHomeBlock") == false || privacyShowing) && read(battery, "mMinimalism") == false &&
                read(battery, "mIsAodAnimate") != true
            if (!enabled || binding.failed || content == null || !visible || battery.parent !== binding.parent ||
                !DuoPolicy.replaces(surface(battery), expandedStyle)) {
                restore(binding)
                return
            }
            binding.view.icon.sizes = componentSizes
            binding.view.icon.foreground = foreground(binding)
            val batteryOnly = binding.surface == DuoSurface.EXPANDED &&
                expandedStyle == DuoExpandedStyle.KEEP_DUO
            val percentContainer = read(battery, "mBatteryPercentContainer") as? View
            val percentView = read(battery, "mBatteryPercentView") as? TextView
            val percentMark = read(battery, "mBatteryPercentMarkView") as? TextView
            val showLeadingPercent = DuoPolicy.leadingPercent(binding.surface, expandedStyle,
                Preferences.getBoolean(Preferences.KEY_CC_BATTERY_PERCENT_LEFT, false))
            val batteryGlyph = if (batteryOnly) originalBatteryDrawable(binding) else null
            if (batteryOnly && batteryGlyph == null) { restore(binding); return }
            binding.view.setBatteryOnly(batteryOnly, batteryGlyph)
            binding.view.setPercent(
                enabled = showLeadingPercent || batteryOnly,
                below = batteryOnly,
                container = percentContainer,
                value = percentView,
                mark = percentMark,
                fallbackText = percent?.toString()
            )
            binding.view.icon.hideSignalDots = binding.dotsInMotion || hideExpandedDots(binding)
            binding.view.submit(content, small5GaEnabled, renderCellularSignal(battery.context, content))
            binding.view.contentDescription = buildString {
                append(battery.contentDescription?.toString().orEmpty())
                if (content.airplaneMode) {
                    append(", Airplane mode")
                } else {
                    append(", ")
                    append(content.networkLabel ?: "Wi-Fi ${content.wifiLevel}/4")
                    append(", SIM ${if (content.noService) "0" else content.mobileLevel}/4")
                    if (content.noInternet) append(", !")
                }
            }
            // Charging animates the retained battery into the island (alpha/scale/translation).
            // Those transforms belong to the old battery, not this fixed connectivity cluster.
            binding.view.alpha = 1f
            binding.view.translationX = privacyOffset(binding, privacyState)
            binding.view.translationY = 0f
            binding.view.scaleX = 1f
            binding.view.scaleY = 1f
            if (!binding.active) {
                if (batteryLayoutField?.get(binding.parent) !== battery) return
                // Establish a measured, visible replacement before claiming the signal mask.
                batteryLayoutField?.set(binding.parent, binding.view)
                binding.active = true
                field(binding.parent.javaClass, "mIsHideBattery")?.setBoolean(binding.parent, false)
                binding.view.visibility = View.VISIBLE
                battery.visibility = View.GONE
                binding.parent.requestLayout()
            }
            battery.visibility = View.GONE
            // During the full capsule the host reserves its width instead of the battery width.
            // Reserve the extra Duo box too, so neighbouring native icons cannot occupy it.
            setPrivacyInset(binding, if (privacyState == "START_SHOW_PRIVACY" ||
                privacyState == "COMPLETE_SHOW_PRIVACY") binding.view.width else 0)
            // Geometry is owned by the parent layout pass, not by callbacks/pre-draw.
            if (binding.view.width > 0 && binding.view.height > 0 && binding.view.isAttachedToWindow) {
                // Expanded KEEP_DUO keeps the battery ring, while native/carrier network rows own
                // their original positions again. Home and the collapsed proxy remain composed.
                val masksNetwork = !(binding.surface == DuoSurface.EXPANDED &&
                    expandedStyle == DuoExpandedStyle.KEEP_DUO)
                if (!IconPositionHooker.setDuoMask(binding.icons, masksNetwork)) {
                    binding.failed = true
                    restore(binding)
                } else {
                    // Native SystemUI publishes its own `airplane` status-bar slot.  Duo renders
                    // that state inside the battery ring, so keep the native slot ignored while
                    // the replacement owns this cluster; otherwise two planes appear side by side.
                    setNativeAirplaneMasked(binding, masksNetwork)
                }
            }
        } catch (error: Throwable) {
            binding.failed = true
            restore(binding)
            DebugLog.w(TAG, "container failed; restored native", error)
        } finally { binding.reconciling = false }
    }

    /** Keep a valid picture briefly while independent host flows finish a handover. */
    private fun presentationContent(binding: Binding, fresh: DuoContent?): DuoContent? {
        if (fresh != null) {
            binding.missingSince = -1L
            binding.expiry?.let(main::removeCallbacks)
            binding.expiry = null
            return fresh
        }
        if (!binding.active) return null
        val now = android.os.SystemClock.uptimeMillis()
        if (binding.missingSince < 0L) {
            binding.missingSince = now
            val token = epoch
            binding.expiry = Runnable {
                if (enabled && token == epoch && bindings[binding.battery] === binding) guarded { reconcile(binding) }
            }.also { main.postDelayed(it, DuoHandover.GRACE_MS) }
        }
        return binding.view.icon.content.takeIf { DuoHandover.keepPrevious(binding.missingSince, now) }
    }

    /** Reuse the actual panel progress, but anchor Duo to Duo rather than native signal widths. */
    private fun hookProxyCompensation() {
        val type = "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController\$controlCenterCallback\$1".toClassOrNull() ?: return
        val method = type.declaredMethods.singleOrNull {
            it.name == "onExpansionChanged" && it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
        } ?: return
        deoptimize(method)
        method.hook {
            before { guarded {
                if (ControlCenterCarrierBlockHooker.isShadeSwitching()) {
                    clearShadeSwitchMotion()
                } else {
                    bindings.values.toList().forEach(::restoreProxyPosition)
                }
            } }
            after { param -> guarded {
                if (ControlCenterCarrierBlockHooker.isShadeSwitching()) return@guarded
                val progress = param.args.getOrNull(0) as? Float ?: return@guarded
                if (progress !in 0f..1f) return@guarded
                if (progress < 0.999f) shadeSwitchSettledToControlCenter = false
                panelProgress = progress
                if (progress > 0f) panelVisible = true
                bindings.values.toList().forEach(::updatePanelBinding)
            } }
        }
        type.findMethodOrNull { name("onStretchHeightChanged"); paramCount(1) }?.hook {
            after { param -> guarded {
                panelStretchHeight = (param.args.getOrNull(0) as? Number)?.toFloat() ?: 0f
            } }
        }
        type.findMethodOrNull { name("onVisibleChanged"); paramCount(1) }?.hook {
            after { param -> guarded {
                if (ControlCenterCarrierBlockHooker.isShadeSwitching()) {
                    clearShadeSwitchMotion()
                    return@guarded
                }
                panelVisible = param.args.getOrNull(0) == true
                if (!panelVisible) {
                    panelProgress = 0f
                    panelStretchHeight = 0f
                    shadeSwitchSettledToControlCenter = false
                }
                bindings.values.toList().forEach(::updatePanelBinding)
            } }
        }
        type.findMethodOrNull { name("onAppearanceChanged"); paramCount(2) }?.hook {
            after { param -> guarded {
                if (ControlCenterCarrierBlockHooker.isShadeSwitching()) {
                    clearShadeSwitchMotion()
                    return@guarded
                }
                if (param.args.getOrNull(0) == true) panelVisible = true
                // Appearance can swap rows before the finger is released. Keep the actual fraction.
                bindings.values.toList().forEach(::updatePanelBinding)
            } }
        }
    }

    /** The shade switch owns this motion; never replay the proxy/Wi-Fi hand-over on that path. */
    private fun clearShadeSwitchMotion() {
        if (shadeSwitchSuspended) return
        shadeSwitchSuspended = true
        shadeSwitchSettledToControlCenter = false
        panelProgress = 0f
        panelVisible = false
        panelStretchHeight = 0f
        bindings.values.toList().forEach { binding ->
            clearPanelMotion(binding)
            clearSignalMotions(binding)
            restoreProxyPosition(binding)
        }
    }

    internal fun onShadeSwitchStarted() {
        if (enabled) guarded { clearShadeSwitchMotion() }
    }

    internal fun onShadeSwitchFinished(controlCenterVisible: Boolean) {
        if (!enabled) return
        guarded {
            shadeSwitchSuspended = false
            shadeSwitchSettledToControlCenter = controlCenterVisible
            panelVisible = controlCenterVisible
            panelProgress = if (controlCenterVisible) 1f else 0f
            panelStretchHeight = 0f
            bindings.values.toList().forEach { binding ->
                clearPanelMotion(binding)
                clearSignalMotions(binding)
                restoreProxyPosition(binding)
            }
        }
    }

    private fun ancestor(view: View, name: String): View? {
        var node: View? = view
        while (node != null) {
            if (node.javaClass.name == name) return node
            node = node.parent as? View
        }
        return null
    }

    private val panelMatrix = Matrix()
    private val inversePanelMatrix = Matrix()
    private val panelPoint = FloatArray(2)

    private fun screenAnchor(view: View): DuoPanelPoint {
        panelMatrix.reset()
        view.transformMatrixToGlobal(panelMatrix)
        panelPoint[0] = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) 0f else view.width.toFloat()
        panelPoint[1] = view.height / 2f
        panelMatrix.mapPoints(panelPoint)
        return DuoPanelPoint(panelPoint[0], panelPoint[1])
    }

    private fun screenVector(view: View, x: Float, y: Float): DuoPanelPoint {
        panelMatrix.reset()
        (view.parent as? View)?.transformMatrixToGlobal(panelMatrix)
        panelPoint[0] = x; panelPoint[1] = y
        panelMatrix.mapVectors(panelPoint)
        return DuoPanelPoint(panelPoint[0], panelPoint[1])
    }

    private fun restoreProxyPosition(binding: Binding) {
        binding.proxyRoot?.let { root ->
            root.translationX = binding.proxyX.restore(root.translationX)
            root.translationY = binding.proxyY.restore(root.translationY)
        }
        binding.proxyRoot = null
    }

    private fun positionProxy(binding: Binding, root: View, home: View, target: View, expandedRoot: View) {
        if (binding.proxyRoot !== root) restoreProxyPosition(binding)
        val translated = screenAnchor(target)
        val translation = screenVector(expandedRoot, expandedRoot.translationX, expandedRoot.translationY)
        val endpoint = DuoPanelPoint(translated.x - translation.x, translated.y - translation.y)
        val stretch = screenVector(expandedRoot, 0f, -panelStretchHeight * (1f - panelProgress))
        val desired = DuoPanelGeometry.position(screenAnchor(home), endpoint, panelProgress, stretch)
        val actual = screenAnchor(binding.view)
        panelMatrix.reset()
        (root.parent as? View)?.transformMatrixToGlobal(panelMatrix)
        if (!panelMatrix.invert(inversePanelMatrix)) { restoreProxyPosition(binding); return }
        panelPoint[0] = desired.x - actual.x; panelPoint[1] = desired.y - actual.y
        inversePanelMatrix.mapVectors(panelPoint)
        binding.proxyRoot = root
        root.translationX = binding.proxyX.apply(root.translationX, panelPoint[0])
        root.translationY = binding.proxyY.apply(root.translationY, panelPoint[1])
    }

    private fun hideExpandedDots(binding: Binding): Boolean = binding.surface != DuoSurface.HOME &&
        expandedStyle == DuoExpandedStyle.KEEP_DUO &&
        ControlCenterHeaderHooker.supportsCompactLayout(binding.battery) &&
        Preferences.getBoolean(Preferences.KEY_CC_HIDE_DATE, false) &&
        Preferences.getBoolean(Preferences.KEY_CC_CARRIER_TWO_LINE, false)

    private fun clearSignalMotions(binding: Binding) {
        if (binding.dotsInMotion) {
            binding.dotsInMotion = false
            binding.view.icon.hideSignalDots = hideExpandedDots(binding)
            binding.view.invalidate()
        }
        binding.signalMotions.values.forEach { it.clear() }
        binding.signalMotions.clear()
        binding.signalTargets.values.forEach { ControlCenterCarrierBlockHooker.releaseDuoTarget(it, binding) }
        binding.signalTargets.clear()
        if (binding.view.icon.hiddenSignalRows.isNotEmpty()) {
            binding.view.icon.hiddenSignalRows = emptySet()
            binding.view.invalidate()
        }
    }

    private fun updateSignalMotions(binding: Binding, home: Binding, expanded: Binding, root: ViewGroup?) {
        val content = home.view.icon.content
        val wifi = content?.wifiLevel != null
        val picture = if (wifi) home.view.icon.signalDotsPicture() else home.view.icon.cellularSignalPicture
        if (root == null || content == null || picture == null || content.airplaneMode) {
            clearSignalMotions(binding); return
        }
        val used = HashSet<Int>()
        val hidden = HashSet<Int>()
        val rows = if (wifi) content.activeDataSubId?.let { id ->
            listOf(DuoSignalRow(id, slotIndices[id] ?: -1, if (content.noService) -1 else content.mobileLevel))
        }.orEmpty() else content.cellularSignalRows
        var dotsReady = false
        rows.forEachIndexed { index, row ->
            val carrier = ControlCenterCarrierBlockHooker.signalHandoverTarget(expanded.icons, row.subId)
            val target = carrier ?: nativeSignalView(expanded, row.subId) ?: return@forEachIndexed
            val hasBounds = if (wifi) home.view.icon.signalDotsBounds(binding.signalPictureCrop, binding.signalSourceBounds)
                else home.view.icon.signalRowBounds(index, binding.signalPictureCrop, binding.signalSourceBounds)
            if (!hasBounds) return@forEachIndexed
            if (binding.signalTargets[row.subId] !== target) {
                binding.signalMotions.remove(row.subId)?.clear()
                binding.signalTargets.remove(row.subId)?.let { ControlCenterCarrierBlockHooker.releaseDuoTarget(it, binding) }
                if (carrier != null && !ControlCenterCarrierBlockHooker.acquireDuoTarget(carrier, binding)) return@forEachIndexed
                binding.signalTargets[row.subId] = target
            }
            val motion = binding.signalMotions.getOrPut(row.subId) { SignalRowMotion() }
            val destinationPicture = if (wifi) loadCellularSignalAssets(binding.battery.context)?.let {
                IconSvgRenderer.signalPicture(it.single, listOf(row.level))
            } else null
            if (motion.update(root, home.view, binding.signalSourceBounds, target, picture,
                    binding.signalPictureCrop, binding.view.icon.foreground, panelProgress, destinationPicture)) {
                if (wifi) dotsReady = true else hidden += index
            }
            used += row.subId
        }
        binding.signalMotions.keys.toList().filter { it !in used }.forEach { subId ->
            binding.signalMotions.remove(subId)?.clear()
            binding.signalTargets.remove(subId)?.let { ControlCenterCarrierBlockHooker.releaseDuoTarget(it, binding) }
        }
        if (binding.dotsInMotion != dotsReady) {
            binding.dotsInMotion = dotsReady
            binding.view.icon.hideSignalDots = dotsReady || hideExpandedDots(binding)
            binding.view.invalidate()
        }
        if (binding.view.icon.hiddenSignalRows != hidden) {
            binding.view.icon.hiddenSignalRows = hidden
            binding.view.invalidate()
        }
    }

    private fun nativeSignalView(binding: Binding, subId: Int): View? {
        val root = binding.icons as? ViewGroup ?: return null
        val id = binding.networkIds["mobile_signal"]?.takeIf { it != 0 } ?: return null
        fun find(group: ViewGroup): View? {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (read(child, "subId") == subId) {
                    return child.findViewById<View>(id)?.takeIf { it.isVisible && it.width > 0 }
                }
                if (child is ViewGroup) find(child)?.let { return it }
            }
            return null
        }
        return find(root)
    }

    private fun clearPanelMotion(binding: Binding) {
        binding.networkMotionSettle?.let(main::removeCallbacks)
        binding.networkMotionSettle = null
        binding.networkMotionProgress = 0f
        binding.panelMotion.clear()
        binding.carrierTarget?.let { ControlCenterCarrierBlockHooker.releaseDuoTarget(it, binding) }
        binding.carrierTarget = null
        if (binding.view.icon.hideNetwork) {
            binding.view.icon.hideNetwork = false
            binding.view.invalidate()
        }
    }

    fun hasActiveProxy(root: View): Boolean = enabled && bindings.values.any {
        it.active && it.surface == DuoSurface.COLLAPSED_PROXY &&
            ancestor(it.view, "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons") === root
    }

    private fun updatePanelBinding(binding: Binding) {
        if (binding.surface != DuoSurface.COLLAPSED_PROXY) return
        fun clearPosition() { clearPanelMotion(binding); clearSignalMotions(binding); restoreProxyPosition(binding) }
        if (ControlCenterCarrierBlockHooker.isShadeSwitching()) {
            clearPosition()
            return
        }
        // The host just switched between full shade pages. A later expansion=1 callback must not
        // start the six-frame home-to-carrier settle from the cleared proxy's progress=0.
        if (shadeSwitchSettledToControlCenter && panelProgress >= 0.999f) return
        if (!binding.active || !panelVisible) { clearPosition(); return }
        val proxyRoot = ancestor(binding.view,
            "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons")
            ?: run { clearPosition(); return }
        val home = bindings.values.firstOrNull { it.active && it.surface == DuoSurface.HOME &&
            it.view.isLaidOut && it.view.display?.displayId == binding.view.display?.displayId }
        val expanded = bindings.values.firstOrNull { it.surface == DuoSurface.EXPANDED &&
            it.parent.rootView === binding.parent.rootView && ancestor(it.battery,
                "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon") != null }
        if (home == null || expanded == null || binding.view.width <= 0) { clearPosition(); return }
        val target = if (expanded.active) expanded.view else expanded.battery
        if (!target.isLaidOut || target.width <= 0) { clearPosition(); return }
        val expandedRoot = ancestor(target,
            "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon")
            ?: run { clearPosition(); return }
        positionProxy(binding, proxyRoot, home.view, target, expandedRoot)
        updateSignalMotions(binding, home, expanded, proxyRoot.rootView as? ViewGroup)
        val content = binding.view.icon.content
        if (content == null || content.airplaneMode) {
            clearPanelMotion(binding); return
        }
        val wifi = content.wifiLevel != null
        val destination = DuoPolicy.networkDestination(
            expandedStyle,
            ControlCenterCarrierBlockHooker.ownsNetwork(expanded.icons, wifi)
        )
        val networkTarget = when (destination) {
            DuoNetworkDestination.NONE -> null
            DuoNetworkDestination.CARRIER -> ControlCenterCarrierBlockHooker.duoHandoverTarget(
                expanded.icons, wifi, mobile.activeDataSubId)
            DuoNetworkDestination.NATIVE -> nativeNetworkView(expanded, wifi)
        }
        val root = proxyRoot.rootView as? ViewGroup
        if (networkTarget == null || root == null) { clearPanelMotion(binding); return }
        if (panelProgress >= 1f && binding.networkMotionProgress >= 1f) return
        val carrierTarget = networkTarget.takeIf { destination == DuoNetworkDestination.CARRIER }
        if (binding.carrierTarget !== carrierTarget) clearPanelMotion(binding)
        if (carrierTarget != null) {
            if (!ControlCenterCarrierBlockHooker.acquireDuoTarget(carrierTarget, binding)) {
                clearPanelMotion(binding); return
            }
            binding.carrierTarget = carrierTarget
        }
        if (panelProgress >= 1f) {
            settlePanelMotion(binding, root, home.view, networkTarget, content,
                binding.view.icon.foreground)
            return
        }
        binding.networkMotionSettle?.let(main::removeCallbacks)
        binding.networkMotionSettle = null
        binding.networkMotionProgress = panelProgress
        val hidden = binding.panelMotion.update(root, home.view, networkTarget, content,
            binding.view.icon.foreground, panelProgress, small5GaEnabled, componentSizes)
        if (binding.view.icon.hideNetwork != hidden) {
            binding.view.icon.hideNetwork = hidden
            binding.view.invalidate()
        }
    }

    /** Finish one-step panel opens with the same short travel used while the user drags. */
    private fun settlePanelMotion(
        binding: Binding,
        root: ViewGroup,
        source: View,
        target: View,
        content: DuoContent,
        color: Int
    ) {
        if (binding.networkMotionSettle != null) return
        val from = binding.networkMotionProgress.coerceIn(0f, 1f)
        if (from >= 0.95f) {
            clearPanelMotion(binding)
            binding.networkMotionProgress = 1f
            return
        }
        val steps = (1..6).map { step -> from + (1f - from) * step / 6f }
        var index = 0
        val runnable = object : Runnable {
            override fun run() {
                if (binding.networkMotionSettle !== this) return
                if (!enabled || !panelVisible || bindings[binding.battery] !== binding) {
                    clearPanelMotion(binding)
                    return
                }
                val next = steps[index++]
                binding.networkMotionProgress = next
                val updated = runCatching {
                    val hidden = binding.panelMotion.update(root, source, target, content, color,
                        next, small5GaEnabled, componentSizes)
                    if (binding.view.icon.hideNetwork != hidden) {
                        binding.view.icon.hideNetwork = hidden
                        binding.view.invalidate()
                    }
                }
                if (updated.isFailure) {
                    val error = updated.exceptionOrNull()
                    DebugLog.w(TAG, "Duo panel hand-over settle failed", error)
                    clearPanelMotion(binding)
                    binding.networkMotionProgress = 1f
                    return
                }
                if (index >= steps.size) {
                    clearPanelMotion(binding)
                    binding.networkMotionProgress = 1f
                } else {
                    main.postDelayed(this, 24L)
                }
            }
        }
        binding.networkMotionSettle = runnable
        main.postDelayed(runnable, 24L)
    }

    private fun nativeNetworkView(binding: Binding, wifi: Boolean): View? {
        fun byId(root: View, name: String): View? = binding.networkIds[name]?.takeIf { it != 0 }
            ?.let { root.findViewById<View>(it) }?.takeIf { it.isVisible && it.width > 0 }
        if (wifi) return byId(binding.icons, "wifi_signal")
        val group = binding.icons as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.isVisible && read(child, "subId") == mobile.activeDataSubId)
                return byId(child, "mobile_type")
        }
        return null
    }

    /** Follow host Folme transforms without creating another animator or cancelling frames. */
    private fun privacyOffset(binding: Binding, state: String?): Float {
        if (binding.surface != DuoSurface.HOME || !DuoPrivacyGeometry.isTransition(state)) return 0f
        var chip = binding.privacyView
        if (chip == null || !chip.isAttachedToWindow) {
            chip = if (binding.privacyId != 0) binding.parent.findViewById(binding.privacyId) else null
            binding.privacyView = chip
        }
        chip ?: return 0f
        if (chip.width <= 0 || chip.height <= 0) return 0f
        val rect = binding.privacyRect
        rect.set(0f, 0f, chip.width.toFloat(), chip.height.toFloat())
        var node: View = chip
        repeat(16) {
            if (node.visibility != View.VISIBLE || node.alpha <= 0.01f) return 0f
            node.matrix.mapRect(rect)
            rect.offset(node.left.toFloat(), node.top.toFloat())
            val ancestor = node.parent as? View ?: return 0f
            rect.offset(-ancestor.scrollX.toFloat(), -ancestor.scrollY.toFloat())
            if (ancestor === binding.parent) {
                return DuoPrivacyGeometry.offset(binding.view.left.toFloat(), binding.view.right.toFloat(),
                    rect.left, rect.right, 4f * chip.resources.displayMetrics.density,
                    binding.parent.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            }
            node = ancestor
        }
        return 0f
    }

    private fun setPrivacyInset(binding: Binding, inset: Int) {
        if (binding.privacyInset == inset) return
        val icons = binding.icons
        val base = (icons.paddingEnd - binding.privacyInset).coerceAtLeast(0)
        binding.privacyInset = inset
        icons.setPaddingRelative(icons.paddingStart, icons.paddingTop, base + inset, icons.paddingBottom)
    }

    /** Snapshot the host's own airplane-slot policy before Duo changes anything. */
    private fun captureAirplaneMaskBaseline(binding: Binding) {
        if (binding.hostIgnoredAirplane != null) return
        val ignored = ignoredSlots(binding.icons) ?: return
        binding.hostIgnoredAirplane = ignored.any { it == AIRPLANE_SLOT }
    }

    /**
     * Adds/removes only Duo's native-airplane suppression.  The baseline bit prevents restore from
     * exposing an airplane slot that was already hidden by Icon Tuner or the host configuration.
     */
    private fun setNativeAirplaneMasked(binding: Binding, masked: Boolean) {
        captureAirplaneMaskBaseline(binding)
        val ignored = ignoredSlots(binding.icons) ?: return
        val hostAlreadyIgnored = binding.hostIgnoredAirplane == true
        var changed = false
        if (masked) {
            if (ignored.none { it == AIRPLANE_SLOT }) {
                ignored.add(AIRPLANE_SLOT)
                changed = true
            }
        } else if (!hostAlreadyIgnored) {
            var index = ignored.size - 1
            while (index >= 0) {
                if (ignored[index] == AIRPLANE_SLOT) {
                    ignored.removeAt(index)
                    changed = true
                }
                index--
            }
        }
        if (changed) {
            binding.icons.requestLayout()
            binding.icons.invalidate()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ignoredSlots(view: View): MutableList<Any?>? =
        runCatching { field(view.javaClass, "ignoredSlots")?.get(view) as? MutableList<Any?> }.getOrNull()

    private fun foreground(binding: Binding): Int {
        val battery = binding.battery
        val areas = read(battery, "mTintAreas")
        if (read(battery, "mUseTint") == true) {
            return (tintMethod?.invoke(null, areas, binding.view, read(battery, "mTintColor")) as? Int)
                ?: error("local tint unavailable")
        }
        val intensity = darkMethod?.invoke(null, areas, binding.view, read(battery, "mDarkIntensity")) as? Float
            ?: error("local dark intensity unavailable")
        return (read(battery, if (intensity > 0f) "mDarkColor" else "mLightColor") as? Int) ?: Color.WHITE
    }

    private fun restore(binding: Binding) {
        clearSignalMotions(binding)
        clearPanelMotion(binding)
        restoreProxyPosition(binding)
        binding.expiry?.let(main::removeCallbacks)
        binding.expiry = null
        binding.missingSince = -1L
        binding.view.finishTransition()
        setPrivacyInset(binding, 0)
        binding.view.translationX = 0f
        binding.view.setBatteryOnly(false, null)
        binding.view.setPercent(false, false, null, null, null)
        binding.view.icon.cellularSignalPicture = null
        IconPositionHooker.setDuoMask(binding.icons, false)
        setNativeAirplaneMasked(binding, false)
        if (!binding.active) return
        binding.active = false
        runCatching {
            if (batteryLayoutField?.get(binding.parent) === binding.view) batteryLayoutField?.set(binding.parent, binding.battery)
            field(binding.parent.javaClass, "mIsHideBattery")?.setBoolean(binding.parent, binding.hostHideBattery)
            binding.view.visibility = View.GONE
            visibilityMethod?.invoke(binding.battery)
            binding.parent.requestLayout()
        }.onFailure { DebugLog.w(TAG, "native battery restore failed", it) }
    }

    private fun detach(battery: View) {
        val binding = bindings.remove(battery) ?: return
        binding.reconciling = true
        restore(binding)
        binding.preDraw?.let { listener -> binding.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
        // Removal during the parent's detach traversal can skip sibling detach callbacks.
        main.post { if (binding.view.parent === binding.parent) binding.parent.removeView(binding.view) }
    }

    private fun hookWifi() {
        val vm = WIFI_VM.toClassOrNull() ?: return
        vm.declaredConstructors.filter { ctor ->
            ctor.parameterCount == 6 && ctor.parameterTypes[3].name.endsWith(".WifiInteractorImpl") &&
                ctor.parameterTypes[4].name == IconTunerFlows.hostClassName("kotlinx.coroutines", "CoroutineScope")
        }.forEach { ctor -> ctor.hook { after { param ->
            val interactor = param.args.getOrNull(3) ?: return@after
            val scope = param.args.getOrNull(4) ?: return@after
            val context = param.args.getOrNull(1) as? Context ?: return@after
            val token = epoch
            main.post { if (enabled && token == epoch) guarded { bindWifi(scope, interactor, context) } }
        } } }
    }

    private fun bindWifi(scope: Any, interactor: Any, context: Context) {
        if (wifiInteractor === interactor && wifiHandles.isNotEmpty()) return
        wifiHandles.forEach { it.cancel() }; wifiHandles.clear()
        wifiScope = scope; wifiInteractor = interactor; wifiContext = context.applicationContext
        network = network.copy(wifiLevel = null, wifiDefault = null)
        ensureConnectivity(context)
        val token = epoch
        HostFlowCollector.collect(scope, read(interactor, "isDefault"), { value ->
            network = network.copy(wifiDefault = value as? Boolean)
            refresh()
        }, { enabled && token == epoch })?.let(wifiHandles::add)
        val wifiMax = (context.getSystemService(WifiManager::class.java)?.maxSignalLevel ?: 4).coerceAtLeast(1)
        HostFlowCollector.collect(scope, read(interactor, "wifiNetwork"), { value ->
            val level = if (value?.javaClass?.name?.endsWith("WifiNetworkModel\$Active") == true)
                (read(value, "level") as? Int)?.takeIf { it in 0..wifiMax }?.let {
                    (it * 4f / wifiMax).roundToInt().coerceIn(0, 4)
                } else null
            network = network.copy(wifiLevel = level)
            refresh()
        }, { enabled && token == epoch })?.let(wifiHandles::add)
    }

    private fun ensureConnectivity(context: Context) {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val token = epoch
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) {
                if (!enabled || token != epoch) return
                currentNetwork = value
                // Capabilities follow onAvailable. Keep the previous representation for this
                // bounded handover instead of exposing the native cluster between callbacks.
                networkPending?.let(main::removeCallbacks)
                networkPending = Runnable {
                    if (enabled && token == epoch && currentNetwork == value) {
                        network = network.copy(transport = DuoTransport.UNKNOWN, validated = false)
                        refresh()
                    }
                }.also { main.postDelayed(it, 200L) }
            }
            override fun onCapabilitiesChanged(value: Network, caps: NetworkCapabilities) {
                if (!enabled || token != epoch || currentNetwork != value) return
                networkPending?.let(main::removeCallbacks)
                networkPending = null
                network = network.copy(transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DuoTransport.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DuoTransport.CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> DuoTransport.VPN
                    else -> DuoTransport.OTHER
                }, validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                refresh()
            }
            override fun onLost(value: Network) {
                if (!enabled || token != epoch || currentNetwork != value) return
                networkPending?.let(main::removeCallbacks)
                networkPending = null
                currentNetwork = null
                network = network.copy(transport = DuoTransport.NONE, validated = false)
                refresh()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(listener, main) }.onSuccess {
            connectivity = manager; callback = listener
        }.onFailure { DebugLog.w(TAG, "default network callback unavailable", it) }
    }

    private fun refresh() { bindings.values.toList().forEach { guarded { reconcile(it) } } }
    private fun field(type: Class<*>, name: String): Field? {
        val key = type to name
        if (fields.containsKey(key)) return fields[key]
        return generateSequence(type) { it.superclass }.mapNotNull { clazz ->
            runCatching { clazz.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        }.firstOrNull().also { fields[key] = it }
    }
    private fun read(owner: Any, name: String): Any? = field(owner.javaClass, name)?.get(owner)
    private inline fun guarded(action: () -> Unit) { runCatching(action).onFailure { DebugLog.w(TAG, "host callback failed", it) } }

    private fun <T> onMainBlocking(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Result<T>? = null
        main.post { try { result = runCatching(action) } finally { latch.countDown() } }
        check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Duo main-thread cleanup timed out" }
        return checkNotNull(result).getOrThrow()
    }
    override fun saveHotReloadState(): Any? = onMainBlocking {
        listOf(wifiScope, wifiInteractor, wifiContext, bindings.keys.toList(),
            panelProgress, panelVisible, panelStretchHeight, shadeSwitchSettledToControlCenter)
    }
    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? List<*> ?: return
        val token = epoch
        main.post { if (enabled && token == epoch) guarded {
            panelProgress = saved.getOrNull(4) as? Float ?: 0f
            panelVisible = saved.getOrNull(5) as? Boolean ?: false
            panelStretchHeight = saved.getOrNull(6) as? Float ?: 0f
            shadeSwitchSettledToControlCenter = saved.getOrNull(7) as? Boolean ?: false
            val scope = saved.getOrNull(0); val interactor = saved.getOrNull(1); val context = saved.getOrNull(2) as? Context
            if (scope != null && interactor != null && context != null) bindWifi(scope, interactor, context)
            (saved.getOrNull(3) as? List<*>)?.filterIsInstance<View>()?.filter { it.isAttachedToWindow }?.forEach(::attach)
        } }
    }
    internal fun recoverExistingViews(views: List<View>, progress: Float?, visible: Boolean?, stretch: Float?) {
        if (!enabled) return
        progress?.let { panelProgress = it.coerceIn(0f, 1f) }
        visible?.let { panelVisible = it }
        stretch?.let { panelStretchHeight = it }
        views.filter { it.javaClass.name == BATTERY && it.isAttachedToWindow }.forEach { battery ->
            guarded { attach(battery) }
        }
        refresh()
        DebugLog.i(TAG, "hot reload Duo batteries=${bindings.size}")
    }

    internal fun recoverWifi(scope: Any, interactor: Any, context: Context) {
        if (enabled) bindWifi(scope, interactor, context)
    }

    override fun onPrepareHotReload() {
        enabled = false
        epoch++
        // Finish native restoration before the lifecycle removes this generation's hooks.
        onMainBlocking {
            bindings.keys.toList().forEach(::detach)
            callback?.let { listener -> runCatching { connectivity?.unregisterNetworkCallback(listener) } }
            networkPending?.let(main::removeCallbacks)
            networkPending = null
            callback = null; connectivity = null; currentNetwork = null
            wifiHandles.forEach { it.cancel() }; wifiHandles.clear()
            wifiScope = null; wifiInteractor = null; wifiContext = null
            small5GaEnabled = false
            cellularSignalAssets = null
            slotIndices = emptyMap()
            mobile = MobileSignalState(); network = DuoNetwork()
            panelProgress = 0f; panelVisible = false; panelStretchHeight = 0f
            shadeSwitchSuspended = false; shadeSwitchSettledToControlCenter = false
        }
    }
}
