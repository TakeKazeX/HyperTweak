package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.runtime.reload.HotReloadPackageState
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsStore
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.ManagedHookReload
import com.takekazex.hypertweak.hook.rules.aod.AodStatusIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationAbsoluteTimeHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationFontWeightHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationHeaderClockSecondsHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationHeaderWeatherHooker
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationMonetTextColorHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUIPluginHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.AirplaneIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CcBatteryStyleHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CellularIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CellularTypeIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.ControlCenterCarrierBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterPrivacyHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HideCarrierLabelHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HideCellularIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconManagerHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconPositionHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotTintHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IgnoreSysIconSettingsHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftContainerHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.NotificationMaxNumberHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.RegionSamplingHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StackedSignalHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.WifiIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoSignalHooker
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
    private companion object { const val KEY_HEADER_CLOCK_SECONDS = "notification_header_clock_seconds" }
    fun iconHookers(): List<BaseHooker> = listOf(
        CellularIconHooker, WifiIconHooker, HideCellularIconHooker, IconManagerHooker,
        IconPositionHooker, AirplaneIconHooker, IconSlotTintHooker, IgnoreSysIconSettingsHooker,
        LeftContainerHooker, DuoSignalHooker, StackedSignalHooker, HideCarrierLabelHooker,
        ControlCenterHeaderHooker, CcBatteryStyleHooker, ControlCenterCarrierBlockHooker,
        ControlCenterPrivacyHooker, RegionSamplingHooker, CellularTypeIconHooker, NotificationMaxNumberHooker
    )

    /**
     * Applies an acknowledged AOD snapshot to every living host without recreating hooks.
     *
     * The AOD options never recreate the SystemUI keyguard row: `DuoSignalHooker` re-derives its
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
                DuoSignalHooker.refreshAodSettings()
                SystemUIPluginHooker.reloadAodSettings()
                true
            }
            else -> true
        }
    }

    /**
     * Applies a committed header snapshot. Each key is routed to the hooker that owns it: the
     * clock-seconds key changes the header clock, the layout keys rebuild the header rows, and the
     * weather keys refresh the date suffix. An empty key set (resync/resume) runs all of them.
     */
    fun applyHeaderSettings(keys: Set<String>) {
        if (keys.isNotEmpty() && keys.none { it in SystemUiLiveSettings.HEADER_KEYS }) return
        val layoutKeys = SystemUiLiveSettings.HEADER_KEYS - KEY_HEADER_CLOCK_SECONDS
        val weatherKeys = setOf(
            SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_ENABLED,
            SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_REGION,
            SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_TYPE
        )
        if (keys.isEmpty() || keys.any { it in layoutKeys }) NotificationHeaderHooker.refreshSettings()
        if (keys.isEmpty() || keys.any { it in weatherKeys }) NotificationHeaderWeatherHooker.refreshSettings()
        // The clock-seconds key belongs to this domain too; a seconds-only snapshot must reach it.
        if (keys.isEmpty() || KEY_HEADER_CLOCK_SECONDS in keys) {
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
        if (keys.isEmpty() && !iconSettingsInstalledFromDifferentValues()) {
            DebugLog.d("IconTuner", "full snapshot with unchanged icon values; refreshing policy only")
            StackedSignalHooker.onPackageReady(context)
            ControlCenterCarrierBlockHooker.onPackageReady(context)
            LeftContainerHooker.onPackageReady(context)
            LeftContainerHooker.requestReconcile()
            com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHotReloadRecovery.scheduleIcons(context, liveHookers())
            return true
        }
        ManagedHookReload.run(
            replacements = hooks.map { it to it },
            recover = {
                StackedSignalHooker.onPackageReady(context)
                ControlCenterCarrierBlockHooker.onPackageReady(context)
                LeftContainerHooker.onPackageReady(context)
                // The icon snapshot is only re-read on events now, so a committed settings change
                // must reconcile explicitly instead of waiting for a periodic timer.
                LeftContainerHooker.requestReconcile()
                com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHotReloadRecovery.scheduleIcons(context, liveHookers())
            }
        )
        DebugLog.i("IconTuner", "live icon settings attached hooks=${hooks.size}; native host recovery queued")
        iconSnapshotAtInstall = iconValueSnapshot()
        return true
    }

    /**
     * The icon-relevant values the current hooks were installed from.
     *
     * Compared against the live values so a genuinely changed setting still rebuilds the hooks,
     * while the boot resync - which republishes identical values - does not.
     */
    @Volatile private var iconSnapshotAtInstall: String? = null

    private fun iconValueSnapshot(): String {
        val values = RuntimeSettingsStore.values() ?: return ""
        return values.entries
            .filter { com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy.affectsIcons(it.key) }
            .sortedBy { it.key }
            .joinToString("|") { (key, value) -> key + "=" + value }
    }

    private fun iconSettingsInstalledFromDifferentValues(): Boolean {
        val installed = iconSnapshotAtInstall ?: return true
        return installed != iconValueSnapshot()
    }

    fun recordInstalledIcons() { iconSnapshotAtInstall = iconValueSnapshot() }
}
