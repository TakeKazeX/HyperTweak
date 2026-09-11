package com.takekazex.hypertweak.hook

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned JSON representation used by the settings backup/restore page.
 *
 * SharedPreferences supports a small set of value types, but JSON numbers do not retain their
 * original type. Every entry therefore carries its SharedPreferences type explicitly.
 */
internal object SettingsBackupCodec {
    private const val FORMAT_VERSION = 1
    private const val KEY_FORMAT_VERSION = "format_version"
    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_TYPE = "type"
    private const val KEY_VALUE = "value"

    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_STRING = "string"
    private const val TYPE_STRING_SET = "string_set"

    fun encode(values: Map<String, *>, createdAtMillis: Long = System.currentTimeMillis()): String {
        val settings = JSONObject()
        values.toSortedMap().forEach { (key, value) ->
            encodeValue(value)?.let { settings.put(key, it) }
        }
        return JSONObject()
            .put(KEY_FORMAT_VERSION, FORMAT_VERSION)
            .put(KEY_CREATED_AT, createdAtMillis)
            .put(KEY_SETTINGS, settings)
            .toString(2)
    }

    /** Returns only SharedPreferences-compatible values. Invalid or unsupported entries fail. */
    fun decode(json: String): Map<String, Any> {
        val root = JSONObject(json)
        require(root.optInt(KEY_FORMAT_VERSION, -1) == FORMAT_VERSION) {
            "Unsupported settings backup format"
        }
        val settings = root.optJSONObject(KEY_SETTINGS)
            ?: throw IllegalArgumentException("Settings backup has no settings object")
        val result = LinkedHashMap<String, Any>()
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key.isNotBlank()) { "Settings backup contains a blank key" }
            val entry = settings.optJSONObject(key)
                ?: throw IllegalArgumentException("Invalid entry for setting: $key")
            result[key] = decodeValue(entry, key)
        }
        return result
    }

    private fun encodeValue(value: Any?): JSONObject? {
        val entry = JSONObject()
        when (value) {
            is Boolean -> entry.put(KEY_TYPE, TYPE_BOOLEAN).put(KEY_VALUE, value)
            is Int -> entry.put(KEY_TYPE, TYPE_INT).put(KEY_VALUE, value)
            is Long -> entry.put(KEY_TYPE, TYPE_LONG).put(KEY_VALUE, value)
            is Float -> entry.put(KEY_TYPE, TYPE_FLOAT).put(KEY_VALUE, value.toDouble())
            is String -> entry.put(KEY_TYPE, TYPE_STRING).put(KEY_VALUE, value)
            is Set<*> -> {
                val strings = value.map { it as? String ?: return null }.sorted()
                entry.put(KEY_TYPE, TYPE_STRING_SET).put(KEY_VALUE, JSONArray(strings))
            }
            is Collection<*> -> {
                val strings = value.map { it as? String ?: return null }.sorted()
                entry.put(KEY_TYPE, TYPE_STRING_SET).put(KEY_VALUE, JSONArray(strings))
            }
            else -> return null
        }
        return entry
    }

    private fun decodeValue(entry: JSONObject, key: String): Any {
        val type = entry.optString(KEY_TYPE, "")
        require(entry.has(KEY_VALUE) && entry.opt(KEY_VALUE) != JSONObject.NULL) {
            "Setting has no value: $key"
        }
        return when (type) {
            TYPE_BOOLEAN -> entry.getBoolean(KEY_VALUE)
            TYPE_INT -> entry.getLong(KEY_VALUE).also {
                require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    "Integer setting is out of range: $key"
                }
            }.toInt()
            TYPE_LONG -> entry.getLong(KEY_VALUE)
            TYPE_FLOAT -> entry.getDouble(KEY_VALUE).also {
                require(it.isFinite() && it in -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble()) {
                    "Float setting is invalid: $key"
                }
            }.toFloat()
            TYPE_STRING -> entry.getString(KEY_VALUE)
            TYPE_STRING_SET -> {
                val array = entry.optJSONArray(KEY_VALUE)
                    ?: throw IllegalArgumentException("String-set setting is invalid: $key")
                buildSet {
                    for (index in 0 until array.length()) add(array.getString(index))
                }
            }
            else -> throw IllegalArgumentException("Unknown setting type for $key: $type")
        }
    }
}
