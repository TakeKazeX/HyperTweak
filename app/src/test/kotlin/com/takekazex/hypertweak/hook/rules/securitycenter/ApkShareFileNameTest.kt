package com.takekazex.hypertweak.hook.rules.securitycenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkShareFileNameTest {
    @Test fun keepsUnicodeAndUsesThePackageWhenTheLabelIsBlank() {
        assertEquals("我的应用_2.0.apk", apkShareFileName("我的应用", "example.app", "2.0"))
        assertEquals("example.app.apk", apkShareFileName(" .. ", "example.app", null))
    }

    @Test fun removesSeparatorsAndControlCharactersFromBothParts() {
        assertEquals("a_b_1_2_.apk", apkShareFileName("a/b", "example.app", "1\\2\u0000"))
    }

    @Test fun fitsAFileNameComponentAndKeepsSupplementaryCharactersWhole() {
        val name = apkShareFileName("a" + "😀".repeat(60), "example.app", "版".repeat(100))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertFalse(name.substringBefore('_').last().isHighSurrogate())
        assertTrue(name.endsWith(".apk"))
    }
}
