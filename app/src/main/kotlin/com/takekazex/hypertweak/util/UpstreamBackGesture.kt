package com.takekazex.hypertweak.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Installed-package policy; the native layer separately checks the actually loaded owner. */
object UpstreamBackGesture {
    const val PACKAGE = "dev.codex.miuibackgesturehook"
    // Keep takeover closed until a real package context establishes ownership.
    @Volatile var installed: Boolean = true
        private set

    fun refresh(context: Context): Boolean {
        installed = try {
            context.packageManager.getApplicationInfo(PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (error: RuntimeException) {
            DebugLog.w("BackGesture", "Cannot inspect upstream module", error)
            installed
        }
        return installed
    }

    fun settingsIntent(context: Context): Intent? {
        // The module deliberately has no LAUNCHER category. Resolve its public
        // Xposed settings entry instead of guessing an activity class name.
        val intent = Intent(Intent.ACTION_MAIN).setPackage(PACKAGE)
            .addCategory("de.robv.android.xposed.category.MODULE_SETTINGS")
        val activity = context.packageManager.resolveActivity(intent, 0)?.activityInfo
            ?.takeIf { it.exported && it.packageName == PACKAGE } ?: return null
        return intent.setClassName(activity.packageName, activity.name)
    }

    fun openSettings(context: Context): Boolean = try {
        val intent = settingsIntent(context)
        if (intent == null) false else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (error: RuntimeException) {
        DebugLog.w("BackGesture", "Cannot open upstream settings", error)
        false
    }
}
