package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.thememanager.ThemeDrmSupport
import com.takekazex.hypertweak.util.DebugLog

/**
 * Keeps the framework from reverting a locally imported (third-party) theme.
 *
 * `com.android.server.am.ActivityManagerServiceImpl#finishBooting` registers MIUI's
 * [miui.drm.ThemeReceiver] in system_server and schedules an alarm (first run 30 minutes after
 * boot, then every 8 hours). On each run the receiver walks `/data/system/theme/` and the boot
 * animation path, asks `miui.drm.DrmManager#isLegal` per file, and calls
 * `ThemeRuntimeManager#resetDefault` for every failure — which is how an applied third-party
 * theme disappears after a while even when the Theme Manager app allowed the apply.
 *
 * The bypass is deliberately scoped to that re-validation instead of disabling `isLegal`
 * globally: `DrmManager` also answers trial/ad queries in system_server, and those must keep
 * seeing the real verdict. A thread-local set around `ThemeReceiver#validateTheme` gives exactly
 * the upstream Ceiler behaviour while leaving every other caller untouched.
 *
 * `miui.drm.*` lives in `miui-framework.jar` on the boot classpath, so the classes resolve from
 * system_server's own loader. Verified byte-identical against OS4.0.0.31 `miui-framework.jar`.
 */
object ThemeDrmRevalidationHooker : StaticHooker() {
    private const val TAG = "ThemeDrm"
    private const val DRM_MANAGER = "miui.drm.DrmManager"
    private const val THEME_RECEIVER = "miui.drm.ThemeReceiver"
    private const val VALIDATE_THEME = "validateTheme"

    /** Marks the re-validation call chain on its own thread; AsyncTask runs it off the main one. */
    private val revalidating = ThreadLocal<Boolean>()

    override fun onHook() {
        if (!Preferences.allowThirdPartyTheme()) {
            DebugLog.hookSkipped(TAG, "theme DRM re-validation", "disabled")
            return
        }

        val drmManager = DRM_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "theme DRM re-validation", "$DRM_MANAGER unavailable")
            return
        }
        val overloads = ThemeDrmSupport.isLegalOverloads(drmManager)
        val success = ThemeDrmSupport.drmSuccessConstant(overloads.firstOrNull()?.returnType) ?: run {
            DebugLog.hookSkipped(TAG, "theme DRM re-validation", "no isLegal verdict carrier found")
            return
        }

        // The verdict hooks go in first: the receiver hook below can run before this method
        // returns only on another thread, and it must never observe a half-installed bypass.
        var installed = 0
        overloads.forEach { method ->
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook {
                    before { param ->
                        if (revalidating.get() != true) return@before
                        HookFailurePolicy.open(TAG, "isLegal re-validation verdict", Unit) {
                            param.result = success
                        }
                    }
                }
                installed++
            }.onFailure { t ->
                DebugLog.hookFailed(TAG, method.toGenericString(), t)
            }
        }
        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "theme DRM re-validation", "no isLegal verdict hook installed")
            return
        }

        val receiver = THEME_RECEIVER.toClassOrNull()?.declaredMethods
            ?.singleOrNull { it.name == VALIDATE_THEME && it.parameterCount == 4 }
        if (receiver == null) {
            DebugLog.hookSkipped(TAG, "theme DRM re-validation", "$THEME_RECEIVER#$VALIDATE_THEME unavailable")
            return
        }
        val receiverHooked = runCatching {
            receiver.isAccessible = true
            deoptimize(receiver)
            receiver.hook {
                intercept { chain ->
                    revalidating.set(true)
                    try {
                        chain.proceed()
                    } finally {
                        revalidating.remove()
                    }
                }
            }
            true
        }.onFailure { t ->
            DebugLog.hookFailed(TAG, receiver.toGenericString(), t)
        }.getOrDefault(false)

        if (receiverHooked) {
            DebugLog.i(TAG, "third-party themes survive framework re-validation verdicts=$installed")
        }
    }
}
