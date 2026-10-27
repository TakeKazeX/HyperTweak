package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.util.backup.SettingsBackupDocument
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Portable settings only; metadata is informational and never enters the restore map. */
internal object SettingsBackupCodec {
    private val types = listOf("boolean", "int", "long", "float", "string", "string_set")

    fun encode(
        values: Map<String, *>,
        createdAtMillis: Long = System.currentTimeMillis(),
        metadata: JSONObject = JSONObject()
    ): String {
        val groups = types.associateWith { JSONObject() }
        values.toSortedMap().forEach { (key, value) ->
            require(key.isNotBlank()) { "Settings backup contains a blank key" }
            val type = when (value) {
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is String -> "string"
                is Set<*>, is Collection<*> -> "string_set"
                else -> throw IllegalArgumentException("Unsupported setting value: $key")
            }
            val encoded = when (type) {
                // JSON numbers cannot preserve all 64-bit integers in common script runtimes.
                "long" -> value.toString()
                "float" -> (value as Float).also {
                    require(it.isFinite()) { "Float setting is invalid: $key" }
                }
                "string_set" -> JSONArray((value as Collection<*>).map {
                    require(it is String) { "String-set setting is invalid: $key" }
                    it
                }.distinct().sorted())
                else -> value
            }
            groups.getValue(type).put(key, encoded)
        }
        val settings = JSONObject()
        groups.forEach { (type, entries) -> if (entries.length() > 0) settings.put(type, entries) }
        return SettingsBackupDocument.validate(JSONObject()
            .put("format", "hypertweak-settings")
            .put("format_version", 2)
            .put("created_at", createdAtMillis)
            .put("metadata", metadata)
            .put("settings", settings)
            .toString())
    }

    /** Decode and validate the entire document before the manager starts any mutation. */
    fun decode(json: String): Map<String, Any> {
        val reader = JSONTokener(SettingsBackupDocument.validate(json))
        val root = reader.nextValue() as? JSONObject
            ?: throw IllegalArgumentException("Settings backup must be an object")
        require(reader.nextClean() == '\u0000') { "Trailing data in settings backup" }
        val version = root.opt("format_version")
        require(version is Number && version.toString() in setOf("1", "2")) {
            "Unsupported settings backup format"
        }
        val settings = root.optJSONObject("settings")
            ?: throw IllegalArgumentException("Settings backup has no settings object")
        val result = LinkedHashMap<String, Any>()
        fun add(key: String, type: String, value: Any, v2: Boolean) {
            require(key.isNotBlank()) { "Settings backup contains a blank key" }
            require(!result.containsKey(key)) { "Duplicate setting across type groups: $key" }
            result[key] = decodeValue(type, value, key, v2)
        }
        if (version.toString() == "1") {
            settings.keys().forEach { key ->
                val entry = settings.optJSONObject(key)
                    ?: throw IllegalArgumentException("Invalid entry for setting: $key")
                add(key, entry.optString("type", ""), entry.get("value"), false)
            }
        } else {
            require(root.opt("format") == "hypertweak-settings") { "Invalid settings backup identity" }
            settings.keys().forEach { type ->
                require(type in types) { "Unknown setting type: $type" }
                val entries = settings.optJSONObject(type)
                    ?: throw IllegalArgumentException("Invalid setting type group: $type")
                entries.keys().forEach { key -> add(key, type, entries.get(key), true) }
            }
        }
        return result
    }

    private fun decodeValue(type: String, value: Any, key: String, v2: Boolean): Any {
        fun invalid(): Nothing = throw IllegalArgumentException("Invalid $type setting: $key")
        fun integer(): Long {
            if (value !is Number) invalid()
            return value.toString().toLongOrNull() ?: invalid()
        }
        return when (type) {
            "boolean" -> value as? Boolean ?: invalid()
            "int" -> integer().also {
                if (it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) invalid()
            }.toInt()
            "long" -> if (v2) {
                if (value !is String || !value.matches(Regex("-?(0|[1-9][0-9]*)"))) invalid()
                value.toLongOrNull() ?: invalid()
            } else integer()
            "float" -> {
                if (value !is Number) invalid()
                val number = value.toDouble()
                val float = number.toFloat()
                if (!number.isFinite() || !float.isFinite()) invalid()
                float
            }
            "string" -> value as? String ?: invalid()
            "string_set" -> {
                if (value !is JSONArray) invalid()
                buildSet { for (index in 0 until value.length()) add(value.get(index) as? String ?: invalid()) }
            }
            else -> throw IllegalArgumentException("Unknown setting type for $key: $type")
        }
    }
}
