package com.takekazex.hypertweak.hook.nativebridge

import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.takekazex.hypertweak.util.logging.DebugLog

/** Applies acknowledged module settings through the launcher's verified System Settings observers. */
internal class LauncherIconThemeController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val journal = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("hypertweak_launcher_icon_theme_journal", Context.MODE_PRIVATE)
    private val hostSettings = LauncherIconThemeJournal(object : LauncherIconThemeJournal.Port {
        override fun readHost(key: String): String? = Settings.System.getString(context.contentResolver, key)
        override fun writeHost(key: String, value: String?) = Settings.System.putString(context.contentResolver, key, value)
        override fun load(key: String): LauncherIconThemeJournal.Record? =
            if (journal.getBoolean("${key}_owned", false)) LauncherIconThemeJournal.Record(
                journal.getString("${key}_before", null), journal.getString("${key}_last", null)) else null
        override fun save(key: String, record: LauncherIconThemeJournal.Record?): Boolean {
            val editor = journal.edit()
            if (record == null) editor.remove("${key}_owned").remove("${key}_before").remove("${key}_last")
            else editor.putBoolean("${key}_owned", true).putString("${key}_before", record.before).putString("${key}_last", record.last)
            return editor.commit()
        }
    })
    private var latest: NativeRuleStateSnapshot? = null
    private val wallpaper = WallpaperManager.getInstance(context)
    private val colorsChanged = WallpaperManager.OnColorsChangedListener { _, _ -> latest?.let(::submit) }
    init {
        runCatching { wallpaper.addOnColorsChangedListener(colorsChanged, handler) }
            .onFailure { DebugLog.w("LauncherIcons", "wallpaper palette observer unavailable", it) }
    }
    private var previous: List<Any>? = null
    private var active = false
    private var closed = false
    private val refresh = Runnable {
        if (!closed && active) runCatching {
            context.contentResolver.notifyChange(Settings.System.getUriFor(ENABLE), null, 0)
            DebugLog.i("LauncherIcons", "icon refresh notification sent")
        }.onFailure { DebugLog.w("LauncherIcons", "icon refresh notification failed", it) }
    }

    fun submit(snapshot: NativeRuleStateSnapshot) {
        if (closed) return
        latest = snapshot
        runCatching {
            val color = when (snapshot.iconThemeMode) {
                2 -> LauncherIconThemeColors.nativeColor(hostSettings.original(COLOR))
                // The host uses a fixed foreground and pastel backgrounds, not AOSP's
                // paired dark/light foreground palette. Keep its background light.
                3 -> context.getColor(android.R.color.system_accent1_100)
                else -> snapshot.iconThemeColor or -0x1000000
            }
            val signature = listOf(snapshot.iconThemeMode, color)
            active = snapshot.iconThemeMode >= 2
            if (previous == signature) return
            // Journal before the first host write; it survives SystemUI death and module replacement.
            if (snapshot.iconThemeMode >= 2) {
                hostSettings.write(COLOR, color.toString())
                hostSettings.write(ENABLE, "1")
            } else {
                hostSettings.restore(COLOR)
                hostSettings.restore(ENABLE)
            }
            previous = signature
            refreshExisting()
            DebugLog.i("LauncherIcons", "icon theme applied mode=${snapshot.iconThemeMode}")
        }.onFailure { DebugLog.w("LauncherIcons", "icon theme settings application failed", it) }
    }

    /** Replayed after native preparation, including an already running launcher on module upgrade. */
    fun refreshExisting() {
        if (closed || !active) return
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 250)
    }

    fun close() {
        closed = true; latest = null; handler.removeCallbacks(refresh)
        runCatching { wallpaper.removeOnColorsChangedListener(colorsChanged) }
    }
    private companion object {
        const val ENABLE = "key_monochrome"
        const val COLOR = "key_monochrome_color"
    }
}
