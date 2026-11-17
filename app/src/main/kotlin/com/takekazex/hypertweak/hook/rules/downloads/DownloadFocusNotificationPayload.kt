package com.takekazex.hypertweak.hook.rules.downloads

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The host's own `file_download` focus template, filled from one task snapshot.
 *
 * Expanded: `chatInfo` (file icon, file name, detail line) + `picInfo` + `progressInfo` bar and
 * an optional pause/resume action. Island: file icon + name with a percentage ring; the small
 * island is the file icon inside the same ring. All text layout and animation stay native.
 */
internal object DownloadFocusNotificationPayload {
    /** Native ModuleButtonViewHolder plates (pause_v3 / restart_v3). */
    const val ACTION_PAUSE = "action_pause_record"
    const val ACTION_RESUME = "action_restart_record"
    const val MAX_BYTES = 3072
    /** Island-only copy of the file icon, drawn with an inset so it sits lighter in the pill. */
    const val ISLAND_ICON = "miui.focus.pic_download_island"
    /** Completion check, the same graphic the native recorder uses for its finished island. */
    const val DONE_ICON = "miui.focus.pic_download_done"

    private const val ACCENT = "#3482FF"
    private const val RING_TRACK = "#33FFFFFF"

    class Action(val key: String, val title: String)

    data class Content(
        val fileName: String,
        val detail: String,
        /** Null when the total size is unknown; no percentage is fabricated. */
        val progress: Int?,
        val icon: String,
        val action: Action? = null,
        val completed: Boolean = false,
    )

    fun build(content: Content): String {
        val name = content.fileName.trim().take(128)
        val progress = content.progress?.coerceIn(0, 100)
        val icon = content.icon
        fun pic(key: String = icon) = JSONObject().put("type", 1).put("pic", key).put("picDark", key)
        fun ring(value: Int) = JSONObject().put("progress", value)
            .put("colorReach", ACCENT).put("colorUnReach", RING_TRACK).put("isCCW", true)

        val param = JSONObject()
            .put("protocol", 1)
            .put("business", "file_download")
            .put("islandFirstFloat", false)
            .put("enableFloat", false)
            .put("updatable", !content.completed)
            .put("reopen", "reopen")
            .put("aodTitle", name)
            .put("aodPic", icon)
            .put("ticker", name)
            .put("tickerPic", icon)
            .put("chatInfo", JSONObject()
                .put("picProfile", icon).put("picProfileDark", icon)
                .put("title", name).put("content", content.detail.trim().take(96)))
        if (!content.completed) {
            param.put("picInfo", pic())
            if (progress != null) param.put("progressInfo", JSONObject().put("progress", progress)
                .put("colorProgress", ACCENT).put("colorProgressEnd", ACCENT))
            content.action?.let { action ->
                param.put("actions", JSONArray().put(JSONObject()
                    .put("action", action.key).put("actionTitle", action.title.take(12))
                    .put("actionIcon", action.key).put("actionIconDark", action.key)
                    .put("type", 0)))
            }
        }

        val big = JSONObject().put("imageTextInfoLeft", JSONObject().put("type", 1).put("picInfo", pic(ISLAND_ICON))
            .put("textInfo", JSONObject().put("title", name).put("showHighlightColor", false)))
        val small = JSONObject()
        if (content.completed) {
            // Native done style (recorder): the right side and small island become a check.
            big.put("picInfo", pic(DONE_ICON))
            small.put("picInfo", pic(DONE_ICON))
        } else if (progress != null) {
            big.put("progressTextInfo", JSONObject().put("progressInfo", ring(progress))
                .put("textInfo", JSONObject().put("title", "$progress%").put("showHighlightColor", false)))
            small.put("combinePicInfo", JSONObject().put("picInfo", pic(ISLAND_ICON)).put("progressInfo", ring(progress)))
        } else {
            small.put("picInfo", pic(ISLAND_ICON))
        }
        param.put("param_island", JSONObject().put("islandProperty", 1).put("islandTimeout", 3600)
            .put("bigIslandArea", big).put("smallIslandArea", small))
        return JSONObject().put("param_v2", param).toString()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) "${bytes}B" else String.format(Locale.US, "%.1f%s", value, units[index])
    }

    /** Native `file_icon_*` drawable of the provider for a file name. */
    fun iconKey(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "apk" -> "file_icon_apk"
        "zip", "rar", "7z", "tar", "gz", "bz2" -> "file_icon_zip"
        "pdf" -> "file_icon_pdf"
        "doc", "docx" -> "file_icon_doc"
        "xls", "xlsx" -> "file_icon_xls"
        "ppt", "pptx" -> "file_icon_ppt"
        "txt", "log", "md" -> "file_icon_txt"
        "xml" -> "file_icon_xml"
        "html", "htm" -> "file_icon_html"
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "file_icon_picture"
        "mp4", "mkv", "avi", "mov", "webm", "3gp" -> "file_icon_video"
        "mp3" -> "file_icon_mp3"
        "flac" -> "file_icon_flac"
        "aac" -> "file_icon_aac"
        "wav" -> "file_icon_wav"
        "m4a" -> "file_icon_m4a"
        "ogg" -> "file_icon_ogg"
        "amr" -> "file_icon_amr"
        else -> "file_icon_default"
    }
}
