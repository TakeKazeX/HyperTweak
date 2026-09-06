package com.takekazex.hypertweak.hook.rules.settings

import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

/** Adds CN third-party packages to the legacy Settings dark-mode list. */
object ForceDarkAppSettingsHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "ForceDarkAppSettings"
    private const val CACHE_CLASS = "com.android.settings.display.util.DarkModeAppCacheManager"
    private const val DETAIL_CLASS = "com.miui.darkmode.DarkModeAppDetailInfo"
    private val enabledOverrides = ConcurrentHashMap<String, Boolean>()

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, false)) return
        val cacheClass = CACHE_CLASS.toClassOrNull() ?: return
        cacheClass.declaredMethods.firstOrNull {
            it.name == "setAppDarkMode" && it.parameterTypes.contentEquals(arrayOf(String::class.java, Boolean::class.javaPrimitiveType))
        }?.apply { isAccessible = true }?.hook("force_dark_capture_app_state") {
            before { param ->
                val packageName = param.args.getOrNull(0) as? String
                val enabled = param.args.getOrNull(1) as? Boolean
                if (packageName != null && enabled != null) enabledOverrides[packageName] = enabled
            }
        }
        val method = cacheClass.declaredMethods.firstOrNull {
            it.name == "getDarkModeAppInfoList" && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true } ?: return
        method.hook("force_dark_settings_app_list") {
            after { param ->
                val current = param.result as? List<Any?> ?: return@after
                val context = runCatching {
                    cacheClass.getDeclaredField("mContext").apply { isAccessible = true }
                        .get(param.thisObject) as? android.content.Context
                }.getOrNull() ?: return@after
                val detailClass = DETAIL_CLASS.toClassOrNull() ?: return@after
                val packageManager = context.packageManager
                val existing = current.mapNotNull { item ->
                    runCatching { item?.javaClass?.getMethod("getPkgName")?.invoke(item) as? String }.getOrNull()
                }.toHashSet()
                val added = current.toMutableList()
                packageManager.getInstalledApplications(0).forEach { app ->
                    if (!isThirdParty(app) || !existing.add(app.packageName)) return@forEach
                    runCatching {
                        val detail = detailClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                        detailClass.getMethod("setLabel", String::class.java).invoke(detail, app.loadLabel(packageManager).toString())
                        detailClass.getMethod("setPkgName", String::class.java).invoke(detail, app.packageName)
                        detailClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                            .invoke(detail, enabledOverrides[app.packageName] ?: false)
                        detailClass.getMethod("setAdaptStatus", Int::class.javaPrimitiveType).invoke(detail, 0)
                        added += detail
                    }
                }
                param.result = added
            }
        }
        DebugLog.i(TAG, "Settings dark-mode app list extension installed")
    }

    private fun isThirdParty(info: ApplicationInfo): Boolean =
        info.uid >= 10000 && (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
}
