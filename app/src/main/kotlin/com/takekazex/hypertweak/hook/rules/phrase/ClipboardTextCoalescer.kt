package com.takekazex.hypertweak.hook.rules.phrase

import org.json.JSONArray
import org.json.JSONException

/** The same copy can arrive as a formatted item followed by a plain-text item. */
internal object ClipboardTextCoalescer {
    private const val VARIANT_WINDOW_MS = 2_000L

    private data class Content(val text: String, val dataSize: Long)
    private data class Variant(val content: Content, val formatted: Boolean)
    private data class Pending(val index: Int, val time: Long, val formatted: Boolean)

    fun <T> merge(rows: List<T>, timeOf: (T) -> Long, localTextPayload: (T) -> String?): ArrayList<T> {
        val result = ArrayList<T>(rows.size)
        val pending = HashMap<Content, Pending>()
        for (row in rows) {
            val variant = localTextPayload(row)?.let(::variant)
            val time = if (variant != null) timeOf(row) else 0L
            if (variant != null && time > 0L) {
                val prior = pending[variant.content]
                if (prior != null && prior.formatted != variant.formatted &&
                    maxOf(prior.time, time) - minOf(prior.time, time) <= VARIANT_WINDOW_MS
                ) {
                    if (variant.formatted) result[prior.index] = row
                    pending.remove(variant.content)
                    continue
                }
                pending[variant.content] = Pending(result.size, time, variant.formatted)
            }
            result.add(row)
        }
        return result
    }

    private fun variant(payload: String): Variant? {
        val items = try { JSONArray(payload) } catch (_: JSONException) { return null }
        if (items.length() != 1) return null
        val item = items.optJSONObject(0) ?: return null
        if (item.keys().asSequence().any { it != "text" && it != "htmlText" && it != "dataSize" }) return null
        val text = (item.opt("text") as? String)?.takeIf(String::isNotEmpty) ?: return null
        val formatted = (item.opt("htmlText") as? String)?.isNotEmpty() == true
        return Variant(Content(text, item.optLong("dataSize", 0L)), formatted)
    }
}
