package com.takekazex.hypertweak.hook.rules.aicr

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AicrCopyDirectCueBatchTest {
    private fun batch(category: String = "copy_jump", intent: String = "https://example.com/page") = """
        {"version":1,"extra":"retained","cues":[{
          "cueId":"cue.copy_text_jump_app#test","category":"$category",
          "actions":[{"inParams":{"intentUri":"$intent"}}],
          "display":{"targetPackage":"vendor.browser","briefCard":{
            "title":{"text":"在原浏览器中打开","highlights":[{"start":1,"end":5}]},
            "description":{"text":"页面标题"},"startIcon":{"type":"builtin","value":"vendor_icon"}
          }}
        }]}
    """.trimIndent()

    private fun rewrite(raw: String, pkg: String, label: String): String? =
        AicrCopyDirectCueBatch.rewrite(raw, pkg, label, { "原浏览器" }) {
            if (it.startsWith("https://")) "$it#target=$pkg" else null
        }

    @Test
    fun anyBrowserGetsOneConsistentNativeCardWithItsActualLabelAndColorRange() {
        for ((pkg, label) in listOf("com.microsoft.emmx" to "Microsoft Edge", "mark.via" to "Via",
            "org.mozilla.firefox" to "Firefox", "unknown.browser" to "任意🌏浏览器")) {
            val json = JSONObject(rewrite(batch(), pkg, label)!!)
            val cue = json.getJSONArray("cues").getJSONObject(0)
            val display = cue.getJSONObject("display")
            val card = display.getJSONObject("briefCard")
            val title = card.getJSONObject("title")
            assertEquals("在${label}中打开", title.getString("text"))
            val range = title.getJSONArray("highlights").getJSONObject(0)
            assertEquals(label, title.getString("text").substring(range.getInt("start"), range.getInt("end")))
            assertEquals(pkg, display.getString("targetPackage"))
            assertEquals("application", card.getJSONObject("startIcon").getString("type"))
            assertEquals(pkg, card.getJSONObject("startIcon").getString("value"))
            assertTrue(cue.getJSONArray("actions").getJSONObject(0).getJSONObject("inParams")
                .getString("intentUri").endsWith("target=$pkg"))
            assertEquals("页面标题", card.getJSONObject("description").getString("text"))
            assertEquals("retained", json.getString("extra"))
        }
    }

    @Test
    fun aDifferentDefaultBrowserReplacesThePreviousApplicationsFullNativeRange() {
        val edge = rewrite(batch(), "com.microsoft.emmx", "Microsoft Edge")!!
        val via = JSONObject(rewrite(edge, "mark.via", "Via")!!)
        val title = via.getJSONArray("cues").getJSONObject(0).getJSONObject("display")
            .getJSONObject("briefCard").getJSONObject("title")
        assertEquals("在Via中打开", title.getString("text"))
        assertEquals(4, title.getJSONArray("highlights").getJSONObject(0).getInt("end"))
    }

    @Test
    fun nonWebActionsAndOtherCueCategoriesArePreserved() {
        assertNull(rewrite(batch(intent = "androidamap://route"), "mark.via", "Via"))
        val other = JSONObject(batch())
        other.getJSONArray("cues").getJSONObject(0).put("cueId", "other").put("category", "map")
        assertNull(rewrite(other.toString(), "mark.via", "Via"))
    }

    @Test
    fun mixedActionsKeepTheWholeCardIntact() {
        val json = JSONObject(batch())
        json.getJSONArray("cues").getJSONObject(0).getJSONArray("actions")
            .put(JSONObject().put("intentUri", "tel:10086"))
        assertNull(rewrite(json.toString(), "mark.via", "Via"))
    }

    @Test
    fun missingPresentationCannotSilentlyKeepTheWrongBrowser() {
        assertNull(rewrite("{}", "mark.via", "Via"))
        val json = JSONObject(batch())
        val title = json.getJSONArray("cues").getJSONObject(0).getJSONObject("display")
            .getJSONObject("briefCard").getJSONObject("title")
        title.put("text", "未知文案").remove("highlights")
        val updated = JSONObject(rewrite(json.toString(), "mark.via", "Via")!!)
        val card = updated.getJSONArray("cues").getJSONObject(0).getJSONObject("display").getJSONObject("briefCard")
        assertEquals("Via", card.getJSONObject("title").getString("text"))
        assertEquals(3, card.getJSONObject("title").getJSONArray("highlights").getJSONObject(0).getInt("end"))
        assertEquals("mark.via", card.getJSONObject("startIcon").getString("value"))
        json.getJSONArray("cues").getJSONObject(0).remove("display")
        val actionsOnly = JSONObject(rewrite(json.toString(), "mark.via", "Via")!!)
        assertTrue(actionsOnly.getJSONArray("cues").getJSONObject(0).getJSONArray("actions")
            .getJSONObject(0).getJSONObject("inParams").getString("intentUri").endsWith("target=mark.via"))
    }
}
