package com.takekazex.hypertweak.hook.base

import android.app.Application
import android.content.Context
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The host process's application context, owned by the module.
 *
 * Replaces EzHookTool's `EzXposed.appContextOrNull` / `runOnApplicationAttach` with the same
 * sources: a context captured from `Application.attach(Context)` (its argument), else
 * `ActivityThread.currentApplication()`. Each module generation starts empty through [reset];
 * the attach hook of a previous generation is an old handle that hot reload unhooks.
 */
object HostContext {
    private const val TAG = "HostContext"
    private const val ATTACH_HOOK_ID = "HyperTweak.ApplicationAttach"

    @Volatile private var module: XposedInterface? = null
    @Volatile private var captured: Context? = null
    @Volatile private var attachHooked = false
    private val callbacks = CopyOnWriteArrayList<(Context) -> Unit>()

    private val currentApplication: Method? by lazy {
        runCatching {
            Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
        }.onFailure { DebugLog.w(TAG, "ActivityThread.currentApplication unavailable", it) }.getOrNull()
    }

    /** Called once per module generation before any hooker runs. */
    fun reset(module: XposedInterface) {
        this.module = module
        captured = null
        attachHooked = false
        callbacks.clear()
    }

    val appContextOrNull: Context?
        get() {
            captured?.let { return it }
            val application = runCatching { currentApplication?.invoke(null) as? Application }.getOrNull()
                ?: return null
            return (application.applicationContext ?: application).also { captured = it }
        }

    /** Runs [callback] for every later `Application.attach`, and now if a context already exists. */
    fun runOnApplicationAttach(callback: (Context) -> Unit) {
        callbacks += callback
        installAttachHook()
        appContextOrNull?.let(callback)
    }

    @Synchronized
    private fun installAttachHook() {
        if (attachHooked) return
        val module = module ?: run {
            DebugLog.w(TAG, "attach hook requested before module initialization")
            return
        }
        val attach = runCatching {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        }.onFailure { DebugLog.w(TAG, "Application.attach(Context) not found", it) }.getOrNull() ?: return
        val factory = HookFactory(attach).apply {
            id(ATTACH_HOOK_ID)
            after { param ->
                val context = param.args.getOrNull(0) as? Context ?: return@after
                captured = context
                callbacks.forEach { callback ->
                    HookFailurePolicy.open(TAG, "attach callback", Unit) { callback(context) }
                }
            }
        }
        runCatching {
            module.hook(attach).setId(ATTACH_HOOK_ID).intercept(factory.buildHooker(TAG))
            attachHooked = true
        }.onFailure { DebugLog.hookFailed(TAG, "Application#attach(Context)", it) }
    }
}
