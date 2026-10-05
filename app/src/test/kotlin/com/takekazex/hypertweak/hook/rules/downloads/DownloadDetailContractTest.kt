package com.takekazex.hypertweak.hook.rules.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadDetailContractTest {
    class Info { var nativeUrl = "https://download"; var iconUrl = "https://icon" }
    class RenamedHost {
        fun a(info: Info) = Unit
        private fun b(info: Info) = Unit
        fun c(info: Info) = Unit
        fun future(count: Int, info: Info, enabled: Boolean) = Unit
    }
    class OtherHost { fun a(info: Info) = Unit }
    @Test fun `same-prototype helper methods cannot invalidate the semantic renderer`() {
        val owner = RenamedHost::class.java
        assertEquals(3, owner.declaredMethods.count { it.parameterTypes.contentEquals(arrayOf(Info::class.java)) })
        val render = owner.getDeclaredMethod("c", Info::class.java)
        assertEquals(render, DownloadDetailContract.renderer(owner, listOf(render)) { it == Info::class.java })
    }
    @Test fun `duplicate semantic renderers and foreign owners fail closed`() {
        val owner = RenamedHost::class.java
        assertNull(DownloadDetailContract.renderer(owner, owner.declaredMethods.toList()) { it == Info::class.java })
        assertNull(DownloadDetailContract.renderer(owner, OtherHost::class.java.declaredMethods.toList()) { it == Info::class.java })
    }
    @Test fun `renderer parameter additions derive the model position from its contract`() {
        val owner = RenamedHost::class.java
        val render = owner.getDeclaredMethod("future", Integer.TYPE, Info::class.java, java.lang.Boolean.TYPE)
        assertEquals(render, DownloadDetailContract.renderer(owner, listOf(render)) { it == Info::class.java })
        assertEquals(1, DownloadDetailContract.infoIndex(render) { it == Info::class.java })
    }
    @Test fun `native copy evidence selects the original URL despite multiple URL-shaped fields`() {
        val source = Info::class.java.getDeclaredField("nativeUrl")
        assertEquals(source, DownloadDetailContract.source(Info::class.java, listOf(source)))
        assertNull(DownloadDetailContract.source(Info::class.java, Info::class.java.declaredFields.toList()))
    }
}
