package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.content.res.Configuration
import android.content.res.AssetManager
import android.graphics.Typeface
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import com.takekazex.hypertweak.util.platform.ResourceLookup

/** The standalone slot replaces mobile_type_single, not either half-height SIM label. */
internal object MobileTypeHostMetrics {
    data class Font(val textSizePx: Float, val typeface: Typeface?, val variationSettings: String?)
    private class Cached(context: Context, val font: Font) {
        val configuration = Configuration(context.resources.configuration)
        val assets = WeakReference<AssetManager>(context.resources.assets)
        val theme = WeakReference(context.theme)
    }
    private val cache = WeakHashMap<Context, Cached>()

    @Synchronized
    fun font(context: Context): Font? {
        cache[context]?.takeIf {
            it.configuration == context.resources.configuration &&
                it.assets.get() === context.resources.assets && it.theme.get() === context.theme
        }?.let { return it.font }
        // MiuiMobileIconBinder applies this appearance to mobile_type_single. In contrast,
        // statusbar_signal_size belongs to MobileTypeDrawable's small per-SIM labels.
        val appearance = ResourceLookup.identifier(context.resources, 
            "TextAppearance.StatusBar.Clock", "style", "com.android.systemui"
        )
        if (appearance == 0) return null
        // Let TextView resolve the complete appearance and system font configuration together.
        // Combining its size with MobileTypeDrawable's fixed bold font creates a hybrid baseline.
        val label = TextView(context).apply { setTextAppearance(appearance) }
        val paint = label.paint
        if (!paint.textSize.isFinite() || paint.textSize <= 0f) return null
        return Font(paint.textSize, paint.typeface, paint.fontVariationSettings).also {
            cache[context] = Cached(context, it)
        }
    }

    fun textSizePx(context: Context): Float? = font(context)?.textSizePx
}
