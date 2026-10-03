package com.takekazex.hypertweak.util

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The public libxposed service exposes settings/files, but no framework log-reading API. */
object LsposedLogReader {
    enum class Status { READY, ROOT_UNAVAILABLE, NO_FILES, TIMED_OUT, FAILED }
    data class Result(val records: List<LogRecord>, val status: Status)
    // A blocked su pipe must not hold the UI/diagnostic caller past its deadline or create an
    // unlimited number of reader threads on repeated refreshes.
    private val reader = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { r -> Thread(r, "HyperTweak-LSPosedLogs").apply { isDaemon = true } })
    // Fixed, read-only paths. Never interpolate user text or modify the daemon's log files.
    internal val command = """
        files=${'$'}(ls -1t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 4 | sort)
        [ -n "${'$'}files" ] || exit 42
        for f in ${'$'}files; do
            echo '----HyperTweak log file----'
            tail -c 2097152 "${'$'}f" || exit 43
        done
    """.trimIndent()

    /** Call from IO; the deadline includes root approval, pipe draining and process exit. */
    fun read(): Result = read(8000) {
        ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    }

    internal fun read(timeoutMs: Long, start: () -> Process): Result {
        val processRef = AtomicReference<Process?>()
        val cancelled = AtomicBoolean(false)
        val task = FutureTask {
            val process = try { start() } catch (_: Exception) {
                return@FutureTask Result(emptyList(), Status.ROOT_UNAVAILABLE)
            }
            processRef.set(process)
            if (cancelled.get()) {
                process.destroyForcibly()
                return@FutureTask Result(emptyList(), Status.TIMED_OUT)
            }
            try {
                var foundFile = false
                val records = process.inputStream.bufferedReader().use { stream ->
                    LsposedLogParser.parse(stream.lineSequence().onEach {
                        if (it == "----HyperTweak log file----") foundFile = true
                    })
                }
                process.waitFor()
                val status = when {
                    process.exitValue() == 0 && foundFile -> Status.READY
                    process.exitValue() == 42 -> Status.NO_FILES
                    process.exitValue() == 43 -> Status.FAILED
                    else -> Status.ROOT_UNAVAILABLE
                }
                Result(records, status)
            } catch (_: Exception) {
                Result(emptyList(), Status.FAILED)
            } finally {
                process.destroy()
            }
        }
        try {
            reader.execute(task)
            return task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return Result(emptyList(), Status.TIMED_OUT)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result(emptyList(), Status.TIMED_OUT)
        } catch (_: Exception) {
            return Result(emptyList(), Status.FAILED)
        } finally {
            if (!task.isDone) {
                cancelled.set(true)
                task.cancel(true)
                processRef.get()?.destroyForcibly()
                reader.remove(task)
            }
        }
    }
}
