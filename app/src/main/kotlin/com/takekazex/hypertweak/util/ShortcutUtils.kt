package com.takekazex.hypertweak.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.takekazex.hypertweak.hook.Preferences

object ShortcutUtils {

    data class ShortcutDef(
        val id: String,
        val label: String,
        val intentAction: String?,
        val intentComponent: String?,
        val intentData: String?,
        val iconPackage: String?,
        val iconRes: Int = 0
    )

    fun getAvailableShortcuts(): List<ShortcutDef> = listOf(
        ShortcutDef("lsposed", "LSPosed Manager", null, "org.lsposed.manager/org.lsposed.manager.ui.activity.MainActivity", null, "org.lsposed.manager", com.takekazex.hypertweak.R.drawable.ic_shortcut_lsposed),
        ShortcutDef("installerx", "InstallerX Revived", null, "com.android.packageinstaller/com.rosan.installer.ui.activity.SettingsActivity", null, "com.android.packageinstaller", com.takekazex.hypertweak.R.drawable.ic_shortcut_installerx),
        ShortcutDef("dev_settings", "Developer Settings", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", null, null, "com.android.settings"),
        ShortcutDef("google_services", "Google Services", null, "com.google.android.gms/com.google.android.gms.app.settings.GoogleSettingsIALink", null, "com.google.android.gms"),
        ShortcutDef("extra_dim", "Extra Dim", null, "com.android.settings/com.android.settings.Settings\$ReduceBrightColorsSettingsActivity", null, "com.android.settings"),
        ShortcutDef("battery_opt", "Battery Optimization", "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", null, null, "com.android.settings"),
        ShortcutDef("running_services", "Running Services", null, "com.android.settings/com.android.settings.Settings\$RunningServicesActivity", null, "com.android.settings"),
        ShortcutDef("notifications", "Notification Settings", null, "com.android.settings/com.android.settings.Settings\$ConfigureNotificationSettingsActivity", null, "com.android.settings"),
        ShortcutDef("manage_apps", "Manage Applications", null, "com.android.settings/com.android.settings.Settings\$ManageApplicationsActivity", null, "com.android.settings"),
        ShortcutDef("default_apps", "Default Apps", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", null, null, "com.android.settings")
    )

    fun getEnabledShortcutIds(): Set<String> {
        val defaults = setOf("lsposed", "installerx", "dev_settings")
        return Preferences.getStringSet(Preferences.KEY_APP_SHORTCUTS, defaults)
    }

    fun getOrderedList(): List<String> {
        val orderStr = Preferences.getString(Preferences.KEY_APP_SHORTCUTS_ORDER, "")
        if (orderStr.isNotEmpty()) return orderStr.split(",").filter { it.isNotEmpty() }
        return getEnabledShortcutIds().toList()
    }

    fun saveOrder(orderedIds: List<String>) {
        Preferences.putString(Preferences.KEY_APP_SHORTCUTS_ORDER, orderedIds.joinToString(","))
        Preferences.putStringSet(Preferences.KEY_APP_SHORTCUTS, orderedIds.toSet())
    }

    private fun loadAppIcon(context: Context, def: ShortcutDef): IconCompat {
        if (def.iconRes != 0) return IconCompat.createWithResource(context, def.iconRes)
        val pm = context.packageManager
        val drawable = runCatching { pm.getApplicationIcon(def.iconPackage ?: "") }.getOrNull()
            ?: pm.defaultActivityIcon
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return IconCompat.createWithBitmap(bitmap)
    }

    fun updateShortcuts(context: Context) {
        val orderedIds = getOrderedList()
        val allDefs = getAvailableShortcuts().associateBy { it.id }
        val iconCache = mutableMapOf<String, IconCompat>()
        val shortcuts = orderedIds.take(5).mapNotNull { id ->
            val def = allDefs[id] ?: return@mapNotNull null
            val intent = if (def.id == "lsposed") {
                Intent(Intent.ACTION_MAIN, null, context, com.takekazex.hypertweak.MainActivity::class.java).apply {
                    putExtra("shortcut_target", def.id)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            } else {
                Intent(def.intentAction ?: Intent.ACTION_VIEW).apply {
                    if (def.intentComponent != null) {
                        val parts = def.intentComponent.split("/")
                        setClassName(parts[0], parts[1])
                    }
                    if (def.intentData != null) data = Uri.parse(def.intentData)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            val icon = iconCache.getOrPut(def.id) { loadAppIcon(context, def) }
            ShortcutInfoCompat.Builder(context, def.id)
                .setShortLabel(def.label)
                .setLongLabel(def.label)
                .setIcon(icon)
                .setIntent(intent)
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }
}
