package com.takekazex.hypertweak.hook.rules.aicr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AicrCopyDirectFreeformTest {
    private val resolveIntent = tool("resolve_intent")
    private val gaodeSearch = tool("jumpToGaoDeSearch")

    @Test fun distinguishesTheThreeNativeCardTypesFromOtherActions() {
        assertEquals(AicrCopyDirectAction.COMMAND,
            AicrCopyDirectAction.fromRequest("cue.copy_text_jump_app#hce.copy_text_jump_app.command#1", resolveIntent))
        assertEquals(AicrCopyDirectAction.TRACKING,
            AicrCopyDirectAction.fromRequest("cue.copy_text_jump_app#hce.copy_text_jump_app.tracking_number#2", resolveIntent))
        val location = "cue.navigate_to_gaode_map_app#hce.navigate_to_map_app.address#3"
        assertEquals(AicrCopyDirectAction.LOCATION, AicrCopyDirectAction.fromRequest(location, gaodeSearch))
        assertNull(AicrCopyDirectAction.fromRequest(location, tool("jumpToBaiduMapSearch")))
        assertNull(AicrCopyDirectAction.fromRequest("cue.copy_text_jump_app#hce.copy_text_jump_app.jump_url#1", resolveIntent))
        assertNull(AicrCopyDirectAction.fromRequest("cue.screen_search#hce.navigate_to_map_app.address#3", gaodeSearch))
    }

    @Test fun addsOnlyTheScenarioToTheNativeActionInput() {
        val original = JSONObject().put("type", gaodeSearch).put("in", JSONObject().put("keyword", "example"))
        val marked = AicrCopyDirectAction.LOCATION.markRequest(original.toString(), gaodeSearch)
        val input = JSONObject(requireNotNull(marked)).getJSONObject("in")
        assertEquals("location", input.remove(AicrCopyDirectAction.EXTRA))
        assertEquals(original.getJSONObject("in").toString(), input.toString())
        assertNull(AicrCopyDirectAction.LOCATION.markRequest(original.toString(), resolveIntent))
    }

    private fun tool(name: String) =
        "urn:aiot-spec-v3:com.mi.phones:action:[com.xiaomi.aicr/context/$name]:0:1.0"
}
