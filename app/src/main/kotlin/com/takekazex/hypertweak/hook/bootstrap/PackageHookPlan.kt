package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.ModuleContext
import com.takekazex.hypertweak.hook.rules.ime.AospImeConfig
import com.takekazex.hypertweak.hook.rules.ime.AospImeHooker
import com.takekazex.hypertweak.hook.rules.ime.ImeServiceProcess

/** Shared lifecycle stays in HookEntry; each host owns its own installation plan. */
internal object PackageHookPlan {
    fun steps(packageName: String, processName: String, selectedIme: Boolean = false): List<HookStep> = buildList {
        if (packageName in com.takekazex.hypertweak.settings.contract.AivsPathPolicy.packages) {
            hook { com.takekazex.hypertweak.hook.rules.shared.AivsDirectoryHooker }
        }
        if (packageName == com.takekazex.hypertweak.settings.contract.CommunitySettings.PACKAGE) {
            hook { com.takekazex.hypertweak.hook.rules.community.CommunityHooker }
        }
        if (packageName == com.takekazex.hypertweak.settings.contract.MarketSettingsKeys.PACKAGE && processName == packageName) {
            hook { com.takekazex.hypertweak.hook.rules.market.MarketHooker }
        }
        if (selectedIme) hook { AospImeHooker }
        if (packageName == com.takekazex.hypertweak.settings.contract.OtaNotificationReplay.VIEWER && processName == packageName) {
            hook { com.takekazex.hypertweak.hook.rules.htmlviewer.OtaNotificationReplayHooker }
        }
        addAll(SystemUiHookPlan.steps(packageName, processName))
        addAll(SettingsAppHookPlan.steps(packageName, processName))
        addAll(SecurityCenterHookPlan.steps(packageName, processName))
        addAll(LauncherCompanionHookPlan.steps(packageName, processName))
        addAll(AndroidAppHookPlan.steps(packageName, processName))
        addAll(ServiceAppHookPlan.steps(packageName, processName))
    }

    fun attach(context: ModuleContext, installIcons: () -> Unit, attach: (BaseHooker) -> Unit) {
        val selectedIme = AospImeConfig.shouldHookImePackage(context.packageName) &&
            ImeServiceProcess.hosts(context.packageName, context.processName)
        for (step in steps(context.packageName, context.processName, selectedIme)) {
            when (step) {
                is HookStep.Install<*> -> attach(step.create())
                HookStep.Icons -> installIcons()
            }
        }
    }
}
