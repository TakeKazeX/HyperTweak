@file:Suppress("StaticFieldLeak")
package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.presentation.icon.duo.NativeWifiDrawable

import com.takekazex.hypertweak.presentation.icon.duo.DuoBattery
import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import com.takekazex.hypertweak.presentation.icon.duo.DuoExpandedStyle
import com.takekazex.hypertweak.presentation.icon.duo.DuoLayout
import com.takekazex.hypertweak.presentation.icon.duo.DuoPolicy
import com.takekazex.hypertweak.presentation.icon.duo.DuoSizes
import com.takekazex.hypertweak.presentation.icon.duo.DuoSurface

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.aod.AodIconSettings
import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.ControlCenterCarrierBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconPositionHooker
import com.takekazex.hypertweak.presentation.icon.IconSvgRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.hook.rules.systemui.icon.SignalHandoverEvents
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
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
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconMobileRowStyle by hookSetting(0) { Preferences.getInt(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE, 0) }
    private val settingIconCellularTypeSmall5ga by hookSetting(false) {
        Preferences.getBoolean(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA, false)
    }
    private val settingIconDuoEnabled by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_DUO_ENABLED, false) }
    private val settingIconDuoExpanded by hookSetting(1) { Preferences.getInt(IconSettingsKeys.KEY_ICON_DUO_EXPANDED, 1) }
    private val settingIconDuoSize by hookSetting(DuoLayout.DEFAULT_ICON_SIZE_DP) {
        Preferences.getInt(IconSettingsKeys.KEY_ICON_DUO_SIZE, DuoLayout.DEFAULT_ICON_SIZE_DP)
    }
    private val settingIconDuoRingScale by hookSetting(100) { Preferences.getInt(IconSettingsKeys.KEY_ICON_DUO_RING_SCALE, 100) }
    private val settingIconDuoWifiScale by hookSetting(100) { Preferences.getInt(IconSettingsKeys.KEY_ICON_DUO_WIFI_SCALE, 100) }
    private val settingIconDuoCellularScale by hookSetting(100) { Preferences.getInt(IconSettingsKeys.KEY_ICON_DUO_CELLULAR_SCALE, 100) }
    private val settingIconDuoTypeScale by hookSetting(100) { Preferences.getInt(IconSettingsKeys.KEY_ICON_DUO_TYPE_SCALE, 100) }
    private val settingIconDuoDotsScale by hookSetting(100) { Preferences.getInt(IconSettingsKeys.KEY_ICON_DUO_DOTS_SCALE, 100) }
    private val settingIconDuoAirplaneScale by hookSetting(100) { Preferences.getInt(IconSettingsKeys.KEY_ICON_DUO_AIRPLANE_SCALE, 100) }
    private val settingIconDuoBatteryScale by hookSetting(100) { Preferences.getInt(IconSettingsKeys.KEY_ICON_DUO_BATTERY_SCALE, 100) }
    private val settingIconDuoPercentScale by hookSetting(100) { Preferences.getInt(IconSettingsKeys.KEY_ICON_DUO_PERCENT_SCALE, 100) }
    private val settingCcBatteryPercentLeft by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_LEFT, false)
    }

    init {
        hostRecovery(HostRecoveryPhase.HANDOVER, icons = true) {
            recoverExistingViews(it.views, it.progress, it.visible, it.stretch)
        }
        hostRecovery(HostRecoveryPhase.NETWORK, icons = true) {
            if (it.coroutineScope != null && it.wifiInteractor != null) recoverWifi(it.coroutineScope!!, it.wifiInteractor!!, it.context)
        }
    }

    private val signalArtwork = DuoSignalArtwork()
    private val panelHandover = DuoPanelHandover(object : DuoPanelHandover.Host {
        override val panelStretchHeight get() = DuoSignalHooker.panelStretchHeight
        override val panelProgress get() = DuoSignalHooker.panelProgress
        override val panelVisible get() = DuoSignalHooker.panelVisible
        override val expandedStyle get() = DuoSignalHooker.expandedStyle
        override val shadeSwitchSettledToControlCenter get() = DuoSignalHooker.shadeSwitchSettledToControlCenter
        override val small5GaEnabled get() = DuoSignalHooker.small5GaEnabled
        override val componentSizes get() = DuoSignalHooker.componentSizes
        override val enabled get() = DuoSignalHooker.enabled
        override val mobile get() = DuoSignalHooker.mobile
        override fun bindings(): Collection<DuoBinding> = bindings.values
        override fun owns(binding: DuoBinding) = bindings[binding.battery] === binding
        override fun slotIndex(subId: Int) = slotIndices[subId]
        override fun destinationSignal(context: Context, level: Int): Picture? =
            signalArtwork.loadCellularSignalAssets(context)?.let { IconSvgRenderer.signalPicture(it.single, listOf(level)) }
    })
    private val aodController = DuoAodController(this, object : DuoAodController.Host {
        override val enabled get() = DuoSignalHooker.enabled
        override val standardEnabled get() = DuoSignalHooker.standardEnabled
        override val epoch get() = DuoSignalHooker.epoch
        override val batteryLayoutField get() = DuoSignalHooker.batteryLayoutField
        override val visibilityMethod get() = DuoSignalHooker.visibilityMethod
        override fun refresh() = DuoSignalHooker.refresh()
        override fun restore(binding: DuoBinding) = DuoSignalHooker.restore(binding)
        override fun setPrivacyInset(binding: DuoBinding, inset: Int) = DuoSignalHooker.setPrivacyInset(binding, inset)
        override fun setNativeAirplaneMasked(binding: DuoBinding, masked: Boolean) = DuoSignalHooker.setNativeAirplaneMasked(binding, masked)
    })

    private const val TAG = "DuoSignal"
    private const val AIRPLANE_SLOT = "airplane"
    private const val BATTERY = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
    private const val CONTAINER = "com.android.systemui.statusbar.views.MiuiStatusBatteryContainer"
    private const val WIFI_VM = "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel"
    private val main = Handler(Looper.getMainLooper())
    // Only applicationContext is retained, for restoring the host flow after a reload.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private val networkSource = DuoNetworkSource(object : DuoNetworkSource.Host {
        override val enabled get() = DuoSignalHooker.enabled
        override val epoch get() = DuoSignalHooker.epoch
        override fun refresh() = DuoSignalHooker.refresh()
    }, main)
    private val bindings = generationMainResource(IdentityHashMap<View, DuoBinding>()) { it.clear() }
    private var enabled by generationState { false }
    private var standardEnabled by generationState { false }
    @Volatile
    private var epoch = 0
    private var componentSizes by generationState { DuoSizes() }
    private var panelProgress by generationState { 0f }
    private var panelVisible by generationState { false }
    private var panelStretchHeight by generationState { 0f }
    private var shadeSwitchSuspended by generationState { false }
    private var shadeSwitchSettledToControlCenter by generationState { false }
    private var expandedStyle by generationState { DuoExpandedStyle.RESTORE_NATIVE }
    private var iconSizeDp by generationState { DuoLayout.DEFAULT_ICON_SIZE_DP.toFloat() }
    private var small5GaEnabled by generationState { false }
    private var mobile by generationState { MobileSignalState() }
    private var slotIndices: Map<Int, Int> by generationState { emptyMap() }
    /** Live dozing read: `MiuiFullAodManager.isDozing`, resolved once from the running host. */
    private var batteryLayoutField: Field? by generationState { null }
    private var statusIconsField: Field? by generationState { null }
    private var wifiTintResourceMethod: Method? by generationState { null }
    private var visibilityMethod: Method? by generationState { null }
    private var tintMethod: Method? by generationState { null }
    private var darkMethod: Method? by generationState { null }

    private var dataFirst by generationState { false }

    override fun onHook() {
        com.takekazex.hypertweak.hook.rules.systemui.icon.duo.prepareDuoPanelMotionSettings()
        com.takekazex.hypertweak.hook.rules.systemui.icon.duo.prepareDuoSignalArtworkSettings()
        com.takekazex.hypertweak.hook.rules.systemui.icon.duo.prepareDuoPanelHandoverSettings()
        com.takekazex.hypertweak.hook.rules.systemui.icon.duo.prepareDuoIconViewSettings()
        com.takekazex.hypertweak.hook.rules.aod.prepareAodIconSettingsSettings()
        epoch++
        dataFirst = settingIconMobileRowStyle == 1
        small5GaEnabled = settingIconCellularTypeSmall5ga
        standardEnabled = settingIconDuoEnabled
        // The keyguard row is always owned by this hooker: an accepted AOD snapshot refreshes it
        // through [refreshAodSettings], so the first AOD option does not need a SystemUI restart.
        enabled = PlatformLevel.isOs4 && isMainProcess
        if (!enabled) return
        SignalHandoverEvents.shade.bind(this, ::onShadeSwitchStarted, ::onShadeSwitchFinished,
            activeProxy = { it is View && hasActiveProxy(it) },
            ownsAirplane = { it is ViewGroup && ownsAirplane(it) },
            requiresMobile = { requiresMobileState }, mobile = ::onMobileState)
        expandedStyle = if (settingIconDuoExpanded == 0)
            DuoExpandedStyle.KEEP_DUO else DuoExpandedStyle.RESTORE_NATIVE
        iconSizeDp = DuoLayout.safeSizeDp(
            settingIconDuoSize.toFloat()
        )
        componentSizes = DuoSizes(
            ring = DuoSizes.ratio(settingIconDuoRingScale),
            wifi = DuoSizes.ratio(settingIconDuoWifiScale),
            cellular = DuoSizes.ratio(settingIconDuoCellularScale),
            type = DuoSizes.ratio(settingIconDuoTypeScale),
            dots = DuoSizes.ratio(settingIconDuoDotsScale),
            airplane = DuoSizes.ratio(settingIconDuoAirplaneScale),
            battery = DuoSizes.ratio(settingIconDuoBatteryScale),
            percent = DuoSizes.ratio(settingIconDuoPercentScale)
        )
        aodController.resolveDozingSource()
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
        wifiTintResourceMethod = "com.android.systemui.statusbar.MiuiStatusBarIconViewHelper"
            .toClassOrNull()?.declaredMethods?.singleOrNull {
                it.name == "transformResId" && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.returnType == Int::class.javaPrimitiveType &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
            }?.apply { isAccessible = true }
        if (wifiTintResourceMethod == null) {
            DebugLog.hookSkipped(TAG, "native Wi-Fi tint artwork", "resource transformer signature unavailable")
        }
        // No hooks on global View/ImageView draw, visibility or tint methods.
        batteryClass.findMethodOrNull { name("onAttachedToWindow"); paramCount(0) }?.hook {
            after { param -> (param.thisObject as? View)?.let { battery ->
                val token = epoch
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    if (epoch == token && battery.isAttachedToWindow) guarded { attach(battery) }
                } else {
                    main.post { if (epoch == token && battery.isAttachedToWindow) guarded { attach(battery) } }
                }
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
        batteryClass.declaredMethods.singleOrNull {
            it.name == "onMeasure" && it.parameterCount == 2
        }?.let { method ->
            deoptimize(method)
            method.hook { before { param ->
                (param.thisObject as? View)?.let { battery -> guarded {
                    bindings[battery]?.let(aodController::applyFullAodNativeChildren)
                } }
            } }
        }
        batteryClass.declaredMethods.singleOrNull {
            it.name == "updateChargeAndText" && it.parameterCount == 0
        }?.let { method ->
            deoptimize(method)
            method.hook { after { param ->
                (param.thisObject as? View)?.let { battery -> guarded {
                    bindings[battery]?.let(aodController::applyFullAodNativeChildren)
                } }
            } }
        }
        batteryClass.declaredMethods.singleOrNull {
            it.name == "onLayout" && it.parameterCount == 5
        }?.let { method ->
            deoptimize(method)
            method.hook { after { param ->
                (param.thisObject as? View)?.let { battery -> guarded {
                    bindings[battery]?.let(aodController::positionNativeFullAod)
                } }
            } }
        }
        for (name in listOf("onBatteryLevelChanged", "onPowerSaveChanged", "onPerformanceModeChanged", "onChargeStateChanged", "updateLightDarkTint", "updateAll")) {
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
        // The host changes mToLockScreen at the start of this callback, before it animates
        // the battery. Reconcile against that target state in the same frame.
        "com.android.systemui.statusbar.phone.KeyguardStatusBarViewControllerInject".toClassOrNull()
            ?.findMethodOrNull {
                name("animateFullAod")
                parameterTypes(Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            }?.let { method ->
                deoptimize(method)
                method.hook { after { param ->
                    val toLockScreen = param.args.getOrNull(0) as? Boolean ?: true
                    guarded {
                        bindings.values.toList().filter { it.surface == DuoSurface.KEYGUARD }.forEach {
                            aodController.observeHandshake(it, toLockScreen)
                            reconcile(it)
                        }
                    }
                } }
            }
        hookProxyCompensation()
        hookWifi()
        DebugLog.hookRegistered(TAG, "OS4 battery container, expanded=$expandedStyle")
    }

    val requiresMobileState: Boolean get() = enabled && (standardEnabled ||
        AodIconSettings.current().let { it.duo && it.centerSignal })

    /** Reuses the SystemUI Duo renderer inside MIUI AOD's own process and battery container. */
    internal fun createAodDuoView(context: Context): View = DuoIconView(context).apply {
        iconSizeDp = DuoLayout.safeSizeDp(
            settingIconDuoSize.toFloat()
        )
        icon.sizes = DuoSizes(
            ring = DuoSizes.ratio(settingIconDuoRingScale),
            wifi = DuoSizes.ratio(settingIconDuoWifiScale),
            cellular = DuoSizes.ratio(settingIconDuoCellularScale),
            type = DuoSizes.ratio(settingIconDuoTypeScale),
            dots = DuoSizes.ratio(settingIconDuoDotsScale),
            airplane = DuoSizes.ratio(settingIconDuoAirplaneScale),
            battery = DuoSizes.ratio(settingIconDuoBatteryScale),
            percent = DuoSizes.ratio(settingIconDuoPercentScale)
        )
    }

    /** Reuses Duo's single/stacked SVG, Wi-Fi routing, battery ring, and centered-percent layout. */
    internal fun updateAodDuoView(
        context: Context,
        target: View,
        content: DuoContent,
        centerBattery: Boolean,
        batteryDrawable: Drawable?,
        percentVisible: Boolean,
        percentBelow: Boolean,
        percentOnRight: Boolean,
        percentContainer: View?,
        percentValue: TextView?,
        percentFallback: String?,
        foreground: Int
    ) {
        val duoView = target as? DuoIconView ?: return
        duoView.icon.foreground = foreground
        duoView.icon.sizes = DuoSizes(
            ring = DuoSizes.ratio(settingIconDuoRingScale),
            wifi = DuoSizes.ratio(settingIconDuoWifiScale),
            cellular = DuoSizes.ratio(settingIconDuoCellularScale),
            type = DuoSizes.ratio(settingIconDuoTypeScale),
            dots = DuoSizes.ratio(settingIconDuoDotsScale),
            airplane = DuoSizes.ratio(settingIconDuoAirplaneScale),
            battery = DuoSizes.ratio(settingIconDuoBatteryScale),
            percent = DuoSizes.ratio(settingIconDuoPercentScale)
        )
        duoView.layoutDirection = if (percentOnRight) View.LAYOUT_DIRECTION_RTL
            else View.LAYOUT_DIRECTION_LTR
        duoView.setBatteryOnly(centerBattery, batteryDrawable)
        duoView.setPercent(
            enabled = percentVisible,
            below = percentBelow,
            container = percentContainer,
            value = percentValue,
            mark = null,
            fallbackText = percentFallback
        )
        duoView.submit(
            content,
            settingIconCellularTypeSmall5ga,
            signalArtwork.renderAodCellularSignal(context, content)
        )
    }

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

    private fun originalBatteryDrawable(binding: DuoBinding): Drawable? {
        val battery = binding.battery
        val name = if (read(battery, "mBatteryStyle") == 1) "mHollowBatteryIconView" else "mBatteryIconView"
        val source = read(battery, name) as? View ?: return null
        if (binding.surface == DuoSurface.KEYGUARD &&
            binding.keyguardRoot?.let { read(it, "mToLockScreen") } == false &&
            (source.width <= 0 || source.height <= 0)) {
            prepareFullAodBatterySource(source)
        }
        if (source.width <= 0 || source.height <= 0) return null
        return binding.batteryGlyph?.takeIf { it.source === source }
            ?: HostBatteryDrawable(source, listOfNotNull(
                read(source, "textPaint") as? Paint, read(source, "hollowTextPaint") as? Paint
            )).also { binding.batteryGlyph = it }
    }

    private fun prepareFullAodBatterySource(source: View) {
        if (!source.isAttachedToWindow) return
        val resources = source.resources
        val packageName = source.context.packageName
        val heightId = resources.getIdentifier("battery_meter_height", "dimen", packageName)
        val widthId = resources.getIdentifier("battery_meter_width", "dimen", packageName)
        if (heightId == 0 || widthId == 0) return
        runCatching {
            val width = resources.getDimensionPixelSize(widthId)
            val height = resources.getDimensionPixelSize(heightId)
            if (width <= 0 || height <= 0) return
            // The native keyguard battery is GONE from the first full-AOD frame, so Android
            // never measures its icon. Only this child is laid out for the borrowed drawable;
            // the parent remains hidden and the host will lay it out normally on wake.
            source.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            source.layout(0, 0, source.measuredWidth, source.measuredHeight)
            DebugLog.i(TAG, "prepared full-AOD battery source: ${source.width}x${source.height}")
        }.onFailure { DebugLog.w(TAG, "full-AOD battery source layout failed", it) }
    }

    private fun attach(battery: View) {
        if (bindings.containsKey(battery)) return
        val parent = battery.parent as? ViewGroup ?: return
        if (parent.javaClass.name != CONTAINER || batteryLayoutField?.get(parent) !== battery) return
        val surface = surface(battery)
        // Keep dormant expanded bindings too: switching from native to Duo must not require
        // that the already-attached control-center header happens to be reinflated.
        if (surface == DuoSurface.UNSUPPORTED) return
        if (!standardEnabled && surface != DuoSurface.KEYGUARD) return
        val icons = statusIconsField?.get(parent) as? View ?: return
        networkSource.ensureConnectivity(battery.context)
        val binding = DuoBinding(battery, parent, icons, surface,
            if (surface == DuoSurface.KEYGUARD)
                ancestor(battery, "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView") else null,
            panelVisible && panelProgress >= 0.999f)
        binding.hostHideBattery = read(parent, "mIsHideBattery") as? Boolean ?: false
        binding.rowPhase = aodController.phaseFor(binding.keyguardRoot)
        binding.view.iconSizeDp = iconSizeDp
        binding.view.setPaddingRelative((4f * battery.resources.displayMetrics.density).roundToInt(), 0, 0, 0)
        captureAirplaneMaskBaseline(binding)
        bindings[battery] = binding
        binding.view.drawFailed = { _ ->
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
            guarded { reconcile(binding); panelHandover.updatePanelBinding(binding) }
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
                "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView" -> return DuoSurface.KEYGUARD
                "com.android.systemui.qs.MiuiQSHeaderView",
                "com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon" -> return DuoSurface.EXPANDED
                "com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons" -> return DuoSurface.COLLAPSED_PROXY
            }
            node = current.parent
        }
        return DuoSurface.UNSUPPORTED
    }

    /** Use current parent geometry, never the GONE battery's stale portrait coordinates. */
    private fun position(binding: DuoBinding) {
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

    private fun reconcile(binding: DuoBinding) {
        if (binding.reconciling) return
        binding.reconciling = true
        try {
            expandedStyle = if (settingIconDuoExpanded == 0)
                DuoExpandedStyle.KEEP_DUO else DuoExpandedStyle.RESTORE_NATIVE
            val battery = binding.battery
            if (binding.active && batteryLayoutField?.get(binding.parent) !== binding.view) binding.failed = true
            if (!enabled && binding.keyguardRoot != null) {
                aodController.releaseKeyguardRow(binding, AodHostPhase.UNKNOWN)
                return
            }
            val decision = aodController.keyguardDecision(binding)
            // Nothing owns this keyguard row: release every borrowed reference, mask and native
            // child so a lit lock screen keeps exactly the host's result.
            if (decision.owner == KeyguardRowOwner.NONE) {
                aodController.releaseKeyguardRow(binding, decision.phase)
                return
            }
            val aod = if (decision.phase == AodHostPhase.DOZING ||
                decision.phase == AodHostPhase.TRANSITIONING) AodIconSettings.current() else null
            if (decision.aodMarksNativeRow && aod != null && !aod.duo) {
                // The opt-in only forces support on unsupported ROMs. Native full-screen AOD
                // devices also need the SystemUI battery row, even when that opt-in is off.
                aodController.showNativeFullAod(binding, aod)
                binding.rowPhase = decision.phase
                return
            }
            if (decision.owner == KeyguardRowOwner.AOD_HOLD) {
                // Legacy AOD draws its own battery row while the keyguard header fades.
                aodController.holdHiddenAod(binding)
                binding.rowPhase = decision.phase
                return
            }
            // Leaving the AOD row: restore native children, row visibility and layout before this
            // pass either renders Duo for a lit lock screen or hides as the host asks.
            aodController.restoreNativeFullAod(binding)
            val percent = (read(battery, "mLevel") as? Int)?.takeIf { read(battery, "mFirstLevel") == false }
            val charging = read(battery, "mCharging") as? Boolean
            val powerSave = read(battery, "mPowerSave") as? Boolean
            // Use the host's configured performance colour, including resource overlays.
            // Charging and power saving retain the native progress-state priority.
            val performanceColor = if (read(battery, "mPerformanceMode") == true)
                read(battery, "mBatteryIconView")?.let { read(it, "mBatteryPerformanceModeColor") as? Int }
                else null
            val freshContent = if (percent != null && charging != null && powerSave != null)
                DuoPolicy.content(DuoBattery(percent, charging, powerSave, performanceColor), mobile, networkSource.network, dataFirst) { subId ->
                    slotIndices[subId] ?: -1
                } else null
            val content = when {
                aod?.centerBattery == true -> percent?.let { value ->
                    DuoContent(DuoBattery(value, charging == true, powerSave == true, performanceColor), null, null,
                        0, false, false)
                } ?: binding.view.icon.content
                aod != null -> freshContent ?: binding.view.icon.content
                else -> presentationContent(binding, freshContent)
            }
            val privacyState = read(binding.parent, "mPrivacyState")?.toString()
            val privacyShowing = DuoPrivacyGeometry.isTransition(privacyState)
            val visible = (read(battery, "mHomeBlock") == false || privacyShowing) && read(battery, "mMinimalism") == false &&
                (binding.surface == DuoSurface.KEYGUARD || read(battery, "mIsAodAnimate") != true) &&
                (aod != null || standardEnabled)
            if (aod != null && binding.failed) {
                aodController.hideForAod(binding)
                binding.rowPhase = decision.phase
                return
            }
            if (aod != null && content == null) {
                // The AOD target has already been selected. Never expose the stock row while
                // battery and network callbacks are still being delivered.
                aodController.hideForAod(binding)
                binding.rowPhase = decision.phase
                return
            }
            if (!enabled || binding.failed || content == null || !visible || battery.parent !== binding.parent ||
                !DuoPolicy.replaces(surface(battery), expandedStyle)) {
                restore(binding)
                binding.rowPhase = decision.phase
                return
            }
            binding.rowPhase = decision.phase
            binding.view.icon.sizes = componentSizes
            binding.view.icon.foreground = foreground(binding)
            binding.view.icon.nativeWifi = nativeWifiGlyph(binding)
            val batteryOnly = aod?.centerBattery == true || binding.surface == DuoSurface.EXPANDED &&
                expandedStyle == DuoExpandedStyle.KEEP_DUO
            val percentContainer = read(battery, "mBatteryPercentContainer") as? View
            val percentView = read(battery, "mBatteryPercentView") as? TextView
            val percentMark = read(battery, "mBatteryPercentMarkView") as? TextView
            val showLeadingPercent = DuoPolicy.leadingPercent(binding.surface, expandedStyle,
                settingCcBatteryPercentLeft)
            val batteryGlyph = if (batteryOnly) originalBatteryDrawable(binding) else null
            if (batteryOnly && batteryGlyph == null) {
                if (aod != null) aodController.hideForAod(binding) else restore(binding)
                return
            }
            if (aod != null) {
                renderAodKeyguard(binding, content, aod, batteryGlyph, percentView)
            } else {
                binding.view.layoutDirection = binding.parent.layoutDirection
                binding.view.setBatteryOnly(batteryOnly, batteryGlyph)
                binding.view.setPercent(
                    enabled = showLeadingPercent || batteryOnly,
                    below = batteryOnly,
                    container = percentContainer,
                    value = percentView,
                    mark = percentMark,
                    fallbackText = percent?.toString()
                )
            }
            binding.view.icon.hideSignalDots = binding.dotsInMotion || panelHandover.hideExpandedDots(binding)
            if (aod == null) binding.view.submit(content, small5GaEnabled,
                signalArtwork.renderCellularSignal(battery.context, content))
            binding.view.contentDescription = buildString {
                append(battery.contentDescription?.toString().orEmpty())
                if (content.airplaneMode) {
                    append(", Airplane mode")
                } else {
                    append(", ")
                    append(content.networkLabel ?: content.wifiLevel?.let { "Wi-Fi $it/4" } ?: "Cellular")
                    append(", SIM ${if (content.noService) "0" else content.mobileLevel}/4")
                    if (content.noInternet) append(", No internet")
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
            binding.view.visibility = View.VISIBLE
            battery.visibility = View.GONE
            binding.aodNativeSuppressed = false
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
            // A failure is not an AOD state: only an AOD-owned row may stay suppressed, a lit
            // lock screen keeps the host result instead of hiding both representations.
            if (binding.surface == DuoSurface.KEYGUARD && binding.rowPhase != AodHostPhase.LIT) {
                aodController.hideForAod(binding)
            }
            DebugLog.w(TAG, "container failed", error)
        } finally { binding.reconciling = false }
    }

    /**
     * Resolves the host's authoritative dozing source once: `MiuiFullAodManager.isDozing` is
     * written by `StatusBarStateController.StateListener.onDozingChanged`, so it describes the
     * surface that is actually showing. `mToLockScreen` is not used for this decision.
     */
    private fun renderAodKeyguard(
        binding: DuoBinding,
        content: DuoContent,
        settings: AodIconSettings,
        batteryGlyph: Drawable?,
        hostPercent: TextView?
    ) {
        val proxy = binding.aodPercent ?: TextView(binding.battery.context).also {
            it.includeFontPadding = false
            binding.aodPercent = it
        }
        proxy.text = if (settings.percentPosition == AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER)
            content.battery.percent.toString() else "${content.battery.percent}%"
        hostPercent?.let {
            proxy.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize)
            proxy.typeface = it.typeface
            proxy.letterSpacing = it.letterSpacing
        }
        proxy.setTextColor(hostPercent?.currentTextColor ?: binding.view.icon.foreground)
        proxy.visibility = View.VISIBLE
        val centerPercent = settings.percentPosition == AodSettingsKeys.AOD_PERCENT_POSITION_DUO_CENTER
        updateAodDuoView(
            context = binding.battery.context,
            target = binding.view,
            content = content,
            centerBattery = settings.centerBattery,
            batteryDrawable = batteryGlyph,
            percentVisible = settings.percent,
            percentBelow = centerPercent,
            percentOnRight = settings.percentPosition == AodSettingsKeys.AOD_PERCENT_POSITION_RIGHT,
            percentContainer = binding.parent,
            percentValue = proxy,
            percentFallback = content.battery.percent.toString(),
            foreground = binding.view.icon.foreground
        )
    }

    /** Keep a valid picture briefly while independent host flows finish a handover. */
    private fun presentationContent(binding: DuoBinding, fresh: DuoContent?): DuoContent? {
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
                    bindings.values.toList().forEach(panelHandover::restoreProxyPosition)
                }
            } }
            after { param -> guarded {
                if (ControlCenterCarrierBlockHooker.isShadeSwitching()) return@guarded
                val progress = param.args.getOrNull(0) as? Float ?: return@guarded
                if (progress !in 0f..1f) return@guarded
                if (progress < 0.999f) shadeSwitchSettledToControlCenter = false
                panelProgress = progress
                if (progress > 0f) panelVisible = true
                bindings.values.toList().forEach(panelHandover::updatePanelBinding)
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
                bindings.values.toList().forEach(panelHandover::updatePanelBinding)
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
                bindings.values.toList().forEach(panelHandover::updatePanelBinding)
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
            panelHandover.clearPanelMotion(binding)
            panelHandover.clearSignalMotions(binding)
            panelHandover.restoreProxyPosition(binding)
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
                binding.panelArrival.shadeFinished(controlCenterVisible)
                panelHandover.clearPanelMotion(binding)
                panelHandover.clearSignalMotions(binding)
                panelHandover.restoreProxyPosition(binding)
            }
        }
    }

    private fun ancestor(view: View, name: String): View? = HostAccess.ancestor(view, name)

    fun hasActiveProxy(root: View): Boolean = panelHandover.hasActiveProxy(root)

    private fun nativeWifiGlyph(binding: DuoBinding): NativeWifiDrawable? {
        val id = binding.networkIds["wifi_signal"]?.takeIf { it != 0 } ?: return null
        val source = binding.icons.findViewById<View>(id) as? android.widget.ImageView ?: return null
        val drawable = binding.wifiGlyph?.takeIf { it.source === source }
            ?: wifiTintResourceMethod?.let { transform ->
                NativeWifiDrawable(source) { resource ->
                    transform.invoke(null, resource, true, false) as? Int
                        ?: error("native Wi-Fi tint resource unavailable")
                }.also { binding.wifiGlyph = it }
            } ?: return null
        val ready = drawable.isReady()
        if (binding.wifiArtworkRevision != drawable.revision) {
            binding.wifiArtworkRevision = drawable.revision
            binding.view.invalidate()
        }
        return drawable.takeIf { ready }
    }

    /** Follow host Folme transforms without creating another animator or cancelling frames. */
    private fun privacyOffset(binding: DuoBinding, state: String?): Float {
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

    private fun setPrivacyInset(binding: DuoBinding, inset: Int) {
        if (binding.privacyInset == inset) return
        val icons = binding.icons
        val base = (icons.paddingEnd - binding.privacyInset).coerceAtLeast(0)
        binding.privacyInset = inset
        icons.setPaddingRelative(icons.paddingStart, icons.paddingTop, base + inset, icons.paddingBottom)
    }

    /** Snapshot the host's own airplane-slot policy before Duo changes anything. */
    private fun captureAirplaneMaskBaseline(binding: DuoBinding) {
        if (binding.hostIgnoredAirplane != null) return
        val ignored = ignoredSlots(binding.icons) ?: return
        binding.hostIgnoredAirplane = ignored.any { it == AIRPLANE_SLOT }
    }

    /**
     * Adds/removes only Duo's native-airplane suppression.  The baseline bit prevents restore from
     * exposing an airplane slot that was already hidden by Icon Tuner or the host configuration.
     */
    internal fun ownsAirplane(container: View): Boolean = IconPositionHooker.duoOwnsAirplane(container)

    private fun setNativeAirplaneMasked(binding: DuoBinding, masked: Boolean) {
        // IconPosition owns the merged airplane mask. Never mutate ignoredSlots independently:
        // its next pass would restore that list and trigger another layout every frame.
        if (binding.airplaneMasked == masked) return
        binding.airplaneMasked = masked
        com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftContainerHooker.onDuoOwnershipChanged()
    }

    @Suppress("UNCHECKED_CAST")
    private fun ignoredSlots(view: View): MutableList<Any?>? =
        runCatching { field(view.javaClass, "ignoredSlots")?.get(view) as? MutableList<Any?> }.getOrNull()

    private fun foreground(binding: DuoBinding): Int {
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

    private fun restore(binding: DuoBinding) {
        aodController.restoreNativeFullAod(binding)
        panelHandover.clearSignalMotions(binding)
        panelHandover.clearPanelMotion(binding)
        panelHandover.restoreProxyPosition(binding)
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
        if (!binding.active) {
            // Only a suppression the module made may be released by replaying the host's own
            // visibility; otherwise the host result is already the truth.
            if (binding.aodNativeSuppressed) visibilityMethod?.invoke(binding.battery)
            binding.aodNativeSuppressed = false
            return
        }
        binding.active = false
        binding.aodNativeSuppressed = false
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
        binding.rowPhase = AodHostPhase.UNKNOWN
        binding.preDraw?.let { listener -> binding.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
        // Removal during the parent's detach traversal can skip sibling detach callbacks.
        if (!enabled && Looper.myLooper() == Looper.getMainLooper()) {
            if (binding.view.parent === binding.parent) binding.parent.removeView(binding.view)
        } else main.post { if (binding.view.parent === binding.parent) binding.parent.removeView(binding.view) }
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
            main.post { if (enabled && token == epoch) guarded { networkSource.bindWifi(scope, interactor, context) } }
        } } }
    }

    private fun refresh() { bindings.values.toList().forEach { guarded { reconcile(it) } } }

    /**
     * Applies an acknowledged AOD snapshot without recreating hooks or rescanning the host.
     *
     * Every hooked entry point stays installed for the process lifetime and the row decision is
     * re-derived per pass from [keyguardDecision], so this pass only refreshes the derived options
     * and reconciles the existing bindings. A host that was already attached while the first AOD
     * option was off is covered; a transient dozing read never overwrites a proven phase.
     */
    fun refreshAodSettings() {
        if (!enabled) {
            DebugLog.hookSkippedDebug(TAG, "AOD settings refresh", "Duo module disabled")
            return
        }
        standardEnabled = settingIconDuoEnabled
        expandedStyle = if (settingIconDuoExpanded == 0)
            DuoExpandedStyle.KEEP_DUO else DuoExpandedStyle.RESTORE_NATIVE
        iconSizeDp = DuoLayout.safeSizeDp(
            settingIconDuoSize.toFloat()
        )
        componentSizes = DuoSizes(
            ring = DuoSizes.ratio(settingIconDuoRingScale),
            wifi = DuoSizes.ratio(settingIconDuoWifiScale),
            cellular = DuoSizes.ratio(settingIconDuoCellularScale),
            type = DuoSizes.ratio(settingIconDuoTypeScale),
            dots = DuoSizes.ratio(settingIconDuoDotsScale),
            airplane = DuoSizes.ratio(settingIconDuoAirplaneScale),
            battery = DuoSizes.ratio(settingIconDuoBatteryScale),
            percent = DuoSizes.ratio(settingIconDuoPercentScale)
        )
        val token = epoch
        main.post { if (enabled && token == epoch) guarded {
            bindings.values.toList().forEach { binding ->
                binding.view.iconSizeDp = iconSizeDp
                reconcile(binding)
            }
            DebugLog.i(TAG, "AOD settings refreshed records=" + bindings.size +
                " standardDuo=" + standardEnabled)
        } }
    }
    private fun field(type: Class<*>, name: String): Field? = HostAccess.field(type, name)
    private fun read(owner: Any, name: String): Any? = field(owner.javaClass, name)?.get(owner)
    private inline fun guarded(action: () -> Unit) { runCatching(action).onFailure { DebugLog.w(TAG, "host callback failed", it) } }

    private fun <T> onMainBlocking(action: () -> T): T = HostAccess.onMain(action)
    override fun saveHotReloadState(): Any? = onMainBlocking {
        listOf(networkSource.wifiScope, networkSource.wifiInteractor, networkSource.wifiContext, bindings.keys.toList(),
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
            if (scope != null && interactor != null && context != null) networkSource.bindWifi(scope, interactor, context)
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
        if (enabled) networkSource.bindWifi(scope, interactor, context)
    }

    override fun onPrepareHotReload() {
        SignalHandoverEvents.shade.retire(this)
        epoch++
        // Finish native restoration before the lifecycle removes this generation's hooks.
        onMainBlocking {
            bindings.keys.toList().forEach(::detach)
            aodController.retire()
            wifiTintResourceMethod = null
            small5GaEnabled = false
            signalArtwork.retire()
            slotIndices = emptyMap()
            mobile = MobileSignalState(); networkSource.retire()
            panelProgress = 0f; panelVisible = false; panelStretchHeight = 0f
            shadeSwitchSuspended = false; shadeSwitchSettledToControlCenter = false
        }
    }
}
