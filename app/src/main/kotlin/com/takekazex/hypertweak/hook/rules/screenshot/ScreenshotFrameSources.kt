package com.takekazex.hypertweak.hook.rules.screenshot

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Bounded private originals for the native editor's reversible shell operation. */
internal class ScreenshotFrameSources(private val directory: File) {
    companion object { private val lock = Any() }
    fun capture(write: (OutputStream) -> Boolean): File = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { check(write(it)) { "Cannot preserve screenshot original" } }
            prune()
            file
        } catch (error: Throwable) { file.delete(); throw error }
    }
    fun bind(identity: ScreenshotMediaIdentity, original: File): File = synchronized(lock) {
        val target = file(identity)
        if (original != target) check(original.renameTo(target)) { "Cannot bind screenshot original" }
        prune()
        target
    }
    fun find(identity: ScreenshotMediaIdentity): File? = synchronized(lock) {
        file(identity).takeIf { it.isFile && it.length() > 0 }
    }
    private fun file(identity: ScreenshotMediaIdentity): File {
        val key = MessageDigest.getInstance("SHA-256").digest((identity.key + identity.stamp).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.png")
    }
    private fun prune() {
        var bytes = 0L
        val now = System.currentTimeMillis()
        directory.listFiles { item -> item.extension == "png" }.orEmpty().sortedByDescending { it.lastModified() }
            .forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= 16 || bytes > 128L * 1024 * 1024 || now - file.lastModified() > 7L * 24 * 60 * 60 * 1000) file.delete()
            }
    }
}
