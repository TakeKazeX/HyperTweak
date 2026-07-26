package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/** Restricts the contextual-search compatibility bridge to the SystemUI and provider calls. */
object ContextualSearchSystemHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "GestureBarCTS"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"

    private val activeBridgedInvocation = ThreadLocal<Boolean>()

    private val resolvedUids = mutableMapOf<String, Int>()

    @Volatile
    private var systemPackageManager: PackageManager? = null

    override fun onPrepareHotReload() {
        // Only the per-invocation flag is process state. The uid cache and PackageManager are
        // rebuilt lazily and stay valid for the life of system_server, and clearing them here
        // used to strand the bridge: systemPackageManager was seeded only from the
        // SystemServer.deviceHasConfigString hook, which runs once during early boot and never
        // again, so after a module hot reload resolveUid() returned -1 forever and every
        // startContextualSearch fell through to enforcePermission.
        activeBridgedInvocation.remove()
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
                    resolveUid(SYSTEM_UI_PACKAGE)
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
                withBridgedInvocation(SYSTEM_UI_PACKAGE) { chain.proceed() }
            }
        }

        // The provider calls back into the service to collect the screenshot and assist data.
        // That call resolves the contextual-search package again, on its own binder thread, so
        // the override has to cover it as well or the service throws on the empty HyperOS value.
        val stateMethod = stubClass.declaredMethods
            .firstOrNull { it.name == "getContextualSearchState" }
            ?.apply { isAccessible = true }
        if (stateMethod == null) {
            DebugLog.w(SCOPE, "contextual search state callback is unavailable")
        } else {
            stateMethod.hook("gesture_bar_cts_provider_callback") {
                intercept { chain ->
                    withBridgedInvocation(GOOGLE_SEARCH_PACKAGE) { chain.proceed() }
                }
            }
        }

        serviceClass.getDeclaredMethod(
            "enforcePermission",
            String::class.java
        ).apply { isAccessible = true }.hook("gesture_bar_cts_permission") {
            intercept { chain ->
                if (activeBridgedInvocation.get() == true) null else chain.proceed()
            }
        }

        serviceClass.getDeclaredMethod("getContextualSearchPackageName")
            .apply { isAccessible = true }
            .hook("gesture_bar_cts_package") {
                intercept { chain ->
                    if (activeBridgedInvocation.get() == true) {
                        GOOGLE_SEARCH_PACKAGE
                    } else {
                        chain.proceed()
                    }
                }
            }
    }

    private inline fun withBridgedInvocation(
        expectedPackage: String,
        proceed: () -> Any?
    ): Any? {
        val expectedUid = resolveUid(expectedPackage)
        if (expectedUid < 0 || Binder.getCallingUid() != expectedUid) return proceed()

        activeBridgedInvocation.set(true)
        return try {
            proceed()
        } finally {
            activeBridgedInvocation.remove()
        }
    }

    /**
     * system_server's own PackageManager. Seeded opportunistically from the boot-time config gate,
     * but that hook runs only once per boot, so fall back to the current ActivityThread — a hot
     * reload lands on a fresh instance whose field is null and would otherwise never recover.
     */
    private fun resolvePackageManager(): PackageManager? {
        systemPackageManager?.let { return it }
        val resolved = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val application = activityThread
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
            application?.packageManager
        }.onFailure {
            DebugLog.w(SCOPE, "failed to resolve system PackageManager", it)
        }.getOrNull() ?: return null
        systemPackageManager = resolved
        return resolved
    }

    private fun resolveUid(packageName: String): Int {
        synchronized(resolvedUids) { resolvedUids[packageName] }?.let { return it }
        val packageManager = resolvePackageManager() ?: return -1
        val resolvedUid = runCatching {
            packageManager.getPackageUid(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        }.onFailure {
            DebugLog.w(SCOPE, "failed to resolve uid for $packageName", it)
        }.getOrDefault(-1)
        if (resolvedUid < 0) return -1
        synchronized(resolvedUids) { resolvedUids[packageName] = resolvedUid }
        DebugLog.i(SCOPE, "resolved $packageName uid=$resolvedUid")
        return resolvedUid
    }
}
