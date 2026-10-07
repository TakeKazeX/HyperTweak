package com.takekazex.hypertweak.dock

import com.takekazex.hypertweak.hook.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.takekazex.hypertweak.util.DebugLog

/** Page disposal must not cancel a submitted switch/slider transaction. */
internal object DockSettingsWriter {
    private val queue = DockSettingsQueue(CoroutineScope(SupervisorJob() + Dispatchers.IO),
        reportFailure = { DebugLog.w("DockBackground", "settings writer failed", it) }) {
        Preferences.commitDockBackground(it)
    }
    val state = queue.state
    fun submit(encoded: String) = queue.submit(encoded)
}

/** Bounded coalescing keeps slider I/O off the UI thread and never cancels an in-flight commit. */
internal class DockSettingsQueue(scope: CoroutineScope, private val reportFailure: (Exception) -> Unit = {},
    private val commit: (String) -> Boolean) {
    data class State(val requested: String? = null, val acknowledged: String? = null,
        val saving: Boolean = false, val failed: Boolean = false, val revision: Long = 0)
    private data class Request(val encoded: String, val revision: Long)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val requests = Channel<Request>(Channel.CONFLATED)
    init {
        scope.launch {
            for (first in requests) {
                delay(80)
                val request = requests.tryReceive().getOrNull() ?: first
                val encoded = request.encoded
                val ok = try { commit(encoded) } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    runCatching { reportFailure(error) }
                    false
                }
                mutable.update { previous -> previous.copy(
                    acknowledged = if (ok) encoded else previous.acknowledged,
                    saving = previous.revision != request.revision,
                    failed = !ok && previous.revision == request.revision,
                ) }
            }
        }
    }
    @Synchronized fun submit(encoded: String) {
        require(DockConfig.decode(encoded) != null)
        val revision = mutable.value.revision + 1
        mutable.update { it.copy(requested = encoded, saving = true, failed = false, revision = revision) }
        check(requests.trySend(Request(encoded, revision)).isSuccess)
    }
}
