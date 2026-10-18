package com.takekazex.hypertweak.hook.base

import java.util.Properties
import java.io.File
import java.security.MessageDigest

/** One identity owns every key in the shared cache, including keys not requested this time. */
internal data class DexKitCacheIdentity(val modified: Long, val size: Long, val sha256: String) {
    fun matches(properties: Properties): Boolean =
        properties.getProperty("cache_schema") == SCHEMA &&
            properties.getProperty("apk_last_modified") == modified.toString() &&
            properties.getProperty("apk_file_size") == size.toString() &&
            properties.getProperty("apk_sha256") == sha256

    fun prepare(properties: Properties): Boolean {
        val valid = matches(properties)
        if (!valid) properties.clear()
        return valid
    }

    fun stamp(properties: Properties) {
        properties.setProperty("cache_schema", SCHEMA)
        properties.setProperty("apk_last_modified", modified.toString())
        properties.setProperty("apk_file_size", size.toString())
        properties.setProperty("apk_sha256", sha256)
    }

    companion object {
        private const val SCHEMA = "2"

        /** Capture bytes and metadata together. Callers may memoize immutable installed APKs. */
        fun capture(file: File): DexKitCacheIdentity? = runCatching {
            if (!file.isFile) return null
            val modified = file.lastModified()
            val size = file.length()
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (file.lastModified() != modified || file.length() != size) return null
            DexKitCacheIdentity(modified, size, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }.getOrNull()
    }
}
