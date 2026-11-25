package com.takekazex.hypertweak.hook.rules.ime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethod
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Keyboards may run their InputMethodService outside the main process (WeType uses `:hld`), and
 * every AOSP IME hook acts on that service's window. The install decision reads the declared
 * `android:process` of the package's `android.view.InputMethod` services instead of naming
 * packages. When the answer is unknown (no PackageManager yet, a failed query, or no declared
 * service), it returns true so installation keeps the previous every-process behavior.
 */
internal object ImeServiceProcess {
    private const val TAG = "AospIme"

    fun hosts(packageName: String, processName: String): Boolean {
        val processes = runCatching { serviceProcesses(packageName) }
            .onFailure { DebugLog.w(TAG, "IME service process lookup failed package=$packageName", it) }
            .getOrNull()
        if (processes.isNullOrEmpty()) return true
        val hosts = processName in processes
        if (!hosts) DebugLog.d(TAG, "IME hooks skipped process=$processName serviceProcesses=$processes")
        return hosts
    }

    private fun serviceProcesses(packageName: String): Set<String>? {
        val packageManager = packageManager() ?: return null
        val intent = Intent(InputMethod.SERVICE_INTERFACE).setPackage(packageName)
        return packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            .mapNotNullTo(LinkedHashSet()) { it.serviceInfo?.processName }
    }

    /** Package load precedes the Application, so fall back to the process's system context. */
    private fun packageManager(): PackageManager? {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = HostAccess.requirePublicMethod(threadClass, "currentActivityThread", accessible = false)
            .invoke(null) ?: return null
        val context = HostAccess.requirePublicMethod(threadClass, "currentApplication", accessible = false)
            .invoke(null) as? Context
            ?: HostAccess.requirePublicMethod(threadClass, "getSystemContext", accessible = false)
                .invoke(thread) as? Context
        return context?.packageManager
    }
}
