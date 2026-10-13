package com.takekazex.hypertweak.hook.rules.screenshot

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScreenshotFrameSourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `original survives a process instance change and cannot follow a reused image identity`() {
        val directory = File(temporary.root, "originals")
        val store = ScreenshotFrameSources(directory)
        val identity = ScreenshotMediaIdentity("0:external:42", "frame revision 1", 1286, 2689, "image/png")
        val captured = store.capture { it.write("original pixels".toByteArray()); true }
        store.bind(identity, captured)
        assertEquals("original pixels", ScreenshotFrameSources(directory).find(identity)?.readText())
        assertNull(store.find(identity.copy(stamp = "frame revision 2")))
    }
    @Test fun `failed preservation leaves no partial original and completed captures stay bounded`() {
        val directory = File(temporary.root, "originals")
        val store = ScreenshotFrameSources(directory)
        assertThrows(IllegalStateException::class.java) { store.capture { it.write(byteArrayOf(1)); false } }
        assertEquals(0, directory.listFiles()!!.size)
        repeat(20) { store.capture { it.write(byteArrayOf(1, 2)); true } }
        assertEquals(16, directory.listFiles()!!.size)
    }
}
