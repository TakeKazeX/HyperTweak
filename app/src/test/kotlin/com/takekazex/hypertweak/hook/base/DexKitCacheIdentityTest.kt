package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties

class DexKitCacheIdentityTest {
    private fun propertiesAsText(properties: Properties): String = java.io.StringWriter().also {
        properties.store(it, null)
    }.toString()

    private fun identity(
        path: String = "/data/app/base.apk",
        modified: Long = 5,
        size: Long = 100,
        digest: String? = "verified",
    ) = DexKitCacheIdentity(path = path, modified = modified, size = size, fullDigest = digest)

    @Test fun `a changed apk metadata clears every entry in the file`() {
        val before = identity(modified = 5)
        val after = identity(modified = 6)
        val properties = Properties().apply {
            before.stamp(this)
            setProperty("feature_one", "OldOwner")
            setProperty("feature_two", "OtherOldOwner")
        }

        assertFalse(after.prepare(properties))
        assertTrue("a rejected identity must clear the whole file", properties.isEmpty())
    }

    @Test fun `a cache created before schema migration cannot retain mixed APK entries`() {
        val current = identity()
        val properties = Properties().apply {
            current.stamp(this)
            remove("cache_schema")
            setProperty("camera", "StaleButLoadable")
        }
        assertFalse(current.prepare(properties))
        assertTrue(properties.isEmpty())
    }

    @Test fun `an empty resolved cache still preserves invalidation across reload`() {
        val current = identity()
        val properties = Properties().apply { current.stamp(this); setProperty("feature", "WrongOwner") }
        assertTrue(current.prepare(properties))
        properties.remove("feature")
        current.stamp(properties)
        val reloaded = Properties().apply { load(propertiesAsText(properties).reader()) }
        assertTrue(current.prepare(reloaded))
        assertNull(reloaded.getProperty("feature"))
    }

    @Test fun `replacing a split invalidates the cache even when base apk is unchanged`() {
        val base = "/data/app/base.apk"
        val withOldSplit = DexKitCacheIdentity(
            path = base, modified = 5, size = 100,
            splits = listOf(DexKitCacheIdentity.SplitStamp("/data/app/split_config.arm64.apk", 10, 1)),
        )
        val properties = Properties().apply { withOldSplit.stamp(this); setProperty("v.target", "La;->a()V") }
        val withNewSplit = DexKitCacheIdentity(
            path = base, modified = 5, size = 100,
            splits = listOf(DexKitCacheIdentity.SplitStamp("/data/app/split_config.arm64.apk", 20, 2)),
        )

        assertFalse("a split-only change must invalidate", withNewSplit.prepare(properties))
        assertTrue(properties.isEmpty())
    }

    @Test fun `a full digest mismatch rejects a cache entry`() {
        val stamped = identity(digest = "recorded")
        val properties = Properties().apply { stamped.stamp(this) }
        val audited = stamped.copy(fullDigest = "different")

        assertFalse("complete bytes must gate reads", audited.prepare(properties))
    }

    @Test fun `a different system-partition fingerprint is rejected`() {
        val before = DexKitCacheIdentity("/product/app/X.apk", 1, 2, fingerprint = "build-a")
        val properties = Properties().apply { before.stamp(this) }
        val after = before.copy(fingerprint = "build-b")

        assertFalse(after.prepare(properties))
    }

