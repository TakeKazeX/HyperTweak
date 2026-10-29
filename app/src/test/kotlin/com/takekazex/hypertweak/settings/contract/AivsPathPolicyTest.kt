package com.takekazex.hypertweak.settings.contract

import org.junit.Assert.*
import org.junit.Test

class AivsPathPolicyTest {
    @Test fun `live toggle maps old and redirected cached paths without double prefixes`() {
        for (pkg in AivsPathPolicy.packages) for (leaf in listOf("encode_audio.data", "origin_audio.pcm")) {
            val old = "/storage/emulated/10/$pkg/audio/$leaf"
            val moved = "/storage/emulated/10/MIUI/AIVS/$pkg/audio/$leaf"
            assertEquals(moved, AivsPathPolicy.audioPath(old, "/storage/emulated/10", pkg, true))
            assertEquals(moved, AivsPathPolicy.audioPath(moved, "/storage/emulated/10", pkg, true))
            assertEquals(old, AivsPathPolicy.audioPath(moved, "/storage/emulated/10", pkg, false))
            assertEquals(old, AivsPathPolicy.audioPath(old, "/storage/emulated/10", pkg, false))
        }
    }
    @Test fun `unrelated roots and customized files remain native`() {
        val pkg = "com.miui.voiceassist"
        for (path in listOf(null, "", "/sdcard/custom.pcm", "/storage/emulated/0/$pkg/audio/other", "/storage/emulated/0/${pkg}2/audio/origin_audio.pcm")) {
            assertEquals(path, AivsPathPolicy.audioPath(path, "/storage/emulated/0", pkg, true))
        }
        assertEquals("../$pkg", AivsPathPolicy.rootArgument("../$pkg", pkg, true))
        assertEquals("MIUI/AIVS/$pkg", AivsPathPolicy.rootArgument("/$pkg/", pkg, true))
        assertEquals(pkg, AivsPathPolicy.rootArgument(pkg, pkg, false))
    }
}
