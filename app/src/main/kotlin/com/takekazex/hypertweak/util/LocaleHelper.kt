package com.takekazex.hypertweak.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    fun getLocalizedContext(context: Context, langIndex: Int): Context {
        val locale = when (langIndex) {
            1 -> Locale.SIMPLIFIED_CHINESE
            2 -> Locale.ENGLISH
            else -> return context // 0 -> Device Default
        }

        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
