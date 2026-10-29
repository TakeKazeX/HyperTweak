package com.takekazex.hypertweak.ui.state

import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Keep the newest slider value, without cancelling a commit or losing it on page disposal. */
internal object DesktopIconSizeWriter {
    private val queue = DesktopIconSizeQueue(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val state = queue.state
    fun submit(value: Int, commit: (Int) -> Boolean) = queue.submit(value, commit)
}

internal class DesktopIconSizeQueue(scope: CoroutineScope) {
    data class State(val revision: Long = 0, val requested: Int = -1, val saving: Boolean = false,
        val succeeded: Boolean = false)
    private data class Request(val state: State, val commit: (Int) -> Boolean)
    private val mutable = MutableStateFlow(State())
    private val requests = Channel<Request>(Channel.CONFLATED)
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            for (first in requests) {
                delay(80)
                val request = requests.tryReceive().getOrNull() ?: first
                val ok = try { request.commit(request.state.requested) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                mutable.update { previous ->
                    if (previous.revision == request.state.revision) previous.copy(saving = false, succeeded = ok)
                    else previous
                }
            }
        }
    }

    @Synchronized fun submit(value: Int, commit: (Int) -> Boolean) {
        require(LauncherDesktopKeys.validPercent(value))
        val previous = mutable.value
        if (previous.revision > 0 && previous.requested == value && (previous.saving || previous.succeeded)) return
        val next = State(previous.revision + 1, value, saving = true)
        mutable.value = next
        check(requests.trySend(Request(next, commit)).isSuccess)
    }
}
