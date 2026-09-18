package com.takekazex.hypertweak.hook.rules.thememanager

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * Lets the Theme Manager apply locally imported (third-party) themes.
 *
 * The app checks a resource's rights before applying it: `ResourceRightsHelper` calls the DRM
 * service's "check rights isLegal" method, and any verdict other than `DRM_SUCCESS` aborts the
 * apply (the user sees "Themes from third-party sources are not supported"). For a locally
 * imported `.mtz` that check always fails, because there is no rights file matching the theme's
 * component hashes.
 *
 * This hook forces the verdict to `DRM_SUCCESS`. The method is renamed by R8 on every host build,
 * so it is resolved by signature first (the only single-argument method returning MIUI's DRM
 * result enum) and by the upstream string markers second. Verified against Theme Manager
 * 11.1.7.0 on OS4.0.0.31, where both routes resolve the same method.
 *
 * The system_server counterpart ([com.takekazex.hypertweak.hook.rules.system.ThemeDrmRevalidationHooker])
 * is required as well: without it the framework restores the default theme minutes after boot.
 */
object ThemeManagerRightsCheckHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "ThemeManagerDrm"
    private const val PACKAGE = "com.android.thememanager"
    private const val DRM_SERVICE = "com.android.thememanager.controller.online.DrmService"

    /** Stable across host builds: the target method name is obfuscated, this id is not. */
    private const val HOOK_ID = "thememanager_theme_rights_check"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.allowThirdPartyTheme()) {
            DebugLog.hookSkipped(TAG, "theme rights check", "disabled")
            return
        }

        val method = runCatching { resolveRightsCheckMethod() }.getOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "theme rights check", "rights-check method not resolved")
            return
        }
        val success = ThemeDrmSupport.drmSuccessConstant(method.returnType) ?: run {
            DebugLog.hookSkipped(TAG, "theme rights check", "unexpected return type ${method.returnType}")
            return
        }

        val installed = runCatching {
            method.isAccessible = true
            deoptimize(method)
            method.hook(HOOK_ID) {
                before { param ->
                    HookFailurePolicy.open(TAG, "rights check verdict", Unit) {
                        param.result = success
                    }
                }
            }
            true
        }.onFailure { t ->
            DebugLog.hookFailed(TAG, method.toGenericString(), t)
        }.getOrDefault(false)

        if (installed) {
            DebugLog.i(
                TAG,
                "local third-party theme rights check allowed target=${method.declaringClass.name}#${method.name}"
            )
        }
    }

    /**
     * Preferred route: the app's only method that takes a single resource and returns the DRM
     * result enum. The class name is stable in the shipped APK; only its methods are obfuscated.
     */
    private fun resolveRightsCheckMethod(): Method? {
        signatureMatch()?.let { return it }
        return dexKitMatch()
    }

    private fun signatureMatch(): Method? {
        val service = DRM_SERVICE.toClassOrNull() ?: return null
        return service.declaredMethods
            .filter { it.parameterCount == 1 && ThemeDrmSupport.isDrmResult(it.returnType) }
            .singleOrNull()
    }

    /** Fallback route: upstream Ceiler's markers, which survive a class rename. */
    private fun dexKitMatch(): Method? {
        val apkPath = hookParam.appInfo?.sourceDir ?: return null
        return DexKitManager.withBridge(apkPath) { bridge ->
            bridge.findMethod {
                matcher {
                    addUsingString("theme", StringMatchType.Equals)
                    addUsingString("ThemeManagerTag", StringMatchType.Equals)
                    addUsingString("/system", StringMatchType.Equals)
                    addUsingString("check rights isLegal: ", StringMatchType.Equals)
                }
            }.singleOrNull()?.getMethodInstance(classLoader)
        }?.takeIf { ThemeDrmSupport.isDrmResult(it.returnType) }
    }
}
