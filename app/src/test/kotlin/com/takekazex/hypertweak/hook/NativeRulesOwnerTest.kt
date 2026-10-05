package com.takekazex.hypertweak.hook

import org.junit.Assert.*
import org.junit.Test

class NativeRulesOwnerTest {
    @Test fun `native bridge belongs only to the payload's exact launcher process`() {
        assertTrue(NativeRules.ownsBridge("com.miui.home"))
        for (process in listOf("app", "system", "com.android.phone", "com.miui.home:remote", "com.evil.home")) {
            assertFalse(NativeRules.ownsBridge(process))
        }
    }
    @Test fun `passive diagnostics and unrelated process calls never load JNI`() {
        NativeRules.bindProcess("com.android.phone")
        assertNull(NativeRules.status())
        assertTrue(NativeRules.describe().contains("NOT_ATTEMPTED"))
        assertFalse(NativeRules.ensureLoaded())
        assertNull(NativeRules.status())
    }
}
