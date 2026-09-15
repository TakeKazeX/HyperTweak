package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedInterface

/** Restricts the contextual-search compatibility bridge to the SystemUI and provider calls. */
object ContextualSearchSystemHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "ContextualSearchBridge"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val MIUI_HOME_PACKAGE = "com.miui.home"
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
        // Installed unconditionally, on purpose. The permission bypass is double-gated at
        // invocation time -- the preference must be on AND the Binder caller must be one of the
        // two owners -- so an idle install changes nothing. Gating the install itself would couple
        // the feature to a system_server restart instead: `hookServiceStartupGate` must run during
        // boot to keep the dormant contextual-search service registered, and missing that one
        // opportunity cannot be recovered later. Upstream keeps it available for the same reason.
        hookServiceStartupGate()
        hookContextualSearchService()
    }

    /**
     * The system-side bridge exists solely to make a Circle to Search entry point succeed. There
     * are two independent ones, so either being on is enough:
     * - the long-press power button action set to
     *   [Preferences.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH] (see [PowerButtonCtsHooker]); or
     * - the launcher's own long press on the gesture bar, which the native payload drives (see
     *   [NativeRuleConfig.KEY_CONTEXTUAL_SEARCH_LONG_PRESS]).
     */
    private fun isContextualSearchEnabled(): Boolean =
        Preferences.powerButtonAction() == Preferences.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH ||
            Preferences.contextualSearchLongPress()

    /**
     * Starts Circle to Search from system_server itself (long-press power button). The bridge
     * flag is set without the caller-uid check of [withBridgedInvocation] because the invoking
     * thread is the system process: the service-side hooks then bypass `enforcePermission` and
     * resolve the Google package name for exactly this call, on this thread.
     */
    fun startFromSystemServer(): Boolean {
        // Re-checked live so turning the power-button re-bind off works without a reboot: the
        // bridge flag is never set and the original power action runs. This path is the
        // system_server invocation, so it is gated on the power-button binding specifically --
        // not on [isContextualSearchEnabled], which the launcher entry point also satisfies.
        if (Preferences.powerButtonAction() !=
            Preferences.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH
        ) {
            return false
        }
        activeBridgedInvocation.set(true)
        return try {
            invokeContextualSearchService()
        } catch (t: Throwable) {
            DebugLog.w(SCOPE, "contextual search service failed", t)
            false
        } finally {
            activeBridgedInvocation.remove()
        }
    }

    private fun invokeContextualSearchService(): Boolean {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "contextual_search") as? IBinder ?: return false
        val stubClass = Class.forName(
            "android.app.contextualsearch.IContextualSearchManager\$Stub"
        )
        val service = stubClass.getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: return false
        val interfaceClass = Class.forName(
            "android.app.contextualsearch.IContextualSearchManager"
        )
        val method = interfaceClass.declaredMethods
            .firstOrNull { it.name == "startContextualSearch" } ?: return false
        val configClass = runCatching {
            Class.forName("android.app.contextualsearch.ContextualSearchConfig")
        }.getOrNull()
        // OS4 (Android 16) grew a `ContextualSearchConfig` parameter to the AIDL method;
        // null is fine — the service substitutes `ContextualSearchConfig.DEFAULT_CONFIG`.
        val args = if (configClass != null && method.parameterTypes.lastOrNull() == configClass) {
            arrayOf<Any?>(ENTRY_POINT, null)
        } else {
            arrayOf<Any?>(ENTRY_POINT)
        }
        method.invoke(service, *args)
        return true
    }

    /** Mirrors the nav-handle entrypoint the SystemUI gesture path uses. */
    private const val ENTRY_POINT = 1

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

        // OS4's AIDL grew a `ContextualSearchConfig` parameter, so resolve by name and hook
        // whatever overload this build ships (`(int)` on older platforms, `(int, Config)` here).
        val startMethod = stubClass.declaredMethods
            .firstOrNull { it.name == "startContextualSearch" }
            ?.apply { isAccessible = true }
        if (startMethod == null) {
            DebugLog.hookSkipped(SCOPE, "ContextualSearchManagerStub#startContextualSearch", "method not found")
        } else {
            startMethod.hook("gesture_bar_cts_entry_call") {
                intercept { chain ->
                    // Two callers reach this AIDL: SystemUI when the power button is bound to
                    // Circle to Search, and the launcher when the native payload owns the
                    // gesture-bar long press (the Android 17 arrangement, where the launcher --
                    // not SystemUI -- is the contextual-search owner).
                    withBridgedInvocationFrom(
                        chain,
                        SYSTEM_UI_PACKAGE,
                        MIUI_HOME_PACKAGE
                    ) { chain.proceed() }
                }
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

        // Both methods are small and private, so ART is free to inline them; deoptimize first or the
        // hooks never fire. Mirrors the sibling AOSP-restore hookers.
        val enforcePermissionMethod = serviceClass.getDeclaredMethod(
            "enforcePermission",
            String::class.java
        ).apply { isAccessible = true }
        deoptimize(enforcePermissionMethod)
        enforcePermissionMethod.hook("gesture_bar_cts_permission") {
            intercept { chain ->
                if (activeBridgedInvocation.get() == true) null else chain.proceed()
            }
        }

        val packageNameMethod = serviceClass.getDeclaredMethod("getContextualSearchPackageName")
            .apply { isAccessible = true }
        deoptimize(packageNameMethod)
        packageNameMethod.hook("gesture_bar_cts_package") {
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
        // Re-checked live so turning Circle to Search off takes effect without a reboot: the bridge
        // flag is never set, so enforcePermission and getContextualSearchPackageName run unchanged.
        if (!isContextualSearchEnabled()) return proceed()
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
     * Multi-caller form of [withBridgedInvocation]: the Binder caller must match one of
     * `expectedPackages`. Used where the platform routes the same AIDL through different owners
     * depending on the platform version, so pinning a single package would silently disable the
     * other entry point.
     */
    private inline fun withBridgedInvocationFrom(
        chain: XposedInterface.Chain,
        vararg expectedPackages: String,
        proceed: () -> Any?
    ): Any? {
        // Re-checked live so turning Circle to Search off takes effect without a reboot.
        if (!isContextualSearchEnabled()) return proceed()
        val callingUid = Binder.getCallingUid()
        val callerMatches = expectedPackages.any { expected ->
            val expectedUid = resolveUid(expected)
            expectedUid >= 0 && callingUid == expectedUid
        }
        if (!callerMatches) return proceed()

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
