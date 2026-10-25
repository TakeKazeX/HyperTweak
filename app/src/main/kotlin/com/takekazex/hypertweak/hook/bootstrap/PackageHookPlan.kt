package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.ModuleContext
import com.takekazex.hypertweak.hook.rules.ime.AospImeConfig
import com.takekazex.hypertweak.hook.rules.ime.AospImeHooker

/** Shared lifecycle stays in HookEntry; each host owns its own installation plan. */
internal object PackageHookPlan {
    fun steps(packageName: String, processName: String, selectedIme: Boolean = false): List<HookStep> = buildList {
        if (selectedIme) hook { AospImeHooker }
        addAll(SystemUiHookPlan.steps(packageName, processName))
        addAll(SettingsAppHookPlan.steps(packageName, processName))
        addAll(SecurityCenterHookPlan.steps(packageName, processName))
        addAll(LauncherCompanionHookPlan.steps(packageName, processName))
        addAll(AndroidAppHookPlan.steps(packageName, processName))
        addAll(ServiceAppHookPlan.steps(packageName, processName))
    }

    fun attach(context: ModuleContext, installIcons: () -> Unit, attach: (BaseHooker) -> Unit) {
        for (step in steps(context.packageName, context.processName,
                          AospImeConfig.shouldHookImePackage(context.packageName))) {
            when (step) {
                is HookStep.Install<*> -> attach(step.create())
                HookStep.Icons -> installIcons()
            }
        }
    }
}
