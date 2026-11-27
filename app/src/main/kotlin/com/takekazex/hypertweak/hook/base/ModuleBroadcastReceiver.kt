package com.takekazex.hypertweak.hook.base

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.takekazex.hypertweak.util.ignoreFailure

/**
 * A receiver registered inside a host process. Use it instead of a bare [BroadcastReceiver].
 *
 * `LoadedApk` sets the receiver's class loader on the delivered Intent's extras before
 * `onReceive`. In system_server delivery is in-process, so that Intent is ActivityManagerService's
 * own record, which its broadcast history keeps long after this generation retires; the Bundle then
 * pins the module loader and its DEX. The loader is reset once [receive] returns.
 */
abstract class ModuleBroadcastReceiver : BroadcastReceiver() {
    protected abstract fun receive(context: Context, intent: Intent)

    final override fun onReceive(context: Context, intent: Intent) {
        try { receive(context, intent) } finally { releaseModuleLoader(intent) }
    }

    companion object {
        private val frameworkLoader: ClassLoader? = Intent::class.java.classLoader

        /** For a receiver that cannot extend this class: call once the intent is no longer read. */
        @JvmStatic fun releaseModuleLoader(intent: Intent?) {
            ignoreFailure { intent?.setExtrasClassLoader(frameworkLoader) }
        }
    }
}
