package com.takekazex.hypertweak.hook

import android.content.SharedPreferences

internal fun SharedPreferences.Editor.putPreferenceValue(key: String, value: Any) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        else -> error("Unsupported restored setting type: ${value::class.java.name}")
    }
}
