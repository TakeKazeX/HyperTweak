package com.takekazex.hypertweak.hook.preferences

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsBackupCodecTest {
    private val values = linkedMapOf<String, Any>(
        "explicit_default" to false, "int_min" to Int.MIN_VALUE, "int_max" to Int.MAX_VALUE,
        "long_min" to Long.MIN_VALUE, "long_max" to Long.MAX_VALUE, "zero_long" to 0L,
        "fraction" to 0.1f, "float_max" to Float.MAX_VALUE, "float_min" to Float.MIN_VALUE,
        "text" to "中文\n\"quoted\"", "empty_text" to "", "set" to setOf("乙", "甲"),
        "empty_set" to emptySet<String>()
    )

    private fun legacy(values: Map<String, Any>): String {
        val entries = JSONObject()
        values.forEach { (key, value) ->
            val type = when (value) {
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is String -> "string"
                else -> "string_set"
            }
            val encoded = when (value) {
                is Set<*> -> JSONArray(value.sortedBy { it.toString() })
                is Float -> value.toDouble()
                else -> value
            }
            entries.put(key, JSONObject().put("type", type).put("value", encoded))
        }
        return JSONObject().put("format_version", 1).put("created_at", 123L)
            .put("settings", entries).toString(2)
    }

    @Test fun bothVersionsPreservePreferenceTypesAndExplicitDefaults() {
        assertEquals(values, SettingsBackupCodec.decode(legacy(values)))
        val encoded = SettingsBackupCodec.encode(values, 123)
        assertEquals(values, SettingsBackupCodec.decode(encoded))
        val groups = JSONObject(encoded).getJSONObject("settings")
        assertEquals(Long.MAX_VALUE.toString(), groups.getJSONObject("long").get("long_max"))
        assertFalse(encoded.contains('\n'))
        assertFalse(SettingsBackupCodec.decode(encoded).containsKey("absent_default"))
        assertEquals(emptyMap<String, Any>(), SettingsBackupCodec.decode(SettingsBackupCodec.encode(emptyMap<String, Any>())))
    }

    @Test fun metadataNeverBecomesRestorableSettingsAndOrderingIsStable() {
        val metadata = JSONObject().put("module", JSONObject().put("version_code", 42))
            .put("prefs_epoch", 999).put("user_flag", true)
        val encoded = SettingsBackupCodec.encode(values, 123, metadata)
        assertEquals(42, JSONObject(encoded).getJSONObject("metadata").getJSONObject("module").getInt("version_code"))
        assertEquals(values, SettingsBackupCodec.decode(encoded))
        assertEquals(encoded, SettingsBackupCodec.encode(values.entries.reversed().associate { it.toPair() }, 123, metadata))
    }

    @Test fun malformedDocumentsFailBeforeReturningAnySettings() {
        val invalidGroups = listOf(
            "{\"boolean\":{\"a\":\"true\"}}", "{\"int\":{\"a\":1.5}}",
            "{\"int\":{\"a\":2147483648}}", "{\"long\":{\"a\":9223372036854775807}}",
            "{\"long\":{\"a\":\"9223372036854775808\"}}", "{\"long\":{\"a\":\"1e3\"}}",
            "{\"float\":{\"a\":3.5e38}}", "{\"string\":{\"a\":1}}",
            "{\"string_set\":{\"a\":[1]}}", "{\"string\":{\"a\":null}}",
            "{\"string\":{\" \":\"x\"}}", "{\"unknown\":{}}", "{\"boolean\":[]}",
            "{\"boolean\":{\"a\":true},\"int\":{\"a\":1}}"
        )
        invalidGroups.forEach { groups ->
            rejected("{\"format\":\"hypertweak-settings\",\"format_version\":2,\"settings\":$groups}")
        }
        rejected("{\"format_version\":3,\"settings\":{}}")
        rejected("{\"format_version\":\"2\",\"settings\":{}}")
        rejected("{\"format_version\":2,\"settings\":{}}")
        rejected(SettingsBackupCodec.encode(emptyMap<String, Any>()) + " {}")
        rejected("[]")
        rejected("{\"format_version\":1,\"settings\":{\"a\":{\"type\":\"int\",\"value\":1.5}}}")
        rejected("{\"format_version\":1,\"settings\":{\"a\":{\"type\":\"string\",\"value\":false}}}")
    }

    @Test fun unsupportedExportValuesCannotSilentlyDisappear() {
        listOf<Any>(Double.NaN, Float.POSITIVE_INFINITY, setOf(1), Any()).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SettingsBackupCodec.encode(mapOf("a" to value)) }
        }
    }

    @Test fun groupedFormatReducesRepresentativeUtf8PayloadIncludingMetadata() {
        val typical = (0 until 200).associate { "feature_setting_$it" to (it % 2 == 0) }
        val metadata = JSONObject().put("snapshot_source", "backend")
            .put("module", JSONObject().put("version_name", "2.0.0-beta").put("version_code", 600))
            .put("system", JSONObject().put("device", "example").put("api", 36)
                .put("android_release", "16").put("build_fingerprint", "vendor/device/product:16/build/incremental:user/release-keys"))
        for ((name, sample) in listOf("empty" to emptyMap<String, Any>(), "mixed" to values, "200_boolean" to typical)) {
            val v1 = legacy(sample).toByteArray(Charsets.UTF_8).size
            val v2 = SettingsBackupCodec.encode(sample, 123, metadata).toByteArray(Charsets.UTF_8).size
            println("BACKUP_SIZE $name v1=$v1 v2=$v2")
            if (name == "200_boolean") assertTrue("Typical payload should shrink by at least 40%", v2 < v1 * 0.6)
        }
    }

    private fun rejected(json: String) {
        assertThrows("Must reject $json", IllegalArgumentException::class.java) { SettingsBackupCodec.decode(json) }
    }
}
