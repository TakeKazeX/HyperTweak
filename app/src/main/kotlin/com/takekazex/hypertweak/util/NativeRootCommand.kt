package com.takekazex.hypertweak.util

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded root IO, including an unanswered root grant; no orphaned unbounded reader pool. */
internal object NativeRootCommand {
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "HyperTweak-NativeRoot").apply { isDaemon = true } })
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    fun run(command: String, maxBytes: Int = 64 * 1024, timeoutMs: Long = 8000,
        start: () -> Process = { ProcessBuilder("su", "-c", "timeout ${maxOf(1, timeoutMs / 1000 - 1)} sh -c " + quote(command))
            .redirectErrorStream(true).start() }): ByteArray {
        val ref = AtomicReference<Process?>()
        val cancelled = AtomicBoolean()
        val task = FutureTask {
            val process = start()
            ref.set(process)
            if (cancelled.get()) { process.destroyForcibly(); error("root request cancelled") }
            try {
                val bytes = process.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(out.size() + count <= maxBytes) { "root response exceeds bound" }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                process.waitFor()
                check(process.exitValue() == 0) { "root command rejected (${process.exitValue()})" }
                bytes
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        try { executor.execute(task); return task.get(timeoutMs, TimeUnit.MILLISECONDS) }
        finally {
            if (!task.isDone) {
                cancelled.set(true); task.cancel(true); ref.get()?.destroyForcibly(); executor.remove(task)
            }
        }
    }
}
