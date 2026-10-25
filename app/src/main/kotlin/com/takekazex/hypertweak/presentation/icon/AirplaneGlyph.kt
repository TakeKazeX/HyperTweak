package com.takekazex.hypertweak.presentation.icon

import android.content.Context
import android.graphics.drawable.Drawable
import com.takekazex.hypertweak.util.DebugLog

/** Shared host-resource artwork for live status icons and settings previews. */
object AirplaneGlyph {
    private const val MODULE_PACKAGE = "com.takekazex.hypertweak"
    /** Resolve by resource name on each host, never by a build-specific integer ID. */
    fun loadGlyph(context: Context): Drawable? {
        val host = runCatching {
            if (context.packageName == "com.android.systemui") context
            else context.createPackageContext("com.android.systemui", 0)
        }.getOrNull()
        val native = host?.let {
            runCatching {
                val id = it.resources.getIdentifier("stat_sys_signal_flightmode", "drawable", "com.android.systemui")
                if (id == 0) null else it.getDrawable(id)?.mutate()
            }.getOrNull()
        }
        if (native != null) return native
        // Settings previews and hosts without this resource use an exact copy of the OS4 vector.
        return runCatching {
            val module = if (context.packageName == MODULE_PACKAGE) context
                else context.createPackageContext(MODULE_PACKAGE, 0)
            val id = module.resources.getIdentifier("ic_stat_sys_airplane", "drawable", MODULE_PACKAGE)
            if (id == 0) null else module.getDrawable(id)?.mutate()
        }.onFailure { DebugLog.w("AirplaneIcon", "native glyph unavailable", it) }.getOrNull()
    }
}
