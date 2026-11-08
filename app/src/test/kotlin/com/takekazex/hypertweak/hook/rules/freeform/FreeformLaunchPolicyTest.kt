package com.takekazex.hypertweak.hook.rules.freeform

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeformLaunchPolicyTest {
    @Test fun matchesShareTargetsAndOnlyTheWeChatCallback() {
        assertEquals(FreeformLaunchScenario.SHARE, freeformLaunchScenario("sender.app", "target.app", "target.Share", Intent.ACTION_SEND, null))
        assertEquals(FreeformLaunchScenario.SHARE, freeformLaunchScenario("sender.app", "target.app", "target.Share", Intent.ACTION_SEND_MULTIPLE, null))
        assertEquals(FreeformLaunchScenario.SHARE, freeformLaunchScenario(
            "sender.app", "com.tencent.mm", "com.tencent.mm.plugin.base.stub.WXEntryActivity", null, null,
        ))
        assertNull(freeformLaunchScenario("sender.app", "com.tencent.mm", "com.tencent.mm.Launcher", null, null))
    }

    @Test fun onlyContentExtensionWebLinksQualify() {
        assertEquals(FreeformLaunchScenario.CONTENT_LINK, freeformLaunchScenario(
            "com.miui.contentextension", "browser.app", "browser.Open", Intent.ACTION_VIEW, "https",
        ))
        assertNull(freeformLaunchScenario("sender.app", "browser.app", "browser.Open", Intent.ACTION_VIEW, "https"))
        assertNull(freeformLaunchScenario(
            "com.miui.contentextension", "browser.app", "browser.Open", Intent.ACTION_VIEW, "content",
        ))
    }

    @Test fun copyDirectRequiresTheAicrCallerAndClipboardWebAction() {
        fun scenario(caller: String, marker: String?, scheme: String = "https") =
            freeformLaunchScenario(caller, "browser.app", "browser.Open", Intent.ACTION_VIEW, scheme, marker)

        assertEquals(FreeformLaunchScenario.COPY_DIRECT_LINK, scenario("com.xiaomi.aicr", "clipboard_open"))
        assertNull(scenario("com.xiaomi.aicr", null))
        assertNull(scenario("com.xiaomi.aicr", "clipboard_open", "tel"))
        assertNull(scenario("sender.app", "clipboard_open"))
    }

    @Test fun shareAndContentLinkSettingsAreIndependent() {
        assertTrue(FreeformLaunchScenario.SHARE.isEnabled(share = true, contentLinks = false))
        assertFalse(FreeformLaunchScenario.CONTENT_LINK.isEnabled(share = true, contentLinks = false))
        assertTrue(FreeformLaunchScenario.CONTENT_LINK.isEnabled(share = false, contentLinks = true))
        assertTrue(FreeformLaunchScenario.COPY_DIRECT_LINK.isEnabled(share = false, contentLinks = true))
        assertFalse(FreeformLaunchScenario.COPY_DIRECT_LINK.isEnabled(share = true, contentLinks = false))
        assertFalse(FreeformLaunchScenario.SHARE.isEnabled(share = false, contentLinks = true))
    }

    @Test fun leavesSameAppScreenshotAndSystemTargetsAlone() {
        assertNull(freeformLaunchScenario("target.app", "target.app", "target.Share", Intent.ACTION_SEND, null))
        assertNull(freeformLaunchScenario("com.miui.screenshot", "target.app", "target.Share", Intent.ACTION_SEND, null))
        assertNull(freeformLaunchScenario("sender.app", "com.android.intentresolver", "Chooser", Intent.ACTION_SEND, null))
        assertNull(freeformLaunchScenario("sender.app", "com.android.packageinstaller", "Installer", Intent.ACTION_SEND, null))
        assertNull(freeformLaunchScenario(null, "target.app", "target.Share", Intent.ACTION_SEND, null))
    }
}
