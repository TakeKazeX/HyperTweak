package com.takekazex.hypertweak.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

object LocaleHelper {
    /**
     * Resolve the UI context for the in-app language preference (0 = device default, 1 = 中文, 2 = English).
     *
     * `Locale.setDefault` is process-global, so it has to be applied in BOTH directions. Switching
     * back to "device default" used to return early, leaving the process locale pinned to the
     * previously selected language for the rest of the process lifetime — which then leaked into
     * every `String.format(Locale.getDefault(), ...)` and locale-sensitive comparison.
     */
    fun getLocalizedContext(context: Context, langIndex: Int): Context {
        val locale = when (langIndex) {
            1 -> Locale.SIMPLIFIED_CHINESE
            2 -> Locale.ENGLISH
            else -> {
                // Read the platform locale instead of Locale.getDefault(), which this method mutates.
                Locale.setDefault(systemLocale())
                return context
            }
        }

        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** The system locale, taken from the untouched static system resources. */
    private fun systemLocale(): Locale =
        runCatching { Resources.getSystem().configuration.locales[0] }.getOrNull()
            ?: Locale.getDefault()
}
