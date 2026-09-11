package com.takekazex.hypertweak.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences

object ShortcutUtils {

    data class ShortcutDef(
        val id: String,
        @StringRes val labelRes: Int,
        val intentAction: String?,
        val intentComponent: String?,
        val intentData: String?,
        val iconPackage: String?,
        val iconRes: Int = 0
    )

    // Labels reuse the Hidden Features strings so the shortcut names stay identical to the
    // in-app rows and only need to be translated once.
    fun getAvailableShortcuts(): List<ShortcutDef> = listOf(
        ShortcutDef("lsposed", R.string.hidden__lsposed_manager, null, "org.lsposed.manager/org.lsposed.manager.ui.activity.MainActivity", null, "org.lsposed.manager", R.drawable.ic_shortcut_lsposed),
        ShortcutDef("installerx", R.string.hidden__installerx_revived, null, "com.android.packageinstaller/com.rosan.installer.ui.activity.SettingsActivity", null, "com.android.packageinstaller", R.drawable.ic_shortcut_installerx),
        ShortcutDef("dev_settings", R.string.hidden__developer_settings, "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", null, null, "com.android.settings"),
        ShortcutDef("google_services", R.string.hidden__google_services, null, "com.google.android.gms/com.google.android.gms.app.settings.GoogleSettingsIALink", null, "com.google.android.gms"),
        ShortcutDef("extra_dim", R.string.hidden__extra_dim, null, "com.android.settings/com.android.settings.Settings\$ReduceBrightColorsSettingsActivity", null, "com.android.settings"),
        ShortcutDef("battery_opt", R.string.hidden__battery_optimization, "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", null, null, "com.android.settings"),
        ShortcutDef("running_services", R.string.hidden__running_services, null, "com.android.settings/com.android.settings.Settings\$RunningServicesActivity", null, "com.android.settings"),
        ShortcutDef("notifications", R.string.hidden__notification_settings, null, "com.android.settings/com.android.settings.Settings\$ConfigureNotificationSettingsActivity", null, "com.android.settings"),
        ShortcutDef("manage_apps", R.string.hidden__manage_applications, null, "com.android.settings/com.android.settings.Settings\$ManageApplicationsActivity", null, "com.android.settings"),
        ShortcutDef("default_apps", R.string.hidden__default_apps, "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", null, null, "com.android.settings")
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
        val bitmap = createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1)
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
        // The launcher pulls shortcut labels from here once and caches them, so resolve them
        // against the app's effective language instead of the raw context locale — the in-app
        // language preference can differ from the device language.
        val localizedContext = LocaleHelper.getLocalizedContext(
            context,
            Preferences.getInt(Preferences.KEY_LANGUAGE, 0)
        )
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
                    if (def.intentData != null) data = def.intentData.toUri()
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            val icon = iconCache.getOrPut(def.id) { loadAppIcon(context, def) }
            val label = localizedContext.getString(def.labelRes)
            ShortcutInfoCompat.Builder(context, def.id)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(icon)
                .setIntent(intent)
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        shortcuts.forEach { shortcut ->
            ShortcutManagerCompat.reportShortcutUsed(context, shortcut.id)
        }
    }
}
