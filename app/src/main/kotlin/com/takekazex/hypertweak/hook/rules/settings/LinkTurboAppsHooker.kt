package com.takekazex.hypertweak.hook.rules.settings

import android.content.Context
import android.content.Intent
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.ThreadActivation
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Method

/**
 * Lists every launcher app with network access on the network acceleration page, instead of
 * only the cloud default packages. Only the page task's reads are widened, so Settings backups
 * keep the native default list. Each app's switch still starts off and is written by the host.
 */
internal object LinkTurboAppsHooker : StaticHooker() {
    private const val TAG = "LinkTurboApps"
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_LINK_TURBO_ALL_APPS, false)
    }

    private data class Targets(val task: Method, val defaults: Method)

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(TAG, resolve = {
            val index = DexRuntime.operands(apk)
            val contract = LinkTurboAppsContract.resolve(index) ?: return@prepareDexTargets null
            Targets(
                task = index.method(contract.task, classLoader) ?: return@prepareDexTargets null,
                defaults = index.method(contract.defaults, classLoader) ?: return@prepareDexTargets null,
            )
        }) { (task, defaults) ->
            val pageTask = ThreadActivation()
            task.hook("link_turbo_page_task") {
                intercept { chain ->
                    if (enabled && !generationRetired) pageTask.within { chain.proceed() } else chain.proceed()
                }
            }
            defaults.hook("link_turbo_default_packages") {
                after { param ->
                    if (generationRetired || !enabled || !pageTask.active) return@after
                    // A null list means the service is not ready; the task aborts natively.
                    val native = param.result as? List<*> ?: return@after
                    HookFailurePolicy.open(TAG, "widen acceleration app list", Unit) {
                        param.result = withLauncherPackages(native)
                    }
                }
            }
        }
    }

    private fun withLauncherPackages(native: List<*>): List<String> {
        val context = currentApplication() ?: return native.filterIsInstance<String>()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = LinkedHashSet<String>()
        native.filterIsInstanceTo(packages)
        context.packageManager.queryIntentActivities(launcher, 0)
            .mapNotNullTo(packages) { it.activityInfo?.packageName }
        DebugLog.d(TAG, "acceleration candidates: native=${native.size} widened=${packages.size}")
        return ArrayList(packages)
    }

    private val currentApplicationMethod by lazy {
        Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
    }

    private fun currentApplication(): Context? = currentApplicationMethod.invoke(null) as? Context
}
