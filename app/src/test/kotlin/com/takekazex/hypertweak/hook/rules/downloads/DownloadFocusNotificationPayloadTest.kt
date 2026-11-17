package com.takekazex.hypertweak.hook.rules.downloads

import com.takekazex.hypertweak.hook.rules.downloads.DownloadFocusNotificationPayload.Action
import com.takekazex.hypertweak.hook.rules.downloads.DownloadFocusNotificationPayload.Content
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFocusNotificationPayloadTest {
    private fun param(content: Content) = JSONObject(DownloadFocusNotificationPayload.build(content)).getJSONObject("param_v2")

    @Test fun `active task fills the native file_download template on every surface`() {
        val param = param(Content("archive.zip", "2.0MB/4.0MB · 1.0MB/s", 50, "file_icon_zip",
            Action(DownloadFocusNotificationPayload.ACTION_PAUSE, "暂停")))
        assertEquals("file_download", param.getString("business"))
        assertTrue(param.getBoolean("updatable"))
        val chat = param.getJSONObject("chatInfo")
        assertEquals("archive.zip", chat.getString("title"))
        assertEquals("2.0MB/4.0MB · 1.0MB/s", chat.getString("content"))
        assertEquals("file_icon_zip", chat.getString("picProfile"))
        assertEquals(50, param.getJSONObject("progressInfo").getInt("progress"))
        val action = param.getJSONArray("actions").getJSONObject(0)
        assertEquals("action_pause_record", action.getString("action"))
        assertEquals(0, action.getInt("type"))

        val island = param.getJSONObject("param_island")
        val big = island.getJSONObject("bigIslandArea")
        assertEquals("archive.zip", big.getJSONObject("imageTextInfoLeft").getJSONObject("textInfo").getString("title"))
        assertEquals("50%", big.getJSONObject("progressTextInfo").getJSONObject("textInfo").getString("title"))
        assertEquals(50, island.getJSONObject("smallIslandArea").getJSONObject("combinePicInfo")
            .getJSONObject("progressInfo").getInt("progress"))
    }

    @Test fun `completed task uses the native done check without percentage, bar or actions`() {
        val param = param(Content("finished.zip", "下载完成 · 4.0MB", 100, "file_icon_zip",
            Action(DownloadFocusNotificationPayload.ACTION_PAUSE, "暂停"), completed = true))
        assertFalse(param.getBoolean("updatable"))
        assertFalse(param.has("progressInfo"))
        assertFalse(param.has("picInfo"))
        assertFalse(param.has("actions"))
        val island = param.getJSONObject("param_island")
        val big = island.getJSONObject("bigIslandArea")
        assertFalse(big.has("progressTextInfo"))
        assertEquals(DownloadFocusNotificationPayload.DONE_ICON, big.getJSONObject("picInfo").getString("pic"))
        assertEquals("finished.zip", big.getJSONObject("imageTextInfoLeft").getJSONObject("textInfo").getString("title"))
        val small = island.getJSONObject("smallIslandArea")
        assertFalse(small.has("combinePicInfo"))
        assertEquals(DownloadFocusNotificationPayload.DONE_ICON, small.getJSONObject("picInfo").getString("pic"))
    }

    @Test fun `unknown total never fabricates a percentage`() {
        val param = param(Content("unknown.bin", "1.0MB", null, "file_icon_default"))
        assertFalse(param.has("progressInfo"))
        val island = param.getJSONObject("param_island")
        assertFalse(island.getJSONObject("bigIslandArea").has("progressTextInfo"))
        assertTrue(island.getJSONObject("smallIslandArea").has("picInfo"))
    }

    @Test fun `progress is clamped and long names stay within the host budget`() {
        assertEquals(100, param(Content("file", "", 140, "file_icon_default")).getJSONObject("progressInfo").getInt("progress"))
        val payload = DownloadFocusNotificationPayload.build(
            Content("\"quoted\" " + "下载文件名".repeat(100), "x".repeat(500), 10, "file_icon_default"))
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= DownloadFocusNotificationPayload.MAX_BYTES)
    }

    @Test fun `file icons follow the provider's native drawable names`() {
        assertEquals("file_icon_apk", DownloadFocusNotificationPayload.iconKey("App.APK"))
        assertEquals("file_icon_default", DownloadFocusNotificationPayload.iconKey("README"))
    }
}
