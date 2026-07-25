package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/** Restricts the contextual-search compatibility bridge to calls originating in SystemUI. */
object ContextualSearchSystemHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "GestureBarCTS"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"

    private val activeSystemUiInvocation = ThreadLocal<Boolean>()

    @Volatile
    private var systemUiUid = -1

    @Volatile
    private var systemPackageManager: PackageManager? = null

    override fun onPrepareHotReload() {
        activeSystemUiInvocation.remove()
        systemUiUid = -1
        systemPackageManager = null
    }

    override fun onHook() {
        hookServiceStartupGate()
        hookContextualSearchService()
    }

    private fun hookServiceStartupGate() {
        val contextualSearchPackageId = classLoader
            .loadClass("com.android.internal.R\$string")
            .getDeclaredField("config_defaultContextualSearchPackageName")
            .getInt(null)
        val systemServerClass = classLoader.loadClass("com.android.server.SystemServer")
        val method = systemServerClass.getDeclaredMethod(
            "deviceHasConfigString",
            Context::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }

        method.hook("gesture_bar_cts_service_startup") {
            before { param ->
                val context = param.args.getOrNull(0) as? Context
                if (context != null) {
                    systemPackageManager = context.packageManager
                    resolveSystemUiUidIfNeeded()
                }
                if (param.args.getOrNull(1) == contextualSearchPackageId) {
                    param.result = true
                }
            }
        }
    }

    private fun hookContextualSearchService() {
        val serviceClass = classLoader.loadClass(
            "com.android.server.contextualsearch.ContextualSearchManagerService"
        )
        val stubClass = classLoader.loadClass(
            "com.android.server.contextualsearch.ContextualSearchManagerService\$ContextualSearchManagerStub"
        )

        stubClass.getDeclaredMethod(
            "startContextualSearch",
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }.hook("gesture_bar_cts_systemui_call") {
            intercept { chain ->
                val expectedUid = resolveSystemUiUidIfNeeded()
                if (expectedUid < 0 || Binder.getCallingUid() != expectedUid) {
                    return@intercept chain.proceed()
                }

                activeSystemUiInvocation.set(true)
                try {
                    chain.proceed()
                } finally {
                    activeSystemUiInvocation.remove()
                }
            }
        }

        serviceClass.getDeclaredMethod(
            "enforcePermission",
            String::class.java
        ).apply { isAccessible = true }.hook("gesture_bar_cts_permission") {
            intercept { chain ->
                if (activeSystemUiInvocation.get() == true) null else chain.proceed()
            }
        }

        serviceClass.getDeclaredMethod("getContextualSearchPackageName")
            .apply { isAccessible = true }
            .hook("gesture_bar_cts_package") {
                intercept { chain ->
                    if (activeSystemUiInvocation.get() == true) {
                        GOOGLE_SEARCH_PACKAGE
                    } else {
                        chain.proceed()
                    }
                }
            }
    }

    @Synchronized
    private fun resolveSystemUiUidIfNeeded(): Int {
        if (systemUiUid >= 0) return systemUiUid
        val packageManager = systemPackageManager ?: return -1
        val resolvedUid = runCatching {
            packageManager.getPackageUid(
                SYSTEM_UI_PACKAGE,
                PackageManager.PackageInfoFlags.of(0)
            )
        }.onFailure {
            DebugLog.w(SCOPE, "failed to resolve SystemUI uid", it)
        }.getOrDefault(-1)
        if (resolvedUid >= 0) {
            systemUiUid = resolvedUid
            DebugLog.i(SCOPE, "resolved SystemUI uid=$resolvedUid")
        }
        return systemUiUid
    }
}
