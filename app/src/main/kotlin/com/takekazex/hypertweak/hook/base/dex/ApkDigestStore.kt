package com.takekazex.hypertweak.hook.base.dex

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties

/**
 * Kernel-maintained stat of one installed artifact.
 *
 * `ctime` cannot be set from user space and changes on every content write, and an atomic
 * replacement gets a new inode. Together with size and mtime this tuple detects the
 * equal-size/equal-mtime replacement that a plain `length()/lastModified()` stamp misses, so a
 * digest bound to it can be reused without re-reading the bytes.
 */
internal data class ApkFileStat(
    val device: Long,
    val inode: Long,
    val size: Long,
    val mtimeNanos: Long,
    val ctimeNanos: Long,
) {
    fun encode(): String = "$device:$inode:$size:$mtimeNanos:$ctimeNanos"

    companion object {
        /** Null when the platform exposes no inode/ctime; callers then hash every time. */
        fun of(file: File): ApkFileStat? = android(file) ?: jvm(file)

        private fun android(file: File): ApkFileStat? = runCatching {
            val stat = android.system.Os.stat(file.path) ?: return null
            ApkFileStat(stat.st_dev, stat.st_ino, stat.st_size,
                stat.st_mtim.toNanos(), stat.st_ctim.toNanos())
        }.getOrNull()

        private fun android.system.StructTimespec?.toNanos(): Long =
            this?.let { it.tv_sec * 1_000_000_000L + it.tv_nsec } ?: error("no timespec")

        /** Host JVM (unit tests) only; Android's java.nio has no "unix" view. */
        private fun jvm(file: File): ApkFileStat? = runCatching {
            val attributes = Files.readAttributes(file.toPath(), "unix:dev,ino,size,lastModifiedTime,ctime")
            fun nanos(name: String) = (attributes[name] as java.nio.file.attribute.FileTime)
                .to(java.util.concurrent.TimeUnit.NANOSECONDS)
            ApkFileStat((attributes["dev"] as Number).toLong(), (attributes["ino"] as Number).toLong(),
                (attributes["size"] as Number).toLong(), nanos("lastModifiedTime"), nanos("ctime"))
        }.getOrNull()
    }
}

/**
 * Per-host persistent SHA-256 of installed APK files, keyed by [ApkFileStat] and build
 * fingerprint. Without it every process start (and every later identity check) re-read
 * 50-350 MB host APKs, partly on main threads, and evicted other apps' page cache.
 *
 * The file lives in the executing host's own cache directory, which no other app can write.
 * Concurrent writers from sibling processes may drop each other's newest entry; the cost of that
 * race is one extra hash, never a wrong digest, because every entry carries its full stat key.
 */
internal class ApkDigestStore(private val file: File?, private val fingerprint: String) {
    /** False before the executing package's data directory is known; such a store is not kept. */
    val persistent: Boolean get() = file != null

    @Synchronized
    fun digest(target: File): ByteArray {
        val before = ApkFileStat.of(target)
        val key = before?.let { fingerprint + "|" + it.encode() }
        if (key != null) lookup(target.absolutePath, key)?.let { return it }
        val bytes = sha256(target)
        // Never persist a digest whose bytes may belong to a different version than its key.
        if (key != null && ApkFileStat.of(target) == before) store(target.absolutePath, key, bytes)
        return bytes
    }

    /** The persisted digest for [target]'s current stat, or null; never reads the file's bytes. */
    @Synchronized
    fun cached(target: File): ByteArray? {
        val stat = ApkFileStat.of(target) ?: return null
        return lookup(target.absolutePath, fingerprint + "|" + stat.encode())
    }

    private fun lookup(path: String, key: String): ByteArray? {
        val value = load().getProperty(path) ?: return null
        val separator = value.lastIndexOf('|')
        if (separator <= 0 || value.substring(0, separator) != key) return null
        return value.substring(separator + 1).hexToBytesOrNull()?.takeIf { it.size == 32 }
    }

    private fun store(path: String, key: String, digest: ByteArray) {
        val target = file ?: return
        runCatching {
            val directory = target.parentFile ?: return
            if (!directory.isDirectory && !directory.mkdirs()) return
            val properties = load()
            // Only installed artifacts still present keep an entry; the file stays tiny.
            properties.stringPropertyNames().filterNot { File(it).isFile }.forEach(properties::remove)
            properties.setProperty(path, key + "|" + digest.toHex())
            val temporary = File(directory, target.name + "." + android.os.Process.myPid() + ".tmp")
            temporary.outputStream().use { properties.store(it, null) }
            if (!temporary.renameTo(target)) temporary.delete()
        }
    }

    private fun load(): Properties = Properties().also { properties ->
        val source = file?.takeIf { it.isFile && it.length() <= MAX_BYTES } ?: return properties
        runCatching { source.inputStream().use(properties::load) }.onFailure { properties.clear() }
    }

    companion object {
        const val FILE_NAME = "hypertweak_apk_digests.properties"
        private const val MAX_BYTES = 64 * 1024L

        fun sha256(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest()
        }

        private fun ByteArray.toHex(): String {
            val out = CharArray(size * 2)
            forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                out[index * 2] = HEX[value ushr 4]; out[index * 2 + 1] = HEX[value and 0x0f]
            }
            return String(out)
        }

        private fun String.hexToBytesOrNull(): ByteArray? {
            if (length % 2 != 0) return null
            return ByteArray(length / 2) { index ->
                val high = Character.digit(this[index * 2], 16)
                val low = Character.digit(this[index * 2 + 1], 16)
                if (high < 0 || low < 0) return null
                ((high shl 4) or low).toByte()
            }
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
