package com.takekazex.hypertweak.util.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Shared file limits for the picker and direct settings import/export callers. */
internal object SettingsBackupDocument {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_DEPTH = 32

    /** The caller owns the stream. Read at most the limit plus one byte, even for unknown sizes. */
    fun read(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - output.size() + 1))
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Settings backup exceeds 4 MiB" }
            output.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return validate(decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString())
    }

    /** Check depth before org.json recurses; ignore structural characters inside quoted strings. */
    fun validate(document: String): String {
        require(document.length <= MAX_BYTES && document.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Settings backup exceeds 4 MiB"
        }
        var quoted = false
        var escaped = false
        var depth = 0
        for (char in document) {
            require(char != '\u0000') { "Settings backup contains a NUL character" }
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    // Android org.json accepts single quotes/comments, unlike standard JSON.
                    // Reject them here so they cannot bypass this pre-parse depth check.
                    '\'', '/', '#' -> throw IllegalArgumentException("Settings backup requires standard JSON")
                    '{', '[' -> { depth++; require(depth <= MAX_DEPTH) { "Settings backup is too deeply nested" } }
                    '}', ']' -> { depth--; require(depth >= 0) { "Unbalanced settings backup" } }
                }
            }
        }
        require(!quoted && depth == 0) { "Incomplete settings backup" }
        return document.removePrefix("\uFEFF")
    }
}
