package com.takekazex.hypertweak.settings.contract.debug

import com.takekazex.hypertweak.settings.contract.CameraLegendaryMomentMode
import com.takekazex.hypertweak.settings.contract.DockConfig
import org.json.JSONArray
import org.json.JSONObject

/** Explicit public inventory. Internal bookkeeping and unknown/retired keys are never writable. */
internal data class DebugSetting(val key: String, val type: String, val default: Any?) {
    val sensitive: Boolean get() = type == "StringSet" || key.contains("url") || key.contains("packages") ||
        key.contains("order") || key.contains("custom") || key == DockConfig.KEY || key.contains("spoof_")
    val writable: Boolean get() = type != "Unknown"
    val range: IntRange? get() = when {
        key == com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys.KEY_ICON_THEME_MODE -> 0..4
        key.startsWith("icon_tuner_slot_") -> 0..4
        key == "opened_folder_columns" -> 3..5
        key in setOf("securitycenter_dock_predict_limit", "statusbar_notif_icon_max") -> 0..15
        key in setOf("securitycenter_low_battery_mode", "securitycenter_permission_warning_mode", "securitycenter_anti_peeping_mode",
            "app_blur_mode", "theme_mode", "icon_left_container_mode") -> 0..2
        key == "power_button_long_press_action" -> 0..3
        key == "battery_auto_power_save_threshold" -> com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys.MIN_BATTERY_AUTO_POWER_SAVE_THRESHOLD..com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys.MAX_BATTERY_AUTO_POWER_SAVE_THRESHOLD
        key == "volume_key_step_count" -> 1..100
        key == "volume_key_step_scope" || key == "icon_stacked_signal_style" -> 0..1
        key in setOf("aod_duo_center_content", "aosp_ime_raise_style", "floating_bar_style",
            "icon_duo_expanded", "icon_tuner_mobile_row_style", "statusbar_region_sampling") -> 0..1
        key in setOf("predictive_back_style", "icon_tuner_wifi_standard_mode", "lockscreen_fingerprint_avoid",
            "notification_header_date_alignment", "notification_header_time_alignment", "notification_header_weather_type",
            "aod_percent_position") -> 0..2
        key == "icon_tuner_position" -> 0..3
        key == "systemui_control_center_tile_message_duration_ms" -> 0..5000
        key == "lockscreen_charging_detail_interval_ms" -> 1000..10000
        key == "icon_duo_size" -> 16..32
        key.startsWith("icon_duo_") && key.endsWith("_scale") -> 50..150
        key == "update_check_interval" -> 0..3
        key == "debug_log_level" -> 2..7
        else -> null
    }

    val floatRange: ClosedFloatingPointRange<Float>? get() = when {
        key == "page_scale" -> 0.85f..1.15f
        key == "notification_header_time_scale" -> com.takekazex.hypertweak.settings.contract.NotificationHeaderModel.MIN_TIME_SCALE..com.takekazex.hypertweak.settings.contract.NotificationHeaderModel.MAX_TIME_SCALE
        key.startsWith("systemui_control_center_corner_") -> 0f..100f
        key.startsWith("glass_tuner_") -> 0f..3f
        key == "icon_tuner_stacked_weight" -> 0f..1f
        key.startsWith("icon_tuner_wifi_padding_") -> 0f..24f
        else -> null
    }

    fun decode(raw: Any): Any {
        require(writable) { "READ_ONLY_SETTING: $key" }
        val value: Any = when (type) {
            "Boolean" -> (raw as? Boolean) ?: error("TYPE_MISMATCH: $key")
            "Int" -> {
                require(raw is Int || raw is Long) { "TYPE_MISMATCH: $key" }
                (raw as Number).toLong().also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "OUT_OF_RANGE: $key" } }.toInt()
            }
            "Float" -> {
                require(raw is Number) { "TYPE_MISMATCH: $key" }
                raw.toFloat().also { require(it.isFinite()) { "OUT_OF_RANGE: $key" } }
            }
            "String" -> (raw as? String)?.also { require(it.length <= 16_384) { "VALUE_TOO_LARGE: $key" } }
                ?: error("TYPE_MISMATCH: $key")
            "StringSet" -> {
                require(raw is JSONArray && raw.length() <= 256) { "TYPE_MISMATCH: $key" }
                (0 until raw.length()).map { index ->
                    (raw.get(index) as? String)?.also { require(it.length <= 512) { "VALUE_TOO_LARGE: $key" } }
                        ?: error("TYPE_MISMATCH: $key")
                }.toSet()
            }
            else -> error("READ_ONLY_SETTING: $key")
        }
        if (value is Float) floatRange?.let { require(value in it) { "OUT_OF_RANGE: $key" } }
        if (value is Int) range?.let { require(value in it) { "OUT_OF_RANGE: $key" } }
        if (key == "update_channel") require(value in setOf("stable", "ci")) { "INVALID_UPDATE_CHANNEL" }
        if (key == "update_proxy_mode") require(value in setOf("official", "third_party")) { "INVALID_PROXY_MODE" }
        if (key in setOf("aosp_ime_nav_bar_start", "aosp_ime_nav_bar_end"))
            require(value in setOf("back", "home_handle", "ime_switcher", "space")) { "INVALID_IME_BUTTON" }
        if (key == DockConfig.KEY) require(value is String && DockConfig.decode(value) != null) { "INVALID_DOCK_CONFIG" }
        if (key == "camera_legendary_moment_mode") require(value in CameraLegendaryMomentMode.MODES) { "INVALID_CAMERA_MODE" }
        return value
    }

    fun describe() = JSONObject().put("key", key).put("type", type)
        .put("default", jsonValue(default)).put("writable", writable).put("sensitive", sensitive)
        .put("range", range?.let { JSONArray(listOf(it.first, it.last)) } ?: JSONObject.NULL)
        .put("float_range", floatRange?.let { JSONArray(listOf(it.start.toDouble(), it.endInclusive.toDouble())) } ?: JSONObject.NULL)
        .put("limits", JSONObject().put("string_chars", 16_384).put("set_items", 256))

    companion object {
        fun jsonValue(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is Set<*> -> JSONArray(value.toList())
            else -> value
        }
    }
}
