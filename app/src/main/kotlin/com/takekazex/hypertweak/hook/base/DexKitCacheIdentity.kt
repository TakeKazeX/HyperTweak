package com.takekazex.hypertweak.hook.base

import java.io.File
import java.security.MessageDigest
import java.util.Properties

/** Installed base/split identity. Persistent descriptors require a complete byte digest.
 * Capture is cheap metadata; withFullDigest is memoized once per artifact by DexRuntime.
 * Missing or unreadable splits fail closed instead of silently weakening the identity.
 */
internal data class DexKitCacheIdentity(
    val path: String,
    val modified: Long,
    val size: Long,
    /** One entry per split APK, sorted by path so the encoding is stable. */
    val splits: List<SplitStamp> = emptyList(),
    /** Non-null only for system-partition packages, where an OTA keeps the path and size. */
    val fingerprint: String? = null,
    /** SHA-256 of the base, or an aggregate of complete base/split SHA-256 digests. */
    val fullDigest: String? = null,
) {
    /** Base APK and every split must still describe the same install. */
    fun matches(properties: Properties): Boolean {
        if (properties.getProperty("cache_schema") != SCHEMA) return false
        if (properties.getProperty("apk_path") != path) return false
        if (properties.getProperty("apk_last_modified") != modified.toString()) return false
        if (properties.getProperty("apk_file_size") != size.toString()) return false
        if (properties.getProperty("apk_fingerprint").orEmpty() != fingerprint.orEmpty()) return false
        if (properties.getProperty("apk_splits") != encodeSplits(splits)) return false
        if (fullDigest.isNullOrBlank() || properties.getProperty("apk_full_digest") != fullDigest) return false
        return true
    }

    fun prepare(properties: Properties): Boolean {
        val valid = matches(properties)
        if (!valid) properties.clear()
        return valid
    }

    fun stamp(properties: Properties) {
        properties.setProperty("cache_schema", SCHEMA)
        properties.setProperty("apk_path", path)
        properties.setProperty("apk_last_modified", modified.toString())
        properties.setProperty("apk_file_size", size.toString())
        properties.setProperty("apk_splits", encodeSplits(splits))
        fingerprint?.let { properties.setProperty("apk_fingerprint", it) }
        fullDigest?.let { properties.setProperty("apk_full_digest", it) }
    }

    /** Cache identity for [DexResolution] keying; includes the verified complete digest. */
    fun resolutionKey(): String = path + ":" + size + ":" + modified + ":" + fingerprint.orEmpty() +
        ":" + encodeSplits(splits) + ":" + fullDigest.orEmpty()

    data class SplitStamp(val path: String, val size: Long, val modified: Long)

    companion object {
        private const val SCHEMA = "4"

        /**
         * Metadata-only capture. This is the hot-path entry point: it must never read APK bytes.
         *
         * [splitPaths] must be supplied by callers that can see them
         * (`ApplicationInfo.splitSourceDirs`); omitting them is only correct for packages that
         * genuinely have no splits.
         */
        fun capture(
            file: File,
            splitPaths: List<String> = emptyList(),
            fingerprint: String? = null,
        ): DexKitCacheIdentity? = runCatching {
            if (!file.isFile) return null
            val modified = file.lastModified()
            val size = file.length()
            val splits = splitPaths.distinct().map { path ->
                val split = File(path)
                if (!split.isFile) return null
                SplitStamp(split.absolutePath, split.length(), split.lastModified())
            }.sortedBy(SplitStamp::path)
            DexKitCacheIdentity(file.absolutePath, modified, size, splits, fingerprint)
        }.getOrNull()

        /** Verify complete bytes once; never publish a partial identity on IO or replacement. */
        fun withFullDigest(identity: DexKitCacheIdentity): DexKitCacheIdentity = runCatching {
            val files = listOf(File(identity.path)) + identity.splits.map { File(it.path) }
            val stamps = listOf(SplitStamp(identity.path, identity.size, identity.modified)) + identity.splits
            fun unchanged() = files.zip(stamps).all { (file, stamp) ->
                file.isFile && file.length() == stamp.size && file.lastModified() == stamp.modified
            }
            check(unchanged()) { "APK changed before hashing" }
            val digests = files.map { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest()
            }
            check(unchanged()) { "APK changed while hashing" }
            val digest = if (digests.size == 1) digests.single() else {
                MessageDigest.getInstance("SHA-256").apply { digests.forEach(::update) }.digest()
            }
            identity.copy(fullDigest = digest.hex())
        }.getOrElse { identity.copy(fullDigest = null) }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** Sorted so any construction order yields the same identity string. */
        private fun encodeSplits(splits: List<SplitStamp>): String = splits
            .sortedBy(SplitStamp::path)
            .joinToString("|") { it.path + "," + it.size + "," + it.modified }
    }
}
