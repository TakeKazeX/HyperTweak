package com.takekazex.hypertweak.hook.rules.system

import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/** Exposes third-party applications in HyperOS's per-app force-dark list. */
object ForceDarkAppListHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "ForceDarkAppList"
    private const val CLASS_NAME = "com.android.server.ForceDarkAppListManager"
    private const val METHOD_NAME = "shouldShowInSettings"
    private const val LIST_METHOD_NAME = "getDarkModeAppList"

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, false)) return
        val managerClass = CLASS_NAME.toClassOrNull() ?: run {
            DebugLog.w(TAG, "ForceDarkAppListManager unavailable")
            return
        }
        val method = managerClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == METHOD_NAME &&
                candidate.parameterTypes.contentEquals(arrayOf(ApplicationInfo::class.java)) &&
                candidate.returnType == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true } ?: run {
            DebugLog.w(TAG, "shouldShowInSettings(ApplicationInfo) unavailable")
            return
        }
        method.hook("force_dark_show_third_party") {
            after { param ->
                if (Preferences.getBoolean(Preferences.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, false)) {
                    param.result = true
                }
            }
        }
        managerClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == "getAppDarkModeEnable" && candidate.parameterTypes.contentEquals(
                arrayOf(String::class.java, Int::class.javaPrimitiveType)
            ) && candidate.returnType == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true }?.hook("force_dark_state_read") {
            after { param ->
                // Keep the vendor SecurityManager-backed value as the source of truth. This hook is
                // deliberately present at the service boundary so callers use the same state as Settings.
                if (!Preferences.getBoolean(Preferences.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, false)) return@after
                param.result = param.result as? Boolean ?: false
            }
        }
        managerClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == "getAppForceDarkOrigin" && candidate.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                candidate.returnType == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true }?.hook("force_dark_origin") {
            after { param ->
                if (!Preferences.getBoolean(Preferences.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, false)) return@after
                val packageName = param.args.getOrNull(0) as? String ?: return@after
                val pm = runCatching { managerClass.getDeclaredField("mPackageManager").apply { isAccessible = true }.get(param.thisObject) as? android.content.pm.PackageManager }.getOrNull()
                val app = runCatching { pm?.getApplicationInfo(packageName, 0) }.getOrNull()
                if (app != null && isThirdParty(app)) param.result = false
            }
        }
        managerClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == LIST_METHOD_NAME && candidate.parameterTypes.size == 2
        }?.apply { isAccessible = true }?.hook("force_dark_list_prepare") {
            before {
                // The CN build guard is a static final field in some releases. Set it at the
                // actual list-generation boundary so the value is available before the loop.
                runCatching {
                    Class.forName("miui.os.Build", false, managerClass.classLoader)
                        .getDeclaredField("IS_INTERNATIONAL_BUILD")
                        .apply { isAccessible = true }
                        .setBoolean(null, true)
                }
            }
            after { param ->
                // The returned DarkModeAppData is the system source consumed by Settings and WM.
                // Add third-party records here, not in Settings, so the toggle state survives and
                // the system's own getAppDarkModeEnable() sees the same package set.
                val data = param.result ?: return@after
                val userId = (param.args.getOrNull(1) as? Int) ?: 0
                runCatching {
                    val pm = managerClass.getDeclaredField("mPackageManager").apply { isAccessible = true }
                        .get(param.thisObject) as android.content.pm.PackageManager
                    val getter = data.javaClass.methods.first { it.name == "getDarkModeAppDetailInfoList" && it.parameterTypes.isEmpty() }
                    val setter = data.javaClass.methods.first { it.name == "setDarkModeAppDetailInfoList" && it.parameterTypes.size == 1 }
                    val list = (getter.invoke(data) as? MutableList<Any?>)?.toMutableList() ?: return@runCatching
                    val names = list.mapNotNull { it?.javaClass?.methods?.firstOrNull { m -> m.name == "getPkgName" }?.invoke(it) as? String }.toHashSet()
                    val detailClass = Class.forName("com.miui.darkmode.DarkModeAppDetailInfo", false, managerClass.classLoader)
                    pm.getInstalledApplications(0).filter(::isThirdParty).forEach { app ->
                        if (!names.add(app.packageName)) return@forEach
                        val detail = detailClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                        detailClass.getMethod("setLabel", String::class.java).invoke(detail, app.loadLabel(pm).toString())
                        detailClass.getMethod("setPkgName", String::class.java).invoke(detail, app.packageName)
                        val enabled = runCatching {
                            managerClass.getDeclaredMethod("getAppDarkModeEnable", String::class.java, Int::class.javaPrimitiveType)
                                .apply { isAccessible = true }.invoke(param.thisObject, app.packageName, userId) as Boolean
                        }.getOrDefault(false)
                        detailClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType).invoke(detail, enabled)
                        list += detail
                    }
                    setter.invoke(data, list)
                }.onFailure { DebugLog.w(TAG, "failed to complete system dark-mode list", it) }
            }
        }
        DebugLog.i(TAG, "third-party force-dark list/state/origin hooks installed")
    }

    private fun isThirdParty(info: ApplicationInfo): Boolean =
        info.uid >= 10000 && (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
            !info.packageName.startsWith("com.google.")
}
