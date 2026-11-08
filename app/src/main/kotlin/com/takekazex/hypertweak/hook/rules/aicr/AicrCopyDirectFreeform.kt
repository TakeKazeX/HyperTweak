package com.takekazex.hypertweak.hook.rules.aicr

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.FreeformSettingsKeys
import org.json.JSONObject

private const val ACTION_PREFIX = "urn:aiot-spec-v3:com.mi.phones:action:[com.xiaomi.aicr/context/"
private const val RESOLVE_INTENT = "${ACTION_PREFIX}resolve_intent]:0:1.0"

internal enum class AicrCopyDirectAction(val marker: String) {
    COMMAND("command"),
    LOCATION("location"),
    TRACKING("tracking");

    fun acceptsTool(tool: String?): Boolean = when (this) {
        COMMAND, TRACKING -> tool == RESOLVE_INTENT
        LOCATION -> tool in mapSearchTools
    }

    companion object {
        const val EXTRA = "com.takekazex.hypertweak.extra.COPY_DIRECT_SCENE"
        private val mapTools = mapOf(
            "cue.navigate_to_gaode_map_app" to "jumpToGaoDeSearch",
            "cue.navigate_to_baidu_map_app" to "jumpToBaiduMapSearch",
            "cue.navigate_to_tencent_map_app" to "jumpToTencentMapSearch",
        ).mapValues { (_, handler) -> "$ACTION_PREFIX$handler]:0:1.0" }
        private val mapSearchTools = mapTools.values.toSet()

        fun fromMarker(value: String?) = entries.firstOrNull { it.marker == value }

        fun fromRequest(queryId: String?, tool: String?): AicrCopyDirectAction? {
            val parts = queryId?.split('#', limit = 3) ?: return null
            if (parts.size != 3 || parts[2].isBlank()) return null
            return when (parts[1]) {
                "hce.copy_text_jump_app.command" ->
                    COMMAND.takeIf { parts[0] == "cue.copy_text_jump_app" && it.acceptsTool(tool) }
                "hce.copy_text_jump_app.tracking_number" ->
                    TRACKING.takeIf { parts[0] == "cue.copy_text_jump_app" && it.acceptsTool(tool) }
                "hce.navigate_to_map_app.address" ->
                    LOCATION.takeIf { tool != null && mapTools[parts[0]] == tool }
                else -> null
            }
        }
    }

    fun markRequest(raw: String, tool: String?): String? {
        val action = JSONObject(raw)
        if (action.optString("type") != tool) return null
        val input = action.optJSONObject("in") ?: return null
        input.put(EXTRA, marker)
        return action.toString()
    }
}

internal object AicrCopyDirectFreeformSettings {
    private val jump by hookSetting(false) { Preferences.getBoolean(FreeformSettingsKeys.KEY_JUMP, false) }
    private val command by hookSetting(false) {
        Preferences.getBoolean(FreeformSettingsKeys.KEY_COPY_DIRECT_COMMAND, false)
    }
    private val location by hookSetting(false) {
        Preferences.getBoolean(FreeformSettingsKeys.KEY_COPY_DIRECT_LOCATION, false)
    }
    private val tracking by hookSetting(false) {
        Preferences.getBoolean(FreeformSettingsKeys.KEY_COPY_DIRECT_TRACKING, false)
    }

    fun anyEnabled() = jump && (command || location || tracking)

    fun enabled(action: AicrCopyDirectAction) = jump && when (action) {
        AicrCopyDirectAction.COMMAND -> command
        AicrCopyDirectAction.LOCATION -> location
        AicrCopyDirectAction.TRACKING -> tracking
    }
}
