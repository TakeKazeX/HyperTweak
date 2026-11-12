package com.takekazex.hypertweak.hook.bootstrap

import android.content.Context
import android.os.SystemClock
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.ManagedHookReload
import com.takekazex.hypertweak.hook.rules.systemui.SystemUIPluginHooker
import com.takekazex.hypertweak.hook.rules.systemui.aod.AodStatusIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.AirplaneIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CcBatteryStyleHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CcRotationLockIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CellularIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CellularTypeIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterPrivacyHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HideCarrierLabelHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostHooks
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconManagerHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotTintHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IgnoreSysIconSettingsHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobilePipelineAdapter
import com.takekazex.hypertweak.hook.rules.systemui.icon.NotificationMaxNumberHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelMotionAdapter
import com.takekazex.hypertweak.hook.rules.systemui.icon.RegionSamplingHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.SlotMaskApplier
import com.takekazex.hypertweak.hook.rules.systemui.icon.StackedRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHotReloadRecovery
import com.takekazex.hypertweak.hook.rules.systemui.icon.WifiPipelineAdapter
import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.CarrierRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoRenderer
import com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftRenderer
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationAbsoluteTimeHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationFontWeightHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationHeaderClockSecondsHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationHeaderWeatherHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationMonetTextColorHooker
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadPackageState
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsStore
import com.takekazex.hypertweak.settings.contract.IconConfig
import com.takekazex.hypertweak.settings.contract.IconConfigDecoder
import com.takekazex.hypertweak.settings.contract.IconConfigReload
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy
import com.takekazex.hypertweak.settings.contract.IndependentIconDomain
import com.takekazex.hypertweak.settings.contract.SystemUiLiveSettings
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel

