package com.takekazex.hypertweak.hook.base.dex

import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.base.DexKitCacheIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DexRuntimeIdentityTest {
    @Test fun `package loaded metadata preserves splits before Application exists`() {
        val dir = Files.createTempDirectory("runtime-identity").toFile()
        try {
            val base = File(dir, "base.apk").apply { writeText("base") }
            val split = File(dir, "split.apk").apply { writeText("split") }
            val info = ApplicationInfo().apply { sourceDir = base.path; splitSourceDirs = arrayOf(split.path) }
            DexRuntime.registerSource(info)
            val actual = DexRuntime.identityFor(null, base.path)!!
            val expected = DexKitCacheIdentity.withFullDigest(DexKitCacheIdentity.capture(base, listOf(split.path))!!)
            assertEquals(expected, actual)
            assertSame(actual, DexRuntime.identityFor(null, base.path))
            split.appendText("changed")
            assertNotEquals(actual.fullDigest, DexRuntime.identityFor(null, base.path)!!.fullDigest)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `a plugin never borrows the executing package split set`() {
        val dir = Files.createTempDirectory("plugin-identity").toFile()
        try {
            val base = File(dir, "base.apk").apply { writeText("base") }
            val split = File(dir, "split.apk").apply { writeText("split") }
            val plugin = File(dir, "plugin.apk").apply { writeText("plugin") }
            val host = ApplicationInfo().apply { sourceDir = base.path; splitSourceDirs = arrayOf(split.path) }
            val actual = DexRuntime.identityFor(host, plugin.path)!!
            assertTrue(actual.splits.isEmpty())
            assertEquals(DexKitCacheIdentity.withFullDigest(DexKitCacheIdentity.capture(plugin)!!), actual)
        } finally { dir.deleteRecursively() }
    }
    @Test fun `source update epoch rejects equal-stat replacement and missing splits`() {
        val dir = Files.createTempDirectory("source-epoch").toFile()
        try {
            val base = File(dir, "base.apk").apply { writeText("AAAA") }
            val split = File(dir, "split.apk").apply { writeText("1111") }
            val info = ApplicationInfo().apply { sourceDir = base.path; splitSourceDirs = arrayOf(split.path) }
            DexRuntime.registerSource(info)
            val before = DexRuntime.identityFor(null, base.path)!!
            val modified = split.lastModified()
            split.writeText("2222"); check(split.setLastModified(modified))
            DexRuntime.registerSource(info)
            val after = DexRuntime.identityFor(null, base.path)!!
            assertNotEquals(before.fullDigest, after.fullDigest)
            assertEquals(before.splits, after.splits)
            split.delete()
            assertNull(DexRuntime.identityFor(null, base.path))
        } finally { dir.deleteRecursively() }
    }

}
