package com.takekazex.hypertweak.hook.rules.aicr

import org.json.JSONArray
import org.json.JSONObject

/** Rewrite the actual AI Panel wire model before EventManager dispatches it to native consumers. */
internal object AicrCopyDirectCueBatch {
    fun rewrite(
        raw: String,
        browser: String,
        label: String,
        applicationLabel: (String) -> String?,
        rewriteWebIntent: (String) -> String?
    ): String? {
        val batch = JSONObject(raw)
        val cues = batch.optJSONArray("cues") ?: return null
        var changed = false
        for (index in 0 until cues.length()) {
            val cue = cues.optJSONObject(index) ?: continue
            val marker = cue.optString("category") + cue.optString("cueId")
            if (!isCopyDirectMarker(marker)) continue
            val actions = cue.optJSONArray("actions") ?: continue
            val intents = ArrayList<Pair<JSONObject, String>>()
            if (!collectWebIntents(actions, intents, rewriteWebIntent) || intents.isEmpty()) continue
            // Routing must not depend on a title, a highlight or an icon being present.
            intents.forEach { (container, uri) -> container.put("intentUri", uri) }
            changed = true
            val display = cue.optJSONObject("display") ?: continue
            val originalPackage = display.optString("targetPackage").takeIf(String::isNotBlank)
            display.put("targetPackage", browser)
            val card = display.optJSONObject("briefCard") ?: continue
            val title = card.optJSONObject("title") ?: JSONObject().also { card.put("title", it) }
            val sourceLabel = originalPackage?.let(applicationLabel)
            val ranges = title.optJSONArray("highlights")
            val highlights = (0 until (ranges?.length() ?: 0)).mapNotNull { rangeIndex ->
                ranges?.optJSONObject(rangeIndex)?.let {
                    AicrBrowserCardText.Range(it.optInt("start", -1), it.optInt("end", -1))
                }
            }
            val text = AicrBrowserCardText.rewrite(title.optString("text"), highlights, sourceLabel, label)
                // A confirmed web card with no usable app-name contract still has a truthful,
                // colored application label; it must never silently keep Xiaomi as its target.
                ?: AicrBrowserCardText.Result(label, listOf(AicrBrowserCardText.Range(0, label.length)))
            title.put("text", text.text)
            title.put("highlights", JSONArray().apply {
                text.highlights.forEach { range ->
                    put(JSONObject().put("start", range.start).put("end", range.end))
                }
            })
            card.put("startIcon", JSONObject().put("type", "application").put("value", browser))
        }
        return if (changed) batch.toString() else null
    }

    // The platform adapter accepts only HTTP(S) ACTION_VIEW intents the chosen browser handles.
    // A mixed card containing another intent is kept intact, including its original metadata.
    private fun collectWebIntents(
        value: Any?,
        result: MutableList<Pair<JSONObject, String>>,
        rewrite: (String) -> String?,
        depth: Int = 0
    ): Boolean {
        if (depth > 16) return false
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) {
                if (!collectWebIntents(value.opt(index), result, rewrite, depth + 1)) return false
            }
            is JSONObject -> {
                if (value.has("intentUri")) {
                    val rewritten = rewrite(value.optString("intentUri")) ?: return false
                    result += value to rewritten
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val child = value.opt(keys.next())
                    if ((child is JSONObject || child is JSONArray) &&
                        !collectWebIntents(child, result, rewrite, depth + 1)
                    ) return false
                }
            }
        }
        return true
    }

    private fun isCopyDirectMarker(value: String): Boolean =
        value.contains("copy_jump", ignoreCase = true) || value.contains("copy_text_jump_app", ignoreCase = true) ||
            value.contains("copy_direct", ignoreCase = true)
}
