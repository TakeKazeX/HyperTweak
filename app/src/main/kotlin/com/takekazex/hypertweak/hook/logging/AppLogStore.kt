// Commit acknowledgement is inspected; KTX edit returns Unit and would lose rejection failures.
@file:Suppress("UseKtx")

package com.takekazex.hypertweak.hook.logging

import android.content.SharedPreferences
import androidx.core.content.edit

/** Local app records only; injected processes continue to use the framework logger. */
internal class AppLogStore(private val source: () -> SharedPreferences?, private val recording: () -> Boolean) {
    val lock = Any()
    companion object {
        private const val LEGACY_KEY_DEBUG_LOG = "debug_log"
        private const val KEY_DEBUG_LOG_PREFIX = "debug_log_p_"
        private const val KEY_LOG_SESSION = "debug_log_session"
        fun isRuntimeKey(key: String): Boolean = key == LEGACY_KEY_DEBUG_LOG ||
            key.startsWith(KEY_DEBUG_LOG_PREFIX) || key == KEY_LOG_SESSION ||
            key == "debug_log_processes" || key.startsWith("debug_log_level_")
        // libxposed mutates its local shadow before IPC, even when commit returns false.
        // Retain deletion identities until acknowledged, since all may hide them on retry.
        private val pendingRemoteCleanupKeys = linkedSetOf<String>()
        /** Purge historical app diagnostics without touching shared controls or revisions.
         * A fresh process rediscovers daemon keys; a cached backend retries retained identities. */
        @Synchronized fun purgeRemoteRecords(remote: SharedPreferences) {
            pendingRemoteCleanupKeys.addAll(remote.all.keys.filter(::isRuntimeKey))
            if (pendingRemoteCleanupKeys.isEmpty()) return
            val keys = pendingRemoteCleanupKeys.toSet()
            check(remote.edit().apply { keys.forEach(::remove) }.commit()) {
                "Remote legacy log cleanup rejected"
            }
            pendingRemoteCleanupKeys.removeAll(keys)
        }
        private const val MAX_DEBUG_LOG_LENGTH = 256_000
    }
    fun appendDebugLog(processTag: String, line: String) = appendDebugLogs(processTag, listOf(line))

    /** App-owned records only. Hook-side records belong to the LSPosed daemon. */
    fun appendDebugLogs(processTag: String, lines: List<String>) {
        synchronized(lock) {
            val local = source() ?: return
            if (lines.isEmpty() || !recording()) return
            val key = debugLogKeyFor(processTag)
            val old = local.getString(key, "").orEmpty()
            val appended = lines.joinToString("\n")
            val next = com.takekazex.hypertweak.util.logging.LogCodec.boundedTail(
                if (old.isBlank()) appended else "$old\n$appended", MAX_DEBUG_LOG_LENGTH
            )
            check(local.edit().putString(key, next).commit()) {
                "App log storage rejected commit"
            }
        }
    }

    fun getDebugLog(): String = synchronized(lock) {
        val local = source() ?: return@synchronized ""
        local.all.filterKeys { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
            .values.filterIsInstance<String>().flatMap { it.lines() }.distinct().joinToString("\n")
    }

    /** Clears app-owned logs. LSPosed files remain owned by the framework. */
    fun clearDebugLog() {
        synchronized(lock) {
            val local = source() ?: return
            val keys = local.all.keys.filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
            check(local.edit().apply { keys.forEach(::remove); remove("debug_log_processes") }.commit()) {
                "App log clearing rejected commit"
            }
        }
    }

    /**
     * Clears all debug logs when the runtime session changes (app update / reinstall / reboot),
     * so records from different sessions are not mixed together. No-op when the token is unchanged.
     */
    fun rotateLogSessionIfNeeded(token: String) {
        synchronized(lock) {
            val local = source() ?: return
            if (local.getString(KEY_LOG_SESSION, null) == token) return
            val keys = local.all.keys.filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
            check(local.edit().apply {
                keys.forEach(::remove)
                remove("debug_log_processes")
                putString(KEY_LOG_SESSION, token)
            }.commit()) { "App log session rotation rejected commit" }
        }
    }

    private fun debugLogKeyFor(processTag: String): String =
        "$KEY_DEBUG_LOG_PREFIX${sanitizeProcessTag(processTag)}"

    /** Process tags are prefs keys, so reduce them to `[A-Za-z0-9_]`. */
    private fun sanitizeProcessTag(processTag: String): String =
        processTag.ifBlank { "unknown" }
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
}
