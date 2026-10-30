package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.app.KeyguardManager
import android.content.Context
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method

/** Resolve before admitting taps; unsupported hosts retain their original touch behavior. */
internal object DuoControlCenterClick {
    fun resolve(context: Context): Action? = runCatching {
        val app = context.applicationContext ?: error("SystemUI application unavailable")
        val component = HostAccess.read(app, "mSysUIComponent")
            ?: HostAccess.read(app, "mInitializer")?.let { HostAccess.invoke(it, "getSysUIComponent") }
            ?: error("SystemUI component unavailable")
        val queue = HostAccess.provider(component, "provideCommandQueueProvider")
            ?: error("CommandQueue provider unavailable")
        check(queue.javaClass.name == "com.android.systemui.statusbar.CommandQueue")
        val admission = HostAccess.method(queue, "panelsEnabled")
            ?.takeIf { it.returnType == Boolean::class.javaPrimitiveType }
            ?: error("Panel admission contract unavailable")
        val expand = HostAccess.method(queue, "animateExpandSettingsPanel", String::class.java)
            ?.takeIf { it.returnType == Void.TYPE }
            ?: error("Control-center expansion contract unavailable")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
            ?: error("Keyguard admission unavailable")
        Action(queue, admission, expand, keyguard)
    }.onFailure { DebugLog.w("DuoSignal", "control-center click unavailable", it) }.getOrNull()

    class Action(private val queue: Any, private val admission: Method, private val expand: Method,
                 private val keyguard: KeyguardManager) {
        fun expand() {
            runCatching {
                // HOME can remain attached behind keyguard; tree identity is not a lock policy.
                if (keyguard.isKeyguardLocked || admission.invoke(queue) != true) return
                expand.invoke(queue, null)
            }.onFailure { DebugLog.w("DuoSignal", "control-center click failed", it) }
        }
    }
}
