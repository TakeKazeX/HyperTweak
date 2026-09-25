package com.takekazex.hypertweak.ui.effect

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal data class GlassTunerValues(
    val enabled: Boolean,
    val blendAlpha: Float,
    val blendLightness: Float,
    val radiusScale: Float,
    val glassOpacity: Float,
    val glassTone: Float,
)

internal data class GlassTunerPreset(
    val name: String,
    val values: GlassTunerValues,
    val updatedAtMillis: Long,
)

/** UI-only preset persistence and portable clipboard/share-code format. */
internal object GlassTunerPresetStore {
    const val PREFERENCES_NAME = "glass_tuner_presets"
    private const val KEY_PRESETS = "presets_v1"
    private const val SHARE_PREFIX = "HTGLASS1:"
    const val MAX_PRESETS = 24

    fun load(preferences: SharedPreferences): List<GlassTunerPreset> = runCatching {
        val array = JSONArray(preferences.getString(KEY_PRESETS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching {
                    val name = item.getString("name").trim().take(40)
                    require(name.isNotEmpty())
                    add(
                        GlassTunerPreset(
                            name = name,
                            values = readValues(item),
                            updatedAtMillis = item.optLong("updatedAt", 0L),
                        )
                    )
                }
            }
        }.takeLast(MAX_PRESETS)
    }.getOrDefault(emptyList())

    fun save(preferences: SharedPreferences, presets: List<GlassTunerPreset>): Boolean {
        val array = JSONArray()
        presets.takeLast(MAX_PRESETS).forEach { preset ->
            array.put(
                JSONObject()
                    .put("name", preset.name.take(40))
                    .put("updatedAt", preset.updatedAtMillis)
                    .putValues(preset.values)
            )
        }
        return preferences.edit().putString(KEY_PRESETS, array.toString()).commit()
    }

    fun createShareCode(values: GlassTunerValues): String {
        val payload = JSONObject().put("version", 1).putValues(values)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.encodeToString(
            payload,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return SHARE_PREFIX + encoded
    }

    fun parseShareCode(code: String): GlassTunerValues {
        val trimmed = code.trim()
        require(trimmed.startsWith(SHARE_PREFIX))
        require(trimmed.length <= 4096)
        val payload = Base64.decode(
            trimmed.removePrefix(SHARE_PREFIX),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val json = JSONObject(String(payload, StandardCharsets.UTF_8))
        require(json.getInt("version") == 1)
        return readValues(json)
    }

    private fun JSONObject.putValues(values: GlassTunerValues): JSONObject = apply {
        put("enabled", values.enabled)
        put("blendAlpha", values.blendAlpha.toDouble())
        put("blendLightness", values.blendLightness.toDouble())
        put("radiusScale", values.radiusScale.toDouble())
        put("glassOpacity", values.glassOpacity.toDouble())
        put("glassTone", values.glassTone.toDouble())
    }

    private fun readValues(json: JSONObject): GlassTunerValues {
        fun number(key: String, range: ClosedFloatingPointRange<Float>): Float {
            val result = json.getDouble(key).toFloat()
            require(result.isFinite() && result in range)
            return result
        }

        return GlassTunerValues(
            enabled = json.getBoolean("enabled"),
            blendAlpha = number("blendAlpha", 0f..1f),
            blendLightness = number("blendLightness", 0f..2f),
            radiusScale = number("radiusScale", 0f..2f),
            glassOpacity = number("glassOpacity", 0f..1f),
            glassTone = number("glassTone", 0f..2f),
        )
    }
}