/** SystemUI settings effects; HookEntry retains generation, loader and handle ownership. */
internal class SystemUiSettingsHost(
    private val processName: () -> String,
    private val retired: () -> Boolean,
    private val packageState: () -> HotReloadPackageState?,
    private val liveHookers: () -> Collection<BaseHooker>
) {
    private companion object {
        val CLOCK_SECONDS_KEYS = setOf(
            SystemUiSettingsKeys.KEY_STATUS_BAR_CLOCK_SECONDS,
            SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS
        )
    }
    /** Install order matters: the shared hooks and state producers precede their consumers. */
    fun iconHookers(): List<BaseHooker> = listOf(
        IconHostHooks, PanelMotionAdapter, MobilePipelineAdapter,
        CellularIconHooker, WifiPipelineAdapter, IconManagerHooker,
        SlotMaskApplier, AirplaneIconHooker, IconSlotTintHooker, IgnoreSysIconSettingsHooker,
        LeftRenderer, DuoRenderer, StackedRenderer, HideCarrierLabelHooker,
        ControlCenterHeaderHooker, CcBatteryStyleHooker, CcRotationLockIconHooker, CarrierRenderer,
        ControlCenterPrivacyHooker, RegionSamplingHooker, CellularTypeIconHooker, NotificationMaxNumberHooker
    )

    /**
     * Applies an acknowledged AOD snapshot to every living host without recreating hooks.
     *
     * The AOD options never recreate the SystemUI keyguard row: `DuoRenderer` re-derives its
     * row ownership per pass. The plugin row is refreshed in place through its parent session,
     * which keeps the plugin's handle ownership and the standalone AOD process symmetric.
     */
    fun applyLiveAodSettings(): Boolean {
        if (retired()) return true
        return when (processName()) {
            "com.miui.aod" -> {
                AodStatusIconHooker.refreshAll()
                true
            }
            "com.android.systemui" -> {
                if (!PlatformLevel.isOs4) return true
                DuoRenderer.refreshAodSettings()
                SystemUIPluginHooker.reloadAodSettings()
                true
            }
            else -> true
        }
    }

    /**
     * Applies a committed header snapshot. Each key is routed to the hooker that owns it: the
     * clock-seconds keys refresh both clocks, the layout keys rebuild the header rows, and the
     * weather keys refresh the date suffix. An empty key set (resync/resume) runs all of them.
     */
    fun applyHeaderSettings(keys: Set<String>) {
        if (keys.isNotEmpty() && keys.none { it in SystemUiLiveSettings.HEADER_KEYS }) return
        val layoutKeys = SystemUiLiveSettings.HEADER_KEYS - CLOCK_SECONDS_KEYS
        val weatherKeys = setOf(
            SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_ENABLED,
            SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_REGION,
            SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_TYPE
        )
        if (keys.isEmpty() || keys.any { it in layoutKeys }) NotificationHeaderHooker.refreshSettings()
        if (keys.isEmpty() || keys.any { it in weatherKeys }) NotificationHeaderWeatherHooker.refreshSettings()
        // Both clock settings belong here; a seconds-only snapshot must reach the clock hooker.
        if (keys.isEmpty() || keys.any { it in CLOCK_SECONDS_KEYS }) {
            NotificationHeaderClockSecondsHooker.refreshSettings()
        }
    }

    /** Applies a committed notification-text snapshot to the displayed notifications. */
    fun applyNotificationTextSettings(keys: Set<String>) {
        if (keys.isNotEmpty() && keys.none { it in SystemUiLiveSettings.NOTIFICATION_TEXT_KEYS }) return
        if (keys.isEmpty() || SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR in keys) {
            NotificationMonetTextColorHooker.refreshSettings()
        }
        if (keys.isEmpty() || SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME in keys) {
            NotificationAbsoluteTimeHooker.refreshSettings()
        }
        if (keys.isEmpty() || SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT in keys) {
            NotificationFontWeightHooker.refreshSettings()
        }
    }

    /**
     * Retire current callbacks before reattaching; real module hot reload uses framework handles.
     *
     * [keys] is empty for a full resync, which is what the boot snapshot looks like: the app
     * publishes the complete snapshot with `keys=0` once the settings channel comes up. Rebuilding
     * nineteen icon hookers for that costs a DexKit bridge open plus a carrier-callback query, and
     * the values have not changed since they were installed moments earlier. A full snapshot
     * therefore only re-reads the policy unless an icon-relevant value actually differs.
     */
    fun applyLiveIconSettings(keys: Set<String>): Boolean {
        if (retired() || processName() != "com.android.systemui") return true
        val state = packageState() ?: return false
        val context = state.appContext ?: return false
        val hooks = iconHookers().filter { it in liveHookers() }
        if (hooks.isEmpty()) return false
        val snapshot = iconValueSnapshot()
        val independent = IconConfigReload.domains(iconSnapshotAtInstall, snapshot)
        if (independent?.isEmpty() == true) {
            if (keys.isNotEmpty()) return true
            DebugLog.d("IconTuner", "full snapshot with unchanged icon values; recovering living hosts without group rebuild")
            MobilePipelineAdapter.onPackageReady(context)
            StackedRenderer.onPackageReady(context)
            CarrierRenderer.onPackageReady(context)
            LeftRenderer.onPackageReady(context)
            LeftRenderer.requestReconcile()
            StatusIconHotReloadRecovery.scheduleScoped(context, hooks)
            return true
        }
        val start = SystemClock.elapsedRealtime()
        if (independent != null && applyIndependentIcons(context, hooks, independent)) {
            iconSnapshotAtInstall = snapshot
            DebugLog.i("IconTuner", "live independent icon settings applied domains=$independent " +
                "elapsedMs=${SystemClock.elapsedRealtime() - start}")
            return true
        }
        ManagedHookReload.run(
            replacements = hooks.map { it to it },
            recover = {
                MobilePipelineAdapter.onPackageReady(context)
                StackedRenderer.onPackageReady(context)
                CarrierRenderer.onPackageReady(context)
                LeftRenderer.onPackageReady(context)
                // The icon snapshot is only re-read on events now, so a committed settings change
                // must reconcile explicitly instead of waiting for a periodic timer.
                LeftRenderer.requestReconcile()
                StatusIconHotReloadRecovery.scheduleIcons(context, liveHookers())
            }
        )
        DebugLog.i("IconTuner", "live icon settings attached hooks=${hooks.size} " +
            "attachMs=${SystemClock.elapsedRealtime() - start}; native host recovery queued")
        iconSnapshotAtInstall = snapshot
        return true
    }

    /**
     * Applies [domains] through their own owners only. The native icon groups are never rebuilt,
     * so the status bar keeps its views: policy, Duo and pipeline-gate changes reconcile in place, and owners that
     * only draw on the control center, lock screen or a single slot are recreated on their own.
     * False when an owner is not installed, so the caller falls back to the full transaction.
     */
    private fun applyIndependentIcons(
        context: Context,
        hooks: List<BaseHooker>,
        domains: Set<IndependentIconDomain>
    ): Boolean {
        val reloaded = LinkedHashSet<BaseHooker>()
        val live = LinkedHashSet<BaseHooker>()
        domains.forEach { domain ->
            when (domain) {
                IndependentIconDomain.SAMPLING -> reloaded += RegionSamplingHooker
                IndependentIconDomain.NOTIFICATIONS -> reloaded += NotificationMaxNumberHooker
                IndependentIconDomain.POLICY -> live += listOf(IconManagerHooker, SlotMaskApplier, LeftRenderer)
                IndependentIconDomain.DUO -> live += DuoRenderer
                IndependentIconDomain.CONTROL_CENTER_HEADER -> reloaded += listOf(
                    ControlCenterHeaderHooker, CarrierRenderer, HideCarrierLabelHooker
                )
                IndependentIconDomain.CC_BATTERY -> reloaded += CcBatteryStyleHooker
                IndependentIconDomain.ROTATION_LOCK -> reloaded += CcRotationLockIconHooker
                IndependentIconDomain.AIRPLANE -> reloaded += AirplaneIconHooker
                IndependentIconDomain.CELLULAR -> live += CellularIconHooker
                IndependentIconDomain.WIFI -> live += WifiPipelineAdapter
                IndependentIconDomain.MOBILE_SIGNAL -> live += listOf(MobilePipelineAdapter, StackedRenderer)
                IndependentIconDomain.CELLULAR_TYPE -> reloaded += CellularTypeIconHooker
            }
        }
        if (!hooks.containsAll(reloaded) || !hooks.containsAll(live)) return false
        val sampling = reloaded.filter { it === RegionSamplingHooker || it === NotificationMaxNumberHooker }
        val scoped = reloaded - sampling.toSet()
        if (reloaded.isNotEmpty()) {
            ManagedHookReload.run(replacements = reloaded.map { it to it }) {
                if (sampling.isNotEmpty()) StatusIconHotReloadRecovery.scheduleIndependentIcons(context, sampling)
                if (scoped.isNotEmpty()) {
                    if (CarrierRenderer in scoped) CarrierRenderer.onPackageReady(context)
                    StatusIconHotReloadRecovery.scheduleScoped(context, scoped.toList())
                }
            }
        }
        if (live.isNotEmpty()) HostAccess.onMain {
            if (IndependentIconDomain.POLICY in domains) {
                // Visibility and left placement only: re-block the existing views in place.
                IconManagerHooker.refreshPolicy()
                SlotMaskApplier.refreshPolicy()
                LeftRenderer.requestReconcile()
            }
            // Pipeline gates re-emit through the binders that already collect them.
            if (CellularIconHooker in live) CellularIconHooker.refreshSettings()
            if (WifiPipelineAdapter in live) WifiPipelineAdapter.refreshSettings()
            if (MobilePipelineAdapter in live) MobilePipelineAdapter.refreshSettings()
            if (StackedRenderer in live) StackedRenderer.refreshSettings()
            if (DuoRenderer in live) {
                DuoRenderer.refreshSettings()
                // A style enabled from native/stacked may have no dormant home Duo binding.
                // Discover only Duo's living hosts; keep icon groups and unrelated sources intact.
                StatusIconHotReloadRecovery.scheduleScoped(context, listOf(DuoRenderer))
            }
        }
        return true
    }

    /**
     * The icon-relevant values the current hooks were installed from.
     *
     * Compared against the live values so a genuinely changed setting still rebuilds the hooks,
     * while the boot resync - which republishes identical values - does not.
     */
    @Volatile private var iconSnapshotAtInstall: IconConfig? = null

    private fun iconValueSnapshot(): IconConfig? = RuntimeSettingsStore.values()
        ?.let { values -> IconConfigDecoder.decode { values[it] } }

    fun recordInstalledIcons() { iconSnapshotAtInstall = iconValueSnapshot() }
}
