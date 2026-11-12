@file:Suppress("StaticFieldLeak")
package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

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
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.orSkip
import com.takekazex.hypertweak.hook.rules.systemui.aod.AodIconSettings
import com.takekazex.hypertweak.hook.rules.systemui.icon.GlyphEndpoint
import com.takekazex.hypertweak.hook.rules.systemui.icon.HandoverCoordinator
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.NetworkHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelHub
import com.takekazex.hypertweak.hook.rules.systemui.icon.SlotMaskApplier
import com.takekazex.hypertweak.hook.rules.systemui.icon.signalEndpointVisible
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.presentation.icon.MobileSignalState
import com.takekazex.hypertweak.presentation.icon.NetworkIconVisibility
import com.takekazex.hypertweak.presentation.icon.duo.DuoBattery
import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import com.takekazex.hypertweak.presentation.icon.duo.DuoExpandedStyle
import com.takekazex.hypertweak.presentation.icon.duo.DuoLayout
import com.takekazex.hypertweak.presentation.icon.duo.DuoPolicy
import com.takekazex.hypertweak.presentation.icon.duo.DuoSizes
import com.takekazex.hypertweak.presentation.icon.duo.DuoSurface
import com.takekazex.hypertweak.presentation.icon.duo.NativeWifiDrawable
import com.takekazex.hypertweak.presentation.icon.toDuoNetwork
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.settings.contract.CcBatteryPercentStyle
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSurface
import com.takekazex.hypertweak.settings.contract.SignalStyle
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * OS4 battery-layout adapter. Original battery objects remain attached and receive host callbacks.
 * Only the parent's View-typed layout reference is borrowed; the icon container never receives a
 * module View (its children must implement the host StatusIconDisplayable interface).
 */
