package com.takekazex.hypertweak

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.ui.app.AppContent
import com.takekazex.hypertweak.util.update.UpdateManager

internal fun getSystemAccentColor(context: Context): Int {
    return try {
        context.getColor(android.R.color.system_accent1_500)
    } catch (e: Throwable) {
        0xFF007AFF.toInt()
    }
}

class MainActivity : ComponentActivity() {

    /**
     * Owns the in-app update flow for the module's own process. Created in [onCreate] and torn down
     * in [onDestroy] so its coroutine scope cannot outlive the Activity.
     */
    internal lateinit var updateManager: UpdateManager

    // Intercepted by ModuleStatusHooker. Keep annotation prevents R8 optimization/inlining.
    @Keep
    fun isModuleActive(): Boolean {
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("HyperTweak", "MainActivity onCreate, intent=${intent?.action}")

        val shortcutTarget = intent?.getStringExtra("shortcut_target")
        if (shortcutTarget == "lsposed") {
            Thread {
                try {
                    Runtime.getRuntime().exec("su").outputStream.bufferedWriter().use { w ->
                        w.write("am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733\nexit\n")
                        w.flush()
                    }
                } catch (_: Exception) {}
            }.start()
            finish()
            return
        }

        enableEdgeToEdge()

        // ShortcutService rejects dynamic shortcuts while the launcher alias is disabled.
        // Do not call it at all in that state: some Android builds propagate the
        // service-side IllegalStateException across Binder despite local catches.
        val launcherAliasEnabled = runCatching {
            packageManager.getComponentEnabledSetting(
                ComponentName(this, "com.takekazex.hypertweak.MainActivityAlias")
            ) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(true)
        if (launcherAliasEnabled) {
            runCatching { com.takekazex.hypertweak.util.ShortcutUtils.updateShortcuts(this) }
        }

        window.isNavigationBarContrastEnforced = false

        // The update flow is owned by this Activity and runs only in the module's own process; no
        // hook process constructs it. Reconciliation runs off the main thread below because it
        // queries the package manager and touches the filesystem.
        updateManager = UpdateManager(this)

        setContent { AppContent() }
    }

    override fun onResume() {
        super.onResume()
        // A package update can restart scoped targets without rebinding the service. Re-query when
        // returning to the app so the Home warning does not outlive the target's actual state.
        XposedServiceManager.refreshHotReloadTargets()
        // Also the recovery point for the "allow installs from this source" round trip: if the user
        // granted it while away, the pending install is re-dispatched here.
        if (::updateManager.isInitialized) updateManager.onResume()
    }

    override fun onDestroy() {
        if (::updateManager.isInitialized) updateManager.close()
        super.onDestroy()
    }

internal fun setLauncherIconVisible(context: Context, visible: Boolean) {
    runCatching {
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, "com.takekazex.hypertweak.MainActivityAlias"),
            state,
            PackageManager.DONT_KILL_APP
        )
    }
}
}
