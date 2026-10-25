package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.aicr.AicrBrowserWhitelistHooker
import com.takekazex.hypertweak.hook.rules.aicr.AicrDefaultBrowserHooker
import com.takekazex.hypertweak.hook.rules.gms.QuickSharePhenotypeHooker
import com.takekazex.hypertweak.hook.rules.googleapp.GoogleAppProcessPolicy
import com.takekazex.hypertweak.hook.rules.googleapp.GoogleAppRuntime
import com.takekazex.hypertweak.hook.rules.guardprovider.GuardProviderEnvironmentCheckHooker
import com.takekazex.hypertweak.hook.rules.guardprovider.GuardProviderUploadAppListHooker
import com.takekazex.hypertweak.hook.rules.lbe.LbeClipboardToastHooker
import com.takekazex.hypertweak.hook.rules.milink.MiLinkHpplayHooker
import com.takekazex.hypertweak.hook.rules.module.ModuleStatusHooker
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.powerkeeper.FcmLivePowerKeeperHooker
import com.takekazex.hypertweak.hook.rules.screenshot.ScreenshotAutoFrameHooker
import com.takekazex.hypertweak.hook.rules.screenshot.ScreenshotClipboardHooker
import com.takekazex.hypertweak.hook.rules.screenshot.ScreenshotSaveDirectoryHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.SecurityCoreBubbleAppListHooker
import com.takekazex.hypertweak.hook.rules.settings.BluetoothPluginHooker
import com.takekazex.hypertweak.hook.rules.system.PasskeyHooker
import com.takekazex.hypertweak.hook.rules.system.SpatialAudioBlockerHooker
import com.takekazex.hypertweak.hook.rules.system.ThemeDrmRevalidationHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUiScreenshotSoundHooker
import com.takekazex.hypertweak.hook.rules.thememanager.ThemeManagerRightsCheckHooker
import com.takekazex.hypertweak.hook.rules.trustservice.MiTrustRiskMonitoringHooker
import com.takekazex.hypertweak.hook.rules.xmsf.UnlockFocusAuthHooker

/** Host-owned installation order; factories run only when the caller installs the plan. */
internal object ServiceAppHookPlan {
    fun steps(packageName: String, processName: String): List<HookStep> = buildList {
        when (packageName) {
            "com.miui.screenshot" -> {
                // HyperOS delegates normal screenshots to this process. Its provider hard-codes
                // screenshot.ogg, which is the short AOSP-style click on this CN build; redirect
                // that URI to the ROM's longer shutter sound when the lockscreen-bar tweak is on.
                hook { SystemUiScreenshotSoundHooker }
                // Frame preparation must start before the two full save-family walks, so a cold
                // first capture has the best chance of reaching a fully protected save contract.
                hook { ScreenshotAutoFrameHooker }
                hook { ScreenshotSaveDirectoryHooker }
                hook { ScreenshotClipboardHooker }
            }
            "com.lbe.security.miui" -> {
                hook { LbeClipboardToastHooker }
            }
            "com.miui.securitycore" -> {
                hook { RestartBroadcastHooker }
                hook { SecurityCoreBubbleAppListHooker }
            }
            "com.miui.powerkeeper" -> {
                hook { RestartBroadcastHooker }
                hook { FcmLivePowerKeeperHooker }
            }
            "com.google.android.gms" -> {
                // Only the phenotype DB write runs in GMS (see QuickSharePhenotypeHooker);
                // GMS is added to the scope dynamically when the Quick Share switch is on.
                hook { QuickSharePhenotypeHooker }
            }
            "com.google.android.googlequicksearchbox" -> {
                // Keep the package scope for Lens/OMNI features, but dispatch only in their UI process.
                if (GoogleAppProcessPolicy.ownsUi(packageName, processName)) {
                    hook { GoogleAppRuntime }
                }
            }
            "com.android.thememanager" -> {
                // Local/third-party theme apply gate. The framework-side counterpart lives in
                // system_server (ThemeDrmRevalidationHooker).
                hook { ThemeManagerRightsCheckHooker }
            }
            "com.xiaomi.xmsf" -> {
                hook { RestartBroadcastHooker }
                hook { UnlockFocusAuthHooker }
            }
            "com.xiaomi.scanner" -> {
                hook { RestartBroadcastHooker }
                hook { PasskeyHooker }
            }
            "com.xiaomi.trustservice" -> {
                hook { RestartBroadcastHooker }
                hook { MiTrustRiskMonitoringHooker }
            }
            "com.miui.guardprovider" -> {
                hook { RestartBroadcastHooker }
                hook { GuardProviderEnvironmentCheckHooker }
                hook { GuardProviderUploadAppListHooker }
            }
            "com.milink.service" -> {
                hook { RestartBroadcastHooker }
                hook { MiLinkHpplayHooker }
                hook { SpatialAudioBlockerHooker }
            }
            "com.xiaomi.bluetooth" -> {
                hook { RestartBroadcastHooker }
                hook { BluetoothPluginHooker }
                hook { SpatialAudioBlockerHooker }
            }
            "com.xiaomi.aicr" -> {
                hook { AicrBrowserWhitelistHooker }
                hook { AicrDefaultBrowserHooker }
            }
            "com.takekazex.hypertweak" -> {
                hook { ModuleStatusHooker }
            }
        }
    }
}
