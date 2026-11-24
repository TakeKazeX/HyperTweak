package com.takekazex.hypertweak.hook.rules.milink

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.InstallPolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * MiLink half of "send all apps": lets every app pass MiLink's local app-cast (sub-screen) allow
 * list. The server-side relay allow list lives in Mirror (`MirrorAllAppsHooker`). Empty names, the
 * default launcher and MiLink itself keep the native answer, so the null-task guards stay intact.
 */
internal object MiLinkSubScreenAppsHooker : StaticHooker() {
    private const val TAG = "MiLinkSubScreenApps"

    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_MILINK_UNLOCK_SEND_APP, false)
    }

    override val installPolicy = InstallPolicy.WHEN_ENABLED
    override fun isFeatureEnabled() = enabled

    override fun onHook() {
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(TAG, resolve = {
            DexRuntime.resolve(
                feature = MiLinkSubScreenAppsContract,
                apkPath = apk,
                cacheDir = dexCacheDirectory,
                compute = { MiLinkSubScreenAppsContract.resolve(DexRuntime.operands(apk)) },
                decode = { values, _ -> DexContractMembers(values, classLoader) },
            )
        }) { members ->
            val member = members.method("member")
            // The member test is tiny; callers that inlined it would never reach the hook.
            members.methods("callers").forEach { caller ->
                if (!deoptimize(caller)) DebugLog.w(TAG, "caller stays optimized: ${caller.name}")
            }
            member.hook("milink_sub_screen_apps") {
                intercept { chain ->
                    val pkg = chain.args.firstOrNull() as? String
                    if (!enabled || generationRetired || pkg.isNullOrEmpty()) return@intercept chain.proceed()
                    HookFailurePolicy.open(TAG, "allow sub-screen app", null) {
                        if (keepsNative(pkg)) null else true
                    } ?: chain.proceed()
                }
            }
            DebugLog.hookRegistered(TAG, "sub-screen allow list ready")
        }
    }

    private fun keepsNative(pkg: String): Boolean {
        val context = currentApplication() ?: return true
        if (pkg == context.packageName) return true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == pkg
    }

    private val currentApplicationMethod by lazy {
        Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
    }

    private fun currentApplication(): Context? = currentApplicationMethod.invoke(null) as? Context
}