    @Test fun `metadata capture never reads apk bytes`() {
        val dir = Files.createTempDirectory("identity-capture").toFile()
        try {
            val apk = File(dir, "base.apk").apply { writeBytes(ByteArray(4 * 1024 * 1024)) }
            val split = File(dir, "split.apk").apply { writeBytes(ByteArray(1024)) }
            val captured = DexKitCacheIdentity.capture(apk, listOf(split.absolutePath), "fp")

            assertNotNull(captured)
            assertEquals(apk.absolutePath, captured!!.path)
            assertEquals(4L * 1024 * 1024, captured.size)
            assertEquals(1, captured.splits.size)
            assertNull("capture must not compute a full digest", captured.fullDigest)
            assertEquals("fp", captured.fingerprint)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `splits are encoded in a stable order`() {
        val a = DexKitCacheIdentity.SplitStamp("/a.apk", 1, 1)
        val b = DexKitCacheIdentity.SplitStamp("/b.apk", 2, 2)
        val forward = DexKitCacheIdentity("/base.apk", 1, 2, splits = listOf(a, b), fullDigest = "verified")
        val reverse = DexKitCacheIdentity("/base.apk", 1, 2, splits = listOf(b, a), fullDigest = "verified")

        val properties = Properties().apply { forward.stamp(this) }
        assertTrue("split order must not affect identity", reverse.prepare(properties))
        assertEquals("resolution keys must match too", forward.resolutionKey(), reverse.resolutionKey())
    }

    @Test fun `full digest is stable and detects any change`() {
        val dir = Files.createTempDirectory("identity-full").toFile()
        try {
            val apk = File(dir, "base.apk").apply { writeBytes(ByteArray(1024)) }
            val first = DexKitCacheIdentity.withFullDigest(DexKitCacheIdentity.capture(apk)!!)
            val second = DexKitCacheIdentity.withFullDigest(DexKitCacheIdentity.capture(apk)!!)
            assertEquals("same bytes must give the same digest", first.fullDigest, second.fullDigest)

            // The digest has to notice a change the metadata cannot see: same size, same mtime,
            // different byte. Otherwise the audit record would agree with a replaced APK.
            val modified = apk.lastModified()
            java.io.RandomAccessFile(apk, "rw").use { it.seek(0); it.write(7) }
            apk.setLastModified(modified)
            val changed = DexKitCacheIdentity.withFullDigest(DexKitCacheIdentity.capture(apk)!!)
            assertNotEquals(
                "a same-size same-mtime edit must change the full digest",
                first.fullDigest,
                changed.fullDigest
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a split replaced in place is not hidden by the identity memo`() {
        // base.apk is untouched, so a memo keyed only on base metadata would keep returning the
        // old split stamps and serve descriptors from the replaced split.
        val dir = Files.createTempDirectory("identity-memo").toFile()
        try {
            val apk = File(dir, "base.apk").apply { writeBytes(ByteArray(1024)) }
            val split = File(dir, "split_config.apk").apply { writeBytes(ByteArray(64)) }
            val first = DexKitCacheIdentity.capture(apk, listOf(split.absolutePath))!!

            // Replace the split: same path, different size and mtime.
            split.writeBytes(ByteArray(128))
            split.setLastModified(split.lastModified() + 5_000)
            val second = DexKitCacheIdentity.capture(apk, listOf(split.absolutePath))!!

            assertNotEquals(
                "a split change must produce a different identity",
                first.resolutionKey(),
                second.resolutionKey()
            )
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test fun `metadata alone cannot authorize a persistent cache`() {
        val metadata = identity(digest = null)
        val properties = Properties().apply { metadata.stamp(this) }
        assertFalse(metadata.matches(properties))
        assertFalse(identity().matches(properties))
    }

    @Test fun `a missing split fails closed`() {
        val dir = Files.createTempDirectory("identity-missing-split").toFile()
        try {
            val apk = File(dir, "base.apk").apply { writeText("base") }
            assertNull(DexKitCacheIdentity.capture(apk, listOf(File(dir, "missing.apk").path)))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `same metadata split replacement changes the complete digest`() {
        val dir = Files.createTempDirectory("identity-split-bytes").toFile()
        try {
            val apk = File(dir, "base.apk").apply { writeText("base") }
            val split = File(dir, "split.apk").apply { writeText("before") }
            fun capture() = DexKitCacheIdentity.withFullDigest(
                DexKitCacheIdentity.capture(apk, listOf(split.path))!!)
            val before = capture()
            val modified = split.lastModified()
            split.writeText("after!")
            assertTrue(split.setLastModified(modified))
            val after = capture()
            val properties = Properties().apply { before.stamp(this) }
            assertFalse(after.matches(properties))
            assertNotEquals(before.resolutionKey(), after.resolutionKey())
        } finally { dir.deleteRecursively() }
    }

}