internal object DuoRenderer : IconRenderer() {
    override val hostCallbackOrder = 60
    // Published on settings acceptance; hook callbacks perform no storage reads.
    internal val networkVisibility by hookSetting(NetworkIconVisibility()) { IconTunerOptions.networkVisibility() }
    internal val settingIconMobileRowStyle by hookSetting(0) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE) }
    internal val settingIconCellularTypeSmall5ga by hookSetting(false) {
        Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA)
    }
    internal val settingDuoTopPercent by hookSetting(false) { Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_DUO_TOP_PERCENT) }
    internal val settingDuoCellularDots by hookSetting(false) { Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_DUO_CELLULAR_DOTS) }
    internal val settingDuoDualDots by hookSetting(false) { Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_DUO_DUAL_DOTS) }
    internal val settingDuoClickExpand by hookSetting(true) { Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_DUO_CLICK_EXPAND) }
    internal val settingIconDuoEnabled by hookSetting(false) { Preferences.iconConfig().signalStyle == SignalStyle.DUO }
    internal val settingIconDuoExpanded by hookSetting(1) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_DUO_EXPANDED) }
    internal val settingIconDuoSize by hookSetting(DuoLayout.DEFAULT_ICON_SIZE_DP) {
        Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_DUO_SIZE)
    }
    internal val settingIconDuoRingScale by hookSetting(100) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_DUO_RING_SCALE) }
    internal val settingIconDuoWifiScale by hookSetting(100) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_DUO_WIFI_SCALE) }
    internal val settingIconDuoCellularScale by hookSetting(100) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_DUO_CELLULAR_SCALE) }
    internal val settingIconDuoTypeScale by hookSetting(100) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_DUO_TYPE_SCALE) }
    internal val settingIconDuoDotsScale by hookSetting(100) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_DUO_DOTS_SCALE) }
    internal val settingIconDuoAirplaneScale by hookSetting(100) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_DUO_AIRPLANE_SCALE) }
    internal val settingIconDuoBatteryScale by hookSetting(100) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_DUO_BATTERY_SCALE) }
    internal val settingIconDuoPercentScale by hookSetting(100) { Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_DUO_PERCENT_SCALE) }
    internal val hideTypeWhenDisconnected by hookSetting(false) {
        Preferences.cellularTypeHidesWhenDisconnected()
    }
    internal val settingCcBatteryPercentLeft by hookSetting(false) {
        Preferences.ccBatteryPercentStyle() == CcBatteryPercentStyle.OUTSIDE_LEADING
    }

    init {
        hostRecovery(HostRecoveryPhase.HANDOVER, icons = true) {
            recoverExistingViews(it.views)
        }
    }

    internal val signalArtwork = DuoSignalArtwork()
    internal val panelHandover = DuoPanelHandover(object : DuoPanelHandover.Host {
        override val panelStretchHeight get() = DuoRenderer.panelStretchHeight
        override val panelProgress get() = DuoRenderer.panelProgress
        override val panelVisible get() = DuoRenderer.panelVisible
        override val expandedStyle get() = DuoRenderer.expandedStyle
        override val shadeSwitchSettledToControlCenter get() = DuoRenderer.shadeSwitchSettledToControlCenter
        override val small5GaEnabled get() = DuoRenderer.small5GaEnabled
        override val componentSizes get() = DuoRenderer.componentSizes
        override val enabled get() = DuoRenderer.enabled
        override val mobile get() = DuoRenderer.mobile
        override fun bindings(): Collection<DuoBinding> = bindings.values
        override fun owns(binding: DuoBinding) = bindings[binding.battery] === binding
        override fun slotIndex(subId: Int) = slotIndices[subId]
        override fun rowMorph(context: Context, row: Int, rows: Int) = signalArtwork.rowMorph(context, row, rows)
        override fun signalBars(context: Context) = signalArtwork.signalBars(context)
        override fun destinationSignal(context: Context, level: Int): Picture? =
            signalArtwork.destinationSignal(context, level)
    })
    internal val aodController = DuoAodController(this, object : DuoAodController.Host {
        override val enabled get() = DuoRenderer.enabled
        override val standardEnabled get() = DuoRenderer.standardEnabled
        override val epoch get() = DuoRenderer.epoch
        override val batteryLayoutField get() = DuoRenderer.batteryLayoutField
        override val visibilityMethod get() = DuoRenderer.visibilityMethod
        override fun refresh() = DuoRenderer.refresh()
        override fun restore(binding: DuoBinding) = DuoRenderer.restore(binding)
        override fun setPrivacyInset(binding: DuoBinding, inset: Int) = DuoRenderer.setPrivacyInset(binding, inset)
        override fun setNativeAirplaneMasked(binding: DuoBinding, masked: Boolean) = DuoRenderer.setNativeAirplaneMasked(binding, masked)
    })

    internal const val TAG = "DuoSignal"
    internal const val AIRPLANE_SLOT = "airplane"
    internal const val BATTERY = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
    internal const val CONTAINER = "com.android.systemui.statusbar.views.MiuiStatusBatteryContainer"
    internal val main = Handler(Looper.getMainLooper())
    /** Called before a newly migrated left clone starts borrowing its control-center endpoint. */
    internal fun releaseLeftStatusIcons(slots: Set<String>) {
        bindings.values.toList().forEach { it.statusIconsMotion.excludedSlots = slots }
    }

    internal val bindings = generationMainResource(IdentityHashMap<View, DuoBinding>()) { it.clear() }

    /** Main thread. The lit lockscreen ring under [root] that currently draws the airplane. */
    internal fun keyguardAirplane(root: View): GlyphEndpoint? = bindings.values.firstOrNull { binding ->
        binding.surface == DuoSurface.KEYGUARD && binding.active && binding.view.isAttachedToWindow &&
            binding.view.rootView === root && binding.view.icon.content?.airplaneMode == true &&
            signalEndpointVisible(binding.view, root)
    }?.airplaneEndpoint

    internal fun keyguardAirplaneState(root: View): String =
        bindings.values.filter { it.surface == DuoSurface.KEYGUARD }.joinToString(prefix = "[", postfix = "]") { binding ->
            "active=${binding.active} attached=${binding.view.isAttachedToWindow} " +
                "sameRoot=${binding.view.rootView === root} airplane=${binding.view.icon.content?.airplaneMode} " +
                "visible=${signalEndpointVisible(binding.view, binding.view.rootView ?: root)} " +
                "size=${binding.view.width}x${binding.view.height} phase=${binding.rowPhase}"
        }
    internal var enabled by generationState { false }
    internal var standardEnabled by generationState { false }
    @Volatile
    internal var epoch = 0
    internal var componentSizes by generationState { DuoSizes() }
    internal val panelProgress get() = PanelHub.state.progress
    internal val panelVisible get() = PanelHub.state.visible
    internal val panelStretchHeight get() = PanelHub.state.stretch
    internal var shadeSwitchSuspended by generationState { false }
    internal val shadeSwitchSettledToControlCenter get() = PanelHub.state.settledToControlCenter
    internal var expandedStyle by generationState { DuoExpandedStyle.RESTORE_NATIVE }
    internal var iconSizeDp by generationState { DuoLayout.DEFAULT_ICON_SIZE_DP.toFloat() }
    internal var small5GaEnabled by generationState { false }
    internal val mobile: MobileSignalState get() = MobileSignalHub.state
    internal var slotIndices: Map<Int, Int> by generationState { emptyMap() }
    /** Live dozing read: `MiuiFullAodManager.isDozing`, resolved once from the running host. */
    internal var batteryLayoutField: Field? by generationState { null }
    internal var statusIconsField: Field? by generationState { null }
    internal var wifiTintResourceMethod: Method? by generationState { null }
    internal var visibilityMethod: Method? by generationState { null }
    internal var tintMethod: Method? by generationState { null }
    internal var darkMethod: Method? by generationState { null }

    internal var dataFirst by generationState { false }

    override fun onHook() {
        com.takekazex.hypertweak.hook.rules.systemui.icon.duo.prepareDuoPanelMotionSettings()
        com.takekazex.hypertweak.hook.rules.systemui.icon.duo.prepareDuoSignalArtworkSettings()
        com.takekazex.hypertweak.hook.rules.systemui.icon.duo.prepareDuoPanelHandoverSettings()
        com.takekazex.hypertweak.hook.rules.systemui.icon.duo.prepareDuoIconViewSettings()
        com.takekazex.hypertweak.hook.rules.systemui.aod.prepareAodIconSettingsSettings()
        epoch++
        dataFirst = settingIconMobileRowStyle == 1
        small5GaEnabled = settingIconCellularTypeSmall5ga
        standardEnabled = settingIconDuoEnabled
        // The keyguard row is always owned by this hooker: an accepted AOD snapshot refreshes it
        // through [refreshAodSettings], so the first AOD option does not need a SystemUI restart.
        enabled = PlatformLevel.isOs4 && isMainProcess
        if (!enabled) return
        HandoverCoordinator.shade.bind(this, ::onShadeSwitchStarted, ::onShadeSwitchFinished,
            activeProxy = { it is View && hasActiveProxy(it) },
            ownsAirplane = { it is ViewGroup && ownsAirplane(it) },
            ownsSignalHandover = { ownsSignalHandover })
        MobileSignalHub.subscribe(this, ::onMobileState)
        NetworkHub.subscribe(this, ::refresh)
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
        aodController.resolveBatteryAnimation(batteryClass)
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
                it.name == "transformResId" && Modifier.isStatic(it.modifiers) &&
                    it.returnType == Int::class.javaPrimitiveType &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
            }?.apply { isAccessible = true }
        if (wifiTintResourceMethod == null) {
            DebugLog.hookSkipped(TAG, "native Wi-Fi tint artwork", "resource transformer signature unavailable")
        }
        // No hooks on global View/ImageView draw, visibility or tint methods.
        batteryClass.findMethodOrNull { name("onAttachedToWindow"); paramCount(0) }.orSkip("DuoRenderer", "batteryClass#onAttachedToWindow")?.observe {
            after { param -> (param.thisObject as? View)?.let { battery ->
                val token = epoch
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    if (epoch == token && battery.isAttachedToWindow) guarded { attach(battery) }
                } else {
                    main.post { if (epoch == token && battery.isAttachedToWindow) guarded { attach(battery) } }
                }
            } }
        }
        batteryClass.findMethodOrNull { name("onDetachedFromWindow"); paramCount(0) }.orSkip("DuoRenderer", "batteryClass#onDetachedFromWindow")?.observe {
            before { param -> (param.thisObject as? View)?.let { battery -> guarded { detach(battery) } } }
        }
        visibilityMethod?.observe {
            after { param -> (param.thisObject as? View)?.let { battery -> guarded {
                bindings[battery]?.let { reconcile(it) }
            } } }
        }
        batteryClass.declaredMethods.singleOrNull {
            it.name == "onMeasure" && it.parameterCount == 2
        }?.let { method ->
            deoptimize(method)
            method.observe { before { param ->
                (param.thisObject as? View)?.let { battery -> guarded {
                    bindings[battery]?.let(aodController::applyFullAodNativeChildren)
                } }
            } }
        }
        batteryClass.declaredMethods.singleOrNull {
            it.name == "updateChargeAndText" && it.parameterCount == 0
        }?.let { method ->
            deoptimize(method)
            method.observe { after { param ->
                (param.thisObject as? View)?.let { battery -> guarded {
                    bindings[battery]?.let(aodController::applyFullAodNativeChildren)
                } }
            } }
        }
        batteryClass.declaredMethods.singleOrNull {
            it.name == "onLayout" && it.parameterCount == 5
        }?.let { method ->
            deoptimize(method)
            method.observe { after { param ->
                (param.thisObject as? View)?.let { battery -> guarded {
                    bindings[battery]?.let(aodController::positionNativeFullAod)
                } }
            } }
        }
        val batteryUpdates = listOf(
            "onBatteryLevelChanged", "onPowerSaveChanged", "onPerformanceModeChanged",
            "onChargeStateChanged", "updateLightDarkTint", "updateAll"
        )
        for (name in batteryUpdates) {
            batteryClass.declaredMethods.filter { it.name == name }.forEach { method ->
                method.observe { after { param -> (param.thisObject as? View)?.let { battery -> guarded {
                    bindings[battery]?.let { reconcile(it) }
                } } } }
            }
        }
        // The parent uses mBattery in both measure and layout. Deoptimize these consumers so
        // compiled host code observes the borrowed reference, then reconcile after every layout.
        for (name in listOf("onMeasure", "onLayout")) {
            parentClass.declaredMethods.filter { it.name == name }.forEach { method ->
                deoptimize(method)
                if (name == "onLayout") method.observe { after { param ->
                    bindings.values.firstOrNull { it.parent === param.thisObject }?.let { binding ->
                        guarded { reconcile(binding); if (binding.active) position(binding) }
                    }
                } }
            }
        }
        parentClass.declaredMethods
            .singleOrNull { it.name == "setIsHideBattery" && it.parameterCount == 1 }
            .orSkip("DuoRenderer", "${parentClass.name}#setIsHideBattery")
            ?.observe {
            before { param ->
                val binding = bindings.values.firstOrNull { it.parent === param.thisObject } ?: return@before
                binding.hostHideBattery = param.args[0] as? Boolean ?: return@before
                if (binding.active) param.args[0] = false
            }
        }
        // The native battery changes style only after its fade-out. Keep the current renderer
        // until that handoff, even if the dozing callback arrives earlier or later.
        "com.android.systemui.statusbar.phone.KeyguardStatusBarViewControllerInject".toClassOrNull()
            ?.findMethodOrNull {
                name("animateFullAod")
                parameterTypes(Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            }?.let { method ->
                val statusBar = field(method.declaringClass, "keyguardStatusBarView")
                deoptimize(method)
                method.observe {
                    before { param ->
                        val toLockScreen = param.args.getOrNull(0) as? Boolean ?: return@before
                        val animate = param.args.getOrNull(1) as? Boolean ?: return@before
                        guarded {
                            val root = statusBar?.get(param.thisObject) as? View ?: return@guarded
                            bindings.values.firstOrNull { it.keyguardRoot === root }?.let {
                                aodController.startBatteryAnimation(it, !toLockScreen, animate)
                            }
                        }
                    }
                    after { param ->
                        val toLockScreen = param.args.getOrNull(0) as? Boolean ?: return@after
                        guarded {
                            val root = statusBar?.get(param.thisObject) as? View ?: return@guarded
                            bindings.values.firstOrNull { it.keyguardRoot === root }?.let {
                                aodController.observeHandshake(it, toLockScreen)
                                reconcile(it)
                            }
                        }
                    }
                }
            }
        hookProxyCompensation()
        DebugLog.hookRegistered(TAG, "OS4 battery container, expanded=$expandedStyle")
    }

    internal val ownsSignalHandover: Boolean get() = enabled && (standardEnabled ||
        AodIconSettings.current().let { it.duo && it.centerSignal })

    /** Reuses the SystemUI Duo renderer inside MIUI AOD's own process and battery container. */
    internal fun createAodDuoView(context: Context): View = createAodDuoViewImpl(context)

    internal fun updateAodDuoView(
        context: Context, target: View, content: DuoContent, centerBattery: Boolean,
        batteryDrawable: Drawable?, percentVisible: Boolean, percentBelow: Boolean,
        percentOnRight: Boolean, percentContainer: View?, percentValue: TextView?,
        percentFallback: String?, foreground: Int
    ) = updateAodDuoViewImpl(context, target, content, centerBattery, batteryDrawable,
        percentVisible, percentBelow, percentOnRight, percentContainer, percentValue, percentFallback, foreground)

    /** Hears every settled [MobileSignalHub] change, and the current state once on install. */
    internal fun onMobileState() {
        if (!enabled) return
        slotIndices = mobile.subscriptionOrder.associateWith {
            runCatching { SubscriptionManager.getSlotIndex(it) }.getOrDefault(-1)
        }
        refresh()
    }

    internal fun originalBatteryDrawable(binding: DuoBinding): Drawable? {
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

    internal fun prepareFullAodBatterySource(source: View) {
        if (!source.isAttachedToWindow) return
        val resources = source.resources
        val packageName = source.context.packageName
        val heightId = ResourceLookup.identifier(resources, "battery_meter_height", "dimen", packageName)
        val widthId = ResourceLookup.identifier(resources, "battery_meter_width", "dimen", packageName)
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

    internal fun hookProxyCompensation() {
        PanelHub.subscribe(this, beforeMotion = {
            guarded {
                if (PanelHub.state.shadeSwitching) clearShadeSwitchMotion()
                else bindings.values.toList().forEach(panelHandover::restoreProxyPosition)
            }
        }, changed = {
            guarded {
                if (PanelHub.state.shadeSwitching) clearShadeSwitchMotion()
                else {
                    if (shadeSwitchSuspended) onShadeSwitchFinished(panelVisible)
                    bindings.values.toList().forEach(panelHandover::updatePanelBinding)
                }
            }
        })
    }

    /** The shade switch owns this motion; never replay the proxy/Wi-Fi hand-over on that path. */
    internal fun clearShadeSwitchMotion() {
        if (shadeSwitchSuspended) return
        panelHandover.clearResolutions()
        shadeSwitchSuspended = true
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
            panelHandover.clearResolutions()
            shadeSwitchSuspended = false
            bindings.values.toList().forEach { binding ->
                binding.panelArrival.shadeFinished(controlCenterVisible)
                panelHandover.clearPanelMotion(binding)
                panelHandover.clearSignalMotions(binding)
                panelHandover.restoreProxyPosition(binding)
            }
        }
    }

    internal fun ancestor(view: View, name: String): View? = HostAccess.ancestor(view, name)

    fun hasActiveProxy(root: View): Boolean = panelHandover.hasActiveProxy(root)

    internal fun ownsAirplane(container: View): Boolean = SlotMaskApplier.duoOwnsAirplane(container)

    internal fun refresh() { bindings.values.toList().forEach { guarded { reconcile(it) } } }

    /**
     * Applies an acknowledged AOD snapshot without recreating hooks or rescanning the host.
     *
     * Every hooked entry point stays installed for the process lifetime and the row decision is
     * re-derived per pass from [keyguardDecision], so this pass only refreshes the derived options
     * and reconciles the existing bindings. A host that was already attached while the first AOD
     * option was off is covered; a transient dozing read never overwrites a proven phase.
     */
    fun refreshAodSettings() = refreshLiveSettings("AOD")

    /**
     * Applies changed Duo options (percent, click target, sizes, dots, expanded style) and the
     * control-center options it mirrors to the living bindings in place. The glyph keeps its view,
     * so the next frame simply draws the new options instead of the status bar being rebuilt.
     */
    fun refreshSettings() = refreshLiveSettings("icon")

    internal fun field(type: Class<*>, name: String): Field? = HostAccess.field(type, name)
    internal fun read(owner: Any, name: String): Any? = field(owner.javaClass, name)?.get(owner)
    internal inline fun guarded(action: () -> Unit) { runCatching(action).onFailure { DebugLog.w(TAG, "host callback failed", it) } }

    internal fun <T> onMainBlocking(action: () -> T): T = HostAccess.onMain(action)
    override fun saveHotReloadState(): Any? = onMainBlocking {
        listOf(null, null, null, bindings.keys.toList(),
            panelProgress, panelVisible, panelStretchHeight, shadeSwitchSettledToControlCenter)
    }
    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? List<*> ?: return
        val token = epoch
        main.post { if (enabled && token == epoch) guarded {
            (saved.getOrNull(3) as? List<*>)?.filterIsInstance<View>()?.filter { it.isAttachedToWindow }?.forEach({ attach(it) })
        } }
    }
    internal fun recoverExistingViews(views: List<View>) {
        if (!enabled) return
        views.filter { it.javaClass.name == BATTERY && it.isAttachedToWindow }.forEach { battery ->
            guarded { attach(battery) }
        }
        refresh()
        DebugLog.i(TAG, "hot reload Duo batteries=${bindings.size}")
    }

    override fun onPrepareHotReload() {
        HandoverCoordinator.shade.retire(this)
        epoch++
        // Finish native restoration before the lifecycle removes this generation's hooks.
        onMainBlocking {
            bindings.keys.toList().forEach({ detach(it) })
            aodController.retire()
            wifiTintResourceMethod = null
            small5GaEnabled = false
            signalArtwork.retire()
            slotIndices = emptyMap()
        }
    }
}
