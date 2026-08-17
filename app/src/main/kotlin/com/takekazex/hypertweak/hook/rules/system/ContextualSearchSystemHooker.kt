package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarAction
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
        // The bridge relaxes system_server permission checks and forces the service on, so it must
        // not be installed unless the Circle to Search gesture action that needs it is actually on.
        if (!isCircleToSearchActionEnabled()) {
            DebugLog.hookSkipped(SCOPE, "contextual search bridge", "Circle to Search disabled")
            return
        }
        hookServiceStartupGate()
        hookContextualSearchService()
    }

    /**
     * The system-side bridge exists solely to make SystemUI's Circle to Search gesture action
     * succeed, so it follows the same live predicate the SystemUI-side hookers read: gesture-bar
     * actions enabled, with Circle to Search bound to the long-press or double-tap slot, or the
     * long-press power button re-bind ([Preferences.KEY_POWER_BUTTON_CTS]). Mirrors
     * [com.takekazex.hypertweak.hook.rules.systemui.GestureBarActionHooker] /
     * [VoiceInteractionServiceRepairHooker] / [PowerButtonCtsHooker].
     */
    private fun isCircleToSearchActionEnabled(): Boolean {
        if (Preferences.getBoolean(Preferences.KEY_POWER_BUTTON_CTS, false)) {
            return true
        }
        if (!Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED, false)) {
            return false
        }
        val longPress = GestureBarAction.fromPersistedId(
            Preferences.getInt(
                Preferences.KEY_GESTURE_BAR_LONG_PRESS_ACTION,
                GestureBarAction.DEFAULT_ASSISTANT.persistedId
            )
        )
        val doubleTap = GestureBarAction.fromPersistedId(
            Preferences.getInt(
                Preferences.KEY_GESTURE_BAR_DOUBLE_TAP_ACTION,
                GestureBarAction.CIRCLE_TO_SEARCH.persistedId
            )
        )
        return longPress == GestureBarAction.CIRCLE_TO_SEARCH ||
            doubleTap == GestureBarAction.CIRCLE_TO_SEARCH
    }

    /**
     * Starts Circle to Search from system_server itself (long-press power button). The bridge
     * flag is set without the caller-uid check of [withBridgedInvocation] because the invoking
     * thread is the system process: the service-side hooks then bypass `enforcePermission` and
     * resolve the Google package name for exactly this call, on this thread.
     */
    fun startFromSystemServer(): Boolean {
        // Re-checked live so turning the power-button re-bind off works without a reboot: the
        // bridge flag is never set and the original power action runs.
        if (!isCircleToSearchActionEnabled()) return false
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
            startMethod.hook("gesture_bar_cts_systemui_call") {
                intercept { chain ->
                    withBridgedInvocation(SYSTEM_UI_PACKAGE) { chain.proceed() }
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
        if (!isCircleToSearchActionEnabled()) return proceed()
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
