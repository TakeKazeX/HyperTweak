package com.takekazex.hypertweak.hook.rules.systemui.notification

import java.util.Locale

internal object ClockSecondsDemand {
    fun active(enabled: Boolean, attached: Boolean, shown: Boolean, windowVisible: Boolean,
        interactive: Boolean, retiring: Boolean): Boolean =
        enabled && attached && shown && windowVisible && interactive && !retiring
}

/** Date pattern is configuration, not per-tick work. Locale equality follows current Resources. */
internal class ClockSecondsPattern {
    private var locale: Locale? = null
    private var hour24 = false
    private var pattern: String? = null
    fun get(nextLocale: Locale, next24: Boolean, resolve: (Locale, String) -> String): String {
        if (locale == nextLocale && hour24 == next24) pattern?.let { return it }
        return resolve(nextLocale, if (next24) "Hms" else "hmsa").also {
            locale = nextLocale; hour24 = next24; pattern = it
        }
    }
    fun clear() { locale = null; pattern = null }
}
