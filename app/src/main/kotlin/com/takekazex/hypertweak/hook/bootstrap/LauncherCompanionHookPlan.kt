package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.aod.AodStatusIconHooker
import com.takekazex.hypertweak.hook.rules.mediaeditor.MediaEditorWatermarkHooker
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.personalassistant.AndroidWidgetPickerHooker
import com.takekazex.hypertweak.hook.rules.personalassistant.ModelSpoofHooker
import com.takekazex.hypertweak.hook.rules.systemui.AODHooker
import com.takekazex.hypertweak.hook.rules.voiceassist.XiaoaiGestureHooker

/** Host-owned installation order; factories run only when the caller installs the plan. */
internal object LauncherCompanionHookPlan {
    fun steps(packageName: String, processName: String): List<HookStep> = buildList {
        when (packageName) {
            "com.miui.home" -> {
                // The launcher rules need no Java hook here: LSPosed injects only the native
                // payload into the launcher, so feature switches reach it through
                // NativeRuleConfig rather than through this process.
            }
            "com.miui.aod" -> {
                hook { RestartBroadcastHooker }
                hook { AODHooker }
                hook { AodStatusIconHooker() }
            }
            "com.miui.voiceassist" -> {
                hook { RestartBroadcastHooker }
                hook { XiaoaiGestureHooker }
            }
            "com.miui.personalassistant" -> {
                // 机型伪装: rewrites phoneModel/phoneDevice in the assistant's request environment
                // so Xiaomi's server delivers the 澎湃G1-gated "智能测算" MAML suit (精准电量).
                hook { ModelSpoofHooker }
                hook { AndroidWidgetPickerHooker }
            }
            "com.miui.mediaeditor" -> {
                hook { MediaEditorWatermarkHooker }
            }
        }
    }
}
