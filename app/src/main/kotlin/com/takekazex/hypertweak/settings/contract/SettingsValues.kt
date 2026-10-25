package com.takekazex.hypertweak.settings.contract

/** Typed access to the existing storage boundary; contains no feature policy. */
internal interface SettingsValues {
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    fun getInt(key: String, defaultValue: Int = 0): Int
    fun getFloat(key: String, defaultValue: Float = 1f): Float
    fun putBoolean(key: String, value: Boolean): Unit
    fun putInt(key: String, value: Int): Unit
    fun putFloat(key: String, value: Float): Unit
    fun flush(): Unit
    fun getString(key: String, default: String = ""): String
    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String>
    fun putString(key: String, value: String): Unit
    fun putStringSet(key: String, value: Set<String>): Unit
    fun contains(key: String): Boolean
}
