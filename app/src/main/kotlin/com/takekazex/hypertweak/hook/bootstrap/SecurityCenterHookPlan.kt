package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.lbe.LbeClipboardToastHooker
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.AospAppInfoEntryHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.AospAppManagerEntryHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.AppDetailsIconLaunchHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.battery.BatteryInfoHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.BerserkModeHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.BubbleNotificationWhitelistHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.CarrierOfflineConfigHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.battery.DetailedPowerDataHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.battery.LowBatteryWarningHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.battery.MoreBatteryInfoHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.battery.PowerRankingHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.battery.PowerSaveOverrideHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterDockHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterDockHost
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterSidebarHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterPopupHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.privacy.SecurityCenterPrivacyEntriesHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.SecurityCenterSafetyChecksHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.SecurityCenterUsbInstallNetworkVerifyHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.WarningCountdownHooker
import com.takekazex.hypertweak.hook.rules.system.PasskeyHooker

/** Host-owned installation order; factories run only when the caller installs the plan. */
internal object SecurityCenterHookPlan {
    fun steps(packageName: String, processName: String): List<HookStep> = buildList {
        when (packageName) {
            "com.miui.securitycenter" -> {
                // One DexKit bridge for the whole package dispatch: every consumer below reuses
                // the session opened here. Opening one per hooker cost seconds on the 81 MB APK
                // and pushed the auxiliary processes past ActivityManager's provider deadline.
                fun attachSecurityCenterHookers() {
                    hook { RestartBroadcastHooker }
                    // Default-credential writers live in the main Application on older builds and
                    // in CacheService (`.cache`) on OS4. Other Security Center processes have no
                    // target for this hook, so they must not pay its DEX resolution.
                    if (processName == packageName || processName == "$packageName.cache") {
                        hook { PasskeyHooker }
                    }
                    hook { AospAppInfoEntryHooker }
                    hook { AospAppManagerEntryHooker }
                    // Only the main application process owns the on-demand battery request receiver.
                    // Auxiliary Security Center processes must not start another sampling worker.
                    if (processName == packageName) {
                        hook { BatteryInfoHooker }
                    }
                    hook { BerserkModeHooker }
                    hook { BubbleNotificationWhitelistHooker }
                    hook { LowBatteryWarningHooker }
                    hook { DetailedPowerDataHooker }
                    hook { PowerRankingHooker }
                    // The 省电模式 pins come from Security Center's remote process (PowerSaveService),
                    // so this cannot be limited to the main application process.
                    hook { PowerSaveOverrideHooker }
                    hook { MoreBatteryInfoHooker }
                    hook { WarningCountdownHooker }
                    // Anti-peeping capability checks also run in Security Center's remote process;
                    // the hooker limits preference and function-card changes to the main process.
                    hook { SecurityCenterPrivacyEntriesHooker }
                    // Security Center ships its own copy of the vendor clipboard-read overlay and is the
                    // one that actually builds it; see LbeClipboardToastHooker.
                    hook { LbeClipboardToastHooker }
                    if (processName == packageName || processName == "$packageName.cache" || processName == "$packageName.remote") {
                        hook { SecurityCenterSafetyChecksHooker }
                    }
                    if (processName == SecurityCenterDockHost.PROCESS) {
                        hook { SecurityCenterDockHooker }
                        hook { SecurityCenterPopupHooker }
                        hook { SecurityCenterSidebarHooker }
                    }
                    if (processName == packageName) {
                        hook { AppDetailsIconLaunchHooker }
                        hook { SecurityCenterUsbInstallNetworkVerifyHooker }
                    }
                    if (processName == packageName || processName == "$packageName.remote") {
                        hook { CarrierOfflineConfigHooker }
                    }
                }
                attachSecurityCenterHookers()
            }
        }
    }
}
