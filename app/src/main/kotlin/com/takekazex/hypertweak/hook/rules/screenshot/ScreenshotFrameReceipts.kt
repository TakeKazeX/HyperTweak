package com.takekazex.hypertweak.hook.rules.screenshot

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Properties

/** Small cross-process receipts; never stores a second copy of the screenshot. */
internal class ScreenshotFrameReceipts(private val directory: File) {
    companion object {
        private const val LIMIT = 128
        private const val MAX_AGE = 30L * 24 * 60 * 60 * 1000
        private val processLock = Any()
    }

    fun contains(uri: String, identity: String): Boolean {
        if (!directory.isDirectory) return false
        return locked {
            val record = record(uri)
            if (!record.isFile || record.length() > 4096) return@locked false
            val properties = Properties().apply { record.inputStream().use(::load) }
            val age = System.currentTimeMillis() - record.lastModified()
            age in 0..MAX_AGE && properties.getProperty("uri") == uri &&
                properties.getProperty("identity") == identity
        }
    }

    fun publish(uri: String, identity: String) = locked {
        val properties = Properties().apply {
            setProperty("uri", uri)
            setProperty("identity", identity)
        }
        val temporary = File.createTempFile("receipt-", ".tmp", directory)
        try {
            temporary.outputStream().use { properties.store(it, null) }
            check(temporary.renameTo(record(uri))) { "Cannot publish screenshot frame receipt" }
        } finally {
            temporary.delete()
        }
        val now = System.currentTimeMillis()
        val records = directory.listFiles { file -> file.extension == "properties" }.orEmpty()
            .sortedByDescending { it.lastModified() }
        records.forEachIndexed { index, file ->
            if (index >= LIMIT || now - file.lastModified() > MAX_AGE) file.delete()
        }
    }

    private fun record(uri: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.properties")
    }

    private fun <T> locked(block: () -> T): T = synchronized(processLock) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create screenshot receipt directory" }
        RandomAccessFile(File(directory, ".lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }
}
