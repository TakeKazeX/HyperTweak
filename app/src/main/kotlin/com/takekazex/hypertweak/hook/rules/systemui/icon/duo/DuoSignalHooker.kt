@file:Suppress("StaticFieldLeak")
package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.content.Context
import androidx.core.view.isVisible
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HostFlowCollector
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterCarrierBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconPositionHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalState
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.PlatformLevel
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
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
    private var panelProgress = 0f
    private var panelVisible = false
    private var panelStretchHeight = 0f
    private var expandedStyle = DuoExpandedStyle.RESTORE_NATIVE
    private var iconSizeDp = DuoLayout.DEFAULT_ICON_SIZE_DP.toFloat()
    private var mobile = MobileSignalState()
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
        var carrierTarget: View? = null
        var proxyRoot: View? = null
        val proxyX = DuoOwnedTranslation()
        val proxyY = DuoOwnedTranslation()
        val networkIds = listOf("wifi_signal", "mobile_type", "mobile_signal").associateWith {
            battery.resources.getIdentifier(it, "id", battery.context.packageName)
        }
        var missingSince = -1L
        var expiry: Runnable? = null
        var active = false
        var failed = false
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
        private val previousIcon = DuoDrawable()
        private var blend = 1f
        private var animator: ValueAnimator? = null

        fun submit(content: DuoContent) {
            val old = icon.content
            if (old == content) return
            icon.content = content
            // Signal strength and charge updates remain immediate; only a change of network
            // representation crossfades. No layout or host visibility mutations in the animator.
            if (old != null && ((old.wifiLevel != null) != (content.wifiLevel != null) ||
                    old.networkLabel != content.networkLabel || old.airplaneMode != content.airplaneMode)) {
                animator?.cancel()
                previousIcon.content = old
                blend = if (ValueAnimator.areAnimatorsEnabled()) 0f else 1f
                if (blend == 0f) animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 160L
                    interpolator = LinearInterpolator()
                    addUpdateListener { blend = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }

        fun finishTransition() {
            animator?.cancel()
            animator = null
            blend = 1f
            previousIcon.content = null
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
                resolveSize(maxOf(desired + paddingStart + paddingEnd, suggestedMinimumWidth), widthMeasureSpec),
                resolveSize(maxOf(desired, suggestedMinimumHeight), heightMeasureSpec)
            )
        }
        override fun onDraw(canvas: Canvas) {
            icon.setBounds(paddingLeft, 0, width - paddingRight, height)
            runCatching {
                if (blend < 1f) {
                    previousIcon.hideNetwork = icon.hideNetwork
                    previousIcon.bounds = icon.bounds
                    previousIcon.foreground = icon.foreground
                    previousIcon.alpha = ((1f - blend) * 255).toInt()
                    previousIcon.draw(canvas)
                }
                icon.alpha = (blend * 255).toInt()
                icon.draw(canvas)
            }.onFailure { drawFailed?.invoke() }
        }
    }

    override fun onHook() {
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
        if (mobile == next) return
        mobile = next
        refresh()
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
            view.paddingStart + view.paddingEnd,
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
                DuoPolicy.content(DuoBattery(percent, charging, powerSave), mobile, network) else null
            val content = presentationContent(binding, freshContent)
            val privacyState = read(binding.parent, "mPrivacyState")?.toString()
            val privacyShowing = DuoPrivacyGeometry.isTransition(privacyState)
            val visible = (read(battery, "mHomeBlock") == false || privacyShowing) && read(battery, "mMinimalism") == false &&
                read(battery, "mIsAodAnimate") != true
            if (!enabled || binding.failed || content == null || !visible || battery.parent !== binding.parent ||
                !DuoPolicy.replaces(surface(battery), expandedStyle,
                    ControlCenterCarrierBlockHooker.ownsNetworkContainer(binding.icons))) {
                restore(binding)
                return
            }
            binding.view.submit(content)
            binding.view.icon.foreground = foreground(binding)
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
                if (!IconPositionHooker.setDuoMask(binding.icons, true)) {
                    binding.failed = true
                    restore(binding)
                } else {
                    // Native SystemUI publishes its own `airplane` status-bar slot.  Duo renders
                    // that state inside the battery ring, so keep the native slot ignored while
                    // the replacement owns this cluster; otherwise two planes appear side by side.
                    setNativeAirplaneMasked(binding, true)
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
            before { guarded { bindings.values.toList().forEach(::restoreProxyPosition) } }
            after { param -> guarded {
                val progress = param.args.getOrNull(0) as? Float ?: return@guarded
                if (progress !in 0f..1f) return@guarded
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
                panelVisible = param.args.getOrNull(0) == true
                if (!panelVisible) { panelProgress = 0f; panelStretchHeight = 0f }
                bindings.values.toList().forEach(::updatePanelBinding)
            } }
        }
        type.findMethodOrNull { name("onAppearanceChanged"); paramCount(2) }?.hook {
            after { param -> guarded {
                if (param.args.getOrNull(0) == true) panelVisible = true
                // Appearance can swap rows before the finger is released. Keep the actual fraction.
                bindings.values.toList().forEach(::updatePanelBinding)
            } }
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

    private fun clearPanelMotion(binding: Binding) {
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
        fun clearPosition() { clearPanelMotion(binding); restoreProxyPosition(binding) }
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
        val content = binding.view.icon.content
        if (content == null || content.airplaneMode || panelProgress <= 0f || panelProgress >= 1f) {
            clearPanelMotion(binding); return
        }
        val wifi = content.wifiLevel != null
        val destination = DuoPolicy.networkDestination(expandedStyle,
            ControlCenterCarrierBlockHooker.ownsNetworkContainer(expanded.icons),
            ControlCenterCarrierBlockHooker.ownsNetwork(expanded.icons, wifi))
        val networkTarget = when (destination) {
            DuoNetworkDestination.NONE -> null
            DuoNetworkDestination.CARRIER -> ControlCenterCarrierBlockHooker.duoHandoverTarget(
                expanded.icons, wifi, mobile.activeDataSubId)
            DuoNetworkDestination.NATIVE -> nativeNetworkView(expanded, wifi)
        }
        val root = proxyRoot.rootView as? ViewGroup
        if (networkTarget == null || root == null) { clearPanelMotion(binding); return }
        val carrierTarget = networkTarget.takeIf { destination == DuoNetworkDestination.CARRIER }
        if (binding.carrierTarget !== carrierTarget) clearPanelMotion(binding)
        if (carrierTarget != null) {
            if (!ControlCenterCarrierBlockHooker.acquireDuoTarget(carrierTarget, binding)) {
                clearPanelMotion(binding); return
            }
            binding.carrierTarget = carrierTarget
        }
        val hidden = binding.panelMotion.update(root, home.view, networkTarget, content,
            binding.view.icon.foreground, panelProgress)
        if (binding.view.icon.hideNetwork != hidden) {
            binding.view.icon.hideNetwork = hidden
            binding.view.invalidate()
        }
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
        clearPanelMotion(binding)
        restoreProxyPosition(binding)
        binding.expiry?.let(main::removeCallbacks)
        binding.expiry = null
        binding.missingSince = -1L
        binding.view.finishTransition()
        setPrivacyInset(binding, 0)
        binding.view.translationX = 0f
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
        listOf(wifiScope, wifiInteractor, wifiContext, bindings.keys.toList())
    }
    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? List<*> ?: return
        val token = epoch
        main.post { if (enabled && token == epoch) guarded {
            val scope = saved.getOrNull(0); val interactor = saved.getOrNull(1); val context = saved.getOrNull(2) as? Context
            if (scope != null && interactor != null && context != null) bindWifi(scope, interactor, context)
            (saved.getOrNull(3) as? List<*>)?.filterIsInstance<View>()?.filter { it.isAttachedToWindow }?.forEach(::attach)
        } }
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
            mobile = MobileSignalState(); network = DuoNetwork()
            panelProgress = 0f; panelVisible = false; panelStretchHeight = 0f
        }
    }
}
