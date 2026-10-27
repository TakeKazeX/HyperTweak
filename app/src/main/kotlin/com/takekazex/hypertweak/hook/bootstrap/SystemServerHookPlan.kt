package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.ime.AospImeConfig
import com.takekazex.hypertweak.hook.rules.ime.AospImeSystemHooker
import com.takekazex.hypertweak.hook.rules.system.NativePhotoPickerHooker
import com.takekazex.hypertweak.hook.rules.system.NativeFilePickerHooker
import com.takekazex.hypertweak.hook.rules.system.AospPackageInstallerHooker
import com.takekazex.hypertweak.hook.rules.system.SystemConfigHooker
import com.takekazex.hypertweak.hook.rules.system.ThemeDrmRevalidationHooker
import com.takekazex.hypertweak.hook.rules.system.CircleToSearchGestureHooker
import com.takekazex.hypertweak.hook.rules.system.ContextualSearchSystemHooker
import com.takekazex.hypertweak.hook.rules.system.DefaultAssistantHooker
import com.takekazex.hypertweak.hook.rules.system.PowerButtonCtsHooker
import com.takekazex.hypertweak.hook.rules.system.PasskeyHooker
import com.takekazex.hypertweak.hook.rules.system.AonRuntimeGateHooker
import com.takekazex.hypertweak.hook.rules.system.VolumeKeyStepsHooker
import com.takekazex.hypertweak.hook.rules.system.AonGestureFeatureHooker
import com.takekazex.hypertweak.hook.rules.system.AdaptiveRefreshRuntimeHooker
import com.takekazex.hypertweak.hook.rules.system.ForceDarkAppListHooker
import com.takekazex.hypertweak.hook.rules.settings.AdaptiveRefreshSettingsHooker
import com.takekazex.hypertweak.hook.rules.system.FcmLiveSystemHooker
import com.takekazex.hypertweak.hook.rules.system.SystemServerReadinessHooker
import com.takekazex.hypertweak.hook.rules.system.GestureBarTransitionHooker
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackSystemHooker
import com.takekazex.hypertweak.hook.base.BaseHooker

/** Ordered boot-classpath installation; lifecycle remains owned by HookEntry. */
internal object SystemServerHookPlan {
    fun steps(imeEnabled: Boolean): List<HookStep.Install<*>> = buildList<HookStep> {
        hook { SystemServerReadinessHooker }
        hook { AospBackSystemHooker }
        hook { com.takekazex.hypertweak.hook.rules.system.DockBackgroundHooker }
        hook { com.takekazex.hypertweak.hook.rules.system.ForceScreenshotHooker }
        hook { SystemConfigHooker }
        // The enabled com.android.systemui.gesture.line.overlay turns
        // config_attachNavBarToAppDuringTransition off, so the gesture bar stays a separate layer
        // during the swipe-up recents transition. DisplayPolicy caches it in system_server.
        hook { GestureBarTransitionHooker }
        hook { ContextualSearchSystemHooker }
        hook { PowerButtonCtsHooker }
        // Aligns the AOSP assistant setting with the assistant selected in 默认应用 so the
        // platform's own assist path can launch it (see the hooker's comment for what HyperOS
        // leaves inconsistent).
        hook { DefaultAssistantHooker }
        // Binds the launcher's gesture-bar long press to a route that reaches Circle to Search
        // instead of XiaoAI; the native terminal patch alone is not enough (see the hooker).
        hook { CircleToSearchGestureHooker }
        hook { PasskeyHooker }
        hook { FcmLiveSystemHooker }
        hook { NativePhotoPickerHooker }
        hook { NativeFilePickerHooker }
        hook { AospPackageInstallerHooker }
        // AON visual-perception runtime gates (感知锁屏/靠近亮屏/非注视感知) are resource bools read by
        // miui-services inside system_server; the Settings-side unlock alone cannot activate them.
        hook { AonRuntimeGateHooker }
        hook { VolumeKeyStepsHooker }
        // Experimental: let the AON air-gesture controller honour left/right + double-press, which
        // the stock ROM always zeroes for AON (it only enables up/down).
        hook { AonGestureFeatureHooker }
        // 自适应刷新率Pro (Mimotion PWM): force ro.display.enable_pwm_switch so
        // DisplayFeatureManagerService's boot-time setDimmingMode() re-applies the Secure mode after
        // a reboot on builds where the vendor prop is unset (see AdaptiveRefreshSettingsHooker).
        hook { AdaptiveRefreshRuntimeHooker }
        // Dark-mode app data is returned through the uimode Binder. Keep system_server as the
        // single source of truth so Settings and app processes observe the same persistent state.
        hook { com.takekazex.hypertweak.hook.rules.system.DarkModeScheduleHooker }
        hook { ForceDarkAppListHooker }
        // Stops the framework's periodic theme DRM re-validation from restoring the default theme
        // when a locally imported (third-party) theme is applied.
        hook { ThemeDrmRevalidationHooker }
        if (imeEnabled) {
            add(imeStep())
        }
    }.map { it as HookStep.Install<*> }

    private fun imeStep() = HookStep.Install(AospImeSystemHooker::class.java) { AospImeSystemHooker }

    fun install(imeEnabled: () -> Boolean, install: (HookStep.Install<*>) -> Unit) {
        steps(false).forEach(install)
        // Preserve the original read boundary: readiness and boot hooks run first.
        if (imeEnabled()) install(imeStep())
    }

    fun attach(attach: (BaseHooker) -> Unit) {
        install({ true }) { attach(it.create()) }
    }
}
