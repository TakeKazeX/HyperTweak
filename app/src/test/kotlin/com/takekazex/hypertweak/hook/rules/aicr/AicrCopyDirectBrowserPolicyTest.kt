package com.takekazex.hypertweak.hook.rules.aicr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AicrCopyDirectBrowserPolicyTest {
    @Test
    fun normalizeHttpUrlAddsHttpsToBareHostAndKeepsPath() {
        assertEquals(
            "https://example.com/path?q=copy#top",
            AicrCopyDirectBrowserPolicy.normalizeHttpUrl("  example.com/path?q=copy#top  ")
        )
    }

    @Test
    fun normalizeHttpUrlPreservesHttpAndHttps() {
        assertEquals(
            "http://example.com/a",
            AicrCopyDirectBrowserPolicy.normalizeHttpUrl("http://example.com/a")
        )
        assertEquals(
            "HTTPS://example.com/a",
            AicrCopyDirectBrowserPolicy.normalizeHttpUrl("HTTPS://example.com/a")
        )
    }

    @Test
    fun normalizeHttpUrlRejectsNonWebAndHostlessInputs() {
        assertNull(AicrCopyDirectBrowserPolicy.normalizeHttpUrl("mailto:user@example.com"))
        assertNull(AicrCopyDirectBrowserPolicy.normalizeHttpUrl("ftp://example.com/file"))
        assertNull(AicrCopyDirectBrowserPolicy.normalizeHttpUrl("https://"))
        assertNull(AicrCopyDirectBrowserPolicy.normalizeHttpUrl("  "))
    }

    @Test
    fun targetGateAcceptsOnlyXiaomiBrowserHttpActions() {
        assertTrue(
            AicrCopyDirectBrowserPolicy.targetsXiaomiWebAction(
                packageName = AicrCopyDirectBrowserPolicy.XIAOMI_BROWSER,
                componentPackage = null,
                action = AicrCopyDirectBrowserPolicy.ACTION_VIEW,
                scheme = "https"
            )
        )
        assertTrue(
            AicrCopyDirectBrowserPolicy.targetsXiaomiWebAction(
                packageName = null,
                componentPackage = AicrCopyDirectBrowserPolicy.XIAOMI_BROWSER,
                action = null,
                scheme = "http"
            )
        )
        assertFalse(
            AicrCopyDirectBrowserPolicy.targetsXiaomiWebAction(
                packageName = "com.example.browser",
                componentPackage = null,
                action = AicrCopyDirectBrowserPolicy.ACTION_VIEW,
                scheme = "https"
            )
        )
        assertFalse(
            AicrCopyDirectBrowserPolicy.targetsXiaomiWebAction(
                packageName = AicrCopyDirectBrowserPolicy.XIAOMI_BROWSER,
                componentPackage = null,
                action = "android.intent.action.SEND",
                scheme = "https"
            )
        )
        assertFalse(
            AicrCopyDirectBrowserPolicy.targetsXiaomiWebAction(
                packageName = AicrCopyDirectBrowserPolicy.XIAOMI_BROWSER,
                componentPackage = null,
                action = AicrCopyDirectBrowserPolicy.ACTION_VIEW,
                scheme = "geo"
            )
        )
    }
}
