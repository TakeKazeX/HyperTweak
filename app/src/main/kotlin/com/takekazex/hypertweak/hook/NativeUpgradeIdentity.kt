package com.takekazex.hypertweak.hook

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeUpgradeIdentity {
    fun ready(bytes: ByteArray, version: Long, pid: Int, start: Long): Boolean {
        if (bytes.size != 28 || version !in 1..Int.MAX_VALUE.toLong() || pid <= 0 || start <= 0) return false
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val schema = b.int; val actualVersion = b.int; val actualPid = b.int; val initialized = b.int
        val actualStart = b.long
        return schema == 1 && initialized == 1 && actualVersion.toLong() == version && actualPid == pid &&
            actualStart == start && b.int == (0x48544e53 xor actualVersion xor actualPid xor
            actualStart.toInt() xor (actualStart ushr 32).toInt())
    }
    fun needsUpdate(installedHash: String, loadedHash: String): Boolean {
        require(installedHash.matches(Regex("[0-9a-f]{64}")) && loadedHash.matches(Regex("[0-9a-f]{64}")))
        return installedHash != loadedHash
    }
    fun procStart(stat: String): Long {
        // comm may contain whitespace/parentheses; fields begin after the final closing bracket.
        val tail = stat.substringAfterLast(") ", "").trim().split(Regex("\\s+"))
        return tail.getOrNull(19)?.toLongOrNull()?.takeIf { it > 0 } ?: error("invalid process start identity")
    }
}
