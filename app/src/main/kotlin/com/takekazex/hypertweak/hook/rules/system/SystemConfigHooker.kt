package com.takekazex.hypertweak.hook.rules.system

import android.content.pm.FeatureInfo
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method

/** Reversible PM query overlay. Never delete features from the boot-time SystemConfig map. */
object SystemConfigHooker : StaticHooker() {
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_REMOVE_GMS_RESTRICTION, false)
    }
    private val markers = setOf("cn.google.services", "com.google.android.feature.services_updater")
    private var invalidate: Method? by generationState { null }
    private var retired = false
    init { generationCleanup { retired = true; invalidate?.invoke(null) } }

    override fun onHook() {
        retired = false
        val pm = "com.android.server.pm.PackageManagerService".toClassOrNull() ?: return
        val hasFeature = HostAccess.requireDeclaredMethod(pm, "hasSystemFeature", String::class.java,
            Int::class.javaPrimitiveType, accessible = false)
        val binder = "com.android.server.pm.PackageManagerService\$IPackageManagerImpl".toClassOrNull() ?: return
        val allFeatures = HostAccess.requireDeclaredMethod(binder, "getSystemAvailableFeatures", accessible = false)
        val slice = "android.content.pm.ParceledListSlice".toClassOrNull() ?: return
        val getList = HostAccess.requirePublicMethod(slice, "getList", accessible = false)
        val constructor = slice.getConstructor(List::class.java)
        val appPm = "android.app.ApplicationPackageManager".toClassOrNull() ?: return
        invalidate = HostAccess.requireDeclaredMethod(appPm, "invalidateHasSystemFeatureCache", accessible = false)
        deoptimize(hasFeature)
        hasFeature.hook("system.gms_feature_query") {
            before { param ->
                if (!retired && enabled && param.args.firstOrNull() in markers) param.result = false
            }
        }
        deoptimize(allFeatures)
        allFeatures.hook("system.gms_features_list") {
            after { param ->
                if (!retired && enabled) HookFailurePolicy.open(hookerName, "filter features", Unit) {
                    val original = param.result ?: return@open
                    val values = getList.invoke(original) as? List<*> ?: return@open
                    val filtered = values.filterNot { it is FeatureInfo && it.name in markers }
                    if (filtered.size != values.size) param.result = constructor.newInstance(filtered)
                }
            }
        }
        refreshSettings()
    }

    fun refreshSettings(): Boolean {
        val method = invalidate ?: return false
        return runCatching { method.invoke(null); true }.getOrElse {
            DebugLog.w(hookerName, "feature-query cache invalidation failed", it); false
        }
    }

    override fun onPrepareHotReload() {
        retired = true
        refreshSettings()
    }
}
