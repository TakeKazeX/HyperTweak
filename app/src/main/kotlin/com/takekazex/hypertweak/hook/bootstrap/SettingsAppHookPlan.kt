package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.module.SettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.AdaptiveRefreshSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.AonGestureSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.BluetoothPluginHooker
import com.takekazex.hypertweak.hook.rules.settings.ChannelKeyguardToggleHooker
import com.takekazex.hypertweak.hook.rules.settings.GlobalSettingsInterfaceHooker
import com.takekazex.hypertweak.hook.rules.settings.GoogleServicesSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.HyperOsAiEntriesHooker
import com.takekazex.hypertweak.hook.rules.settings.NotificationBadgeSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.NotificationMoreSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.SpatialAudioHooker
import com.takekazex.hypertweak.hook.rules.settings.VisualPerceptionSettingsHooker
import com.takekazex.hypertweak.hook.rules.system.AdaptiveRefreshRuntimeHooker
import com.takekazex.hypertweak.hook.rules.system.PasskeyHooker
import com.takekazex.hypertweak.hook.rules.systemui.AODHooker

/** Host-owned installation order; factories run only when the caller installs the plan. */
internal object SettingsAppHookPlan {
    fun steps(packageName: String, processName: String): List<HookStep> = buildList {
        when (packageName) {
            "com.android.settings" -> {
                // Flip Settings' process-local MIUI build flags before its own classes initialize.
                hook { GlobalSettingsInterfaceHooker }
                hook { RestartBroadcastHooker }
                hook { SettingsHooker }
                hook { AODHooker }
                hook { PasskeyHooker }
                // The Bluetooth extension is loaded by Settings, so its UI hook must be
                // attached in this process as well as the Bluetooth service process.
                hook { BluetoothPluginHooker }
                hook { SpatialAudioHooker() }
                hook { VisualPerceptionSettingsHooker }
                // Experimental: link the orphaned 左右挥手 / 隔空暂停或播放 pages into the AON
                // 隔空手势 landing list (stock ROM never references those fragments).
                hook { AonGestureSettingsHooker }
                // 自适应刷新率Pro (Mimotion PWM): force ro.display.enable_pwm_switch so the
                // 显示与亮度 mimotion_pwm_enable row is not removed by MiuiDisplaySettings on
                // builds where the vendor prop is unset (see AdaptiveRefreshRuntimeHooker).
                hook { AdaptiveRefreshSettingsHooker }
                // Reveal the per-channel 锁屏通知（allow_keyguard）switch in the notification channel
                // page; only effective when the SystemUI-side lockscreen-all-notifications hook is on.
                hook { ChannelKeyguardToggleHooker }
                // Reveal + wire up the channel page's importance drop-down and badge checkbox
                // (HyperOS ships both as shells with no write-back listener).
                hook { NotificationMoreSettingsHooker }
                hook { NotificationBadgeSettingsHooker }
                // Restore Settings' own Google services home-page header on domestic builds.
                hook { GoogleServicesSettingsHooker }
                // Reveal the 小米澎湃AI page's 端侧大模型 / Gemini rows through the shared dispatcher.
                hook { HyperOsAiEntriesHooker }
            }
        }
    }
}
