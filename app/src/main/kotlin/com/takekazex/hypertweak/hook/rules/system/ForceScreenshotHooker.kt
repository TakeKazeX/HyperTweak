package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.ThreadActivation
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Modifier

/** System-server screenshot policy. SKIP_SCREENSHOT and protected buffers are independent. */
object ForceScreenshotHooker : StaticHooker() {
    override val hookerName = "ForceScreenshot"
    @Volatile private var enabled = false
    @Volatile private var ready = false
    @Volatile private var failure: Throwable? = null
    private val displayCapture = ThreadActivation()
    private var restoreNativeSurfaces: (() -> Boolean)? by generationState { null }
    private val recovery by lazy {
        SystemServerStartupRetry(hookerName, action = { restoreNativeSurfaces?.invoke() ?: true })
    }

    fun refreshSettings() {
        enabled = ready && failure == null && Preferences.getBoolean(PlatformSettingsKeys.KEY_FORCE_SCREENSHOT, false)
        DebugLog.i(hookerName, "settings applied enabled=$enabled ready=$ready failed=${failure != null}")
        failure?.let { DebugLog.w(hookerName, "capture policy disabled after host failure", it) }
        if (!enabled && restoreNativeSurfaces != null) recovery.schedule()
    }
    override fun recoverHotReloadHosts() {
        refreshSettings()
        recovery.schedule()
    }
    override fun onPrepareHotReload() {
        enabled = false
        ready = false
        recovery.cancel()
    }

    override fun onHook() {
        ready = false
        failure = null
        runCatching {
            // Choose by available API/constructor contracts, never by a platform version table.
            val families = listOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture")
                .mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }
                .filter { owner -> owner.declaredMethods.any { it.name == "nativeCaptureLayers" } }
            val owner = families.single()
            fun nested(name: String) = classLoader.loadClass(owner.name + "$" + name)
            val capture = nested("CaptureArgs")
            val builder = nested("CaptureArgs\$Builder")
            val layerBuilder = nested("LayerCaptureArgs\$Builder")
            val surface = classLoader.loadClass("android.view.SurfaceControl")
            val setter = builder.declaredMethods.single {
                it.name in setOf("setSecureContentPolicy", "setCaptureSecureLayers") && it.parameterCount == 1
            }
            val captureValue: Any = if (setter.parameterTypes.single() == Boolean::class.javaPrimitiveType) true
                else classLoader.loadClass("android.window.ScreenCapture\$ScreenCaptureParams")
                    .getDeclaredField("SECURE_CONTENT_POLICY_CAPTURE").apply { isAccessible = true }.getInt(null)
            val contract = ForceScreenshotContract.resolve(capture, builder, layerBuilder, surface, setter, captureValue)
            val native = owner.declaredMethods.filter { it.name in setOf("nativeCaptureDisplay", "nativeCaptureLayers") }
            check(native.size == 2 && native.all { Modifier.isNative(it.modifiers) && Modifier.isStatic(it.modifiers) &&
                it.parameterCount >= 2 && capture.isAssignableFrom(it.parameterTypes[0]) })
            val wm = classLoader.loadClass("com.android.server.wm.WindowManagerService")
            val root = classLoader.loadClass("com.android.server.wm.RootWindowContainer")
            val vendorGate = ForceScreenshotVendorGate.resolve(
                classLoader.loadClass("com.android.server.wm.WindowManagerServiceStub"), root)
            val surfaceRecovery = ForceScreenshotSurfaceRecovery.resolve(wm, root,
                classLoader.loadClass("com.android.server.wm.WindowManagerGlobalLock"))
            val checkWindow = classLoader.loadClass("android.os.ServiceManager")
                .getDeclaredMethod("checkService", String::class.java).apply { isAccessible = true }
            restoreNativeSurfaces = {
                // This runs on the existing bounded main-thread recovery scheduler, never inside
                // a capture callback. Refuse proxies: restoration requires our local WMS instance.
                val service = checkWindow.invoke(null, "window")
                if (service == null || !wm.isInstance(service)) false else {
                    surfaceRecovery.restore(service)
                    true
                }
            }
            val captures = wm.declaredMethods.filter { it.name == "captureDisplay" &&
                it.parameterTypes.any(capture::isAssignableFrom) }
            check(captures.isNotEmpty())
            // Deoptimize the Binder entry itself: its compiled caller must reach the selected
            // concrete gate, rather than an inlined vendor check from before module installation.
            (captures + vendorGate).forEach { check(deoptimize(it)) { "Cannot deoptimize screenshot gate: $it" } }
            vendorGate.hook {
                before { param ->
                    // READ_FRAME_BUFFER was already checked by WMS. Only its explicit screenshot
                    // scope bypasses the secure-window verdict; AppOps and content assessment stay native.
                    if (enabled && failure == null && displayCapture.active) param.result = false
                }
            }
            captures.forEach { method ->
                method.hook { intercept { chain -> displayCapture.within { chain.proceed() } } }
            }
            native.forEach { method ->
                method.hook {
                    intercept { chain ->
                        // Task snapshots also use captureLayers under the WM lock. Leave their
                        // capture arguments stock; only the explicit display request owns this override.
                        if (!enabled || failure != null || method.name == "nativeCaptureLayers" && !displayCapture.active) {
                            return@intercept chain.proceed()
                        }
                        val args = chain.args[0] ?: return@intercept chain.proceed()
                        contract.withCapturePolicy(args, { failure = it; enabled = false }) { chain.proceed() }
                    }
                }
            }
            ready = true
            refreshSettings()
            recovery.schedule()
            DebugLog.i(hookerName, "system screenshot contract installed policy=${contract.policy.name} vendorGate=${vendorGate.declaringClass.name}#${vendorGate.name}")
        }.onFailure { DebugLog.hookFailed(hookerName, "system screenshot contract", it) }
    }
}
