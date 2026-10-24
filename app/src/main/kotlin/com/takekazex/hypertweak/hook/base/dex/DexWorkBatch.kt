package com.takekazex.hypertweak.hook.base.dex

/** Adjacent preparations for one APK share a lazy native session, without retaining it at idle. */
internal class DexWorkBatch(
    private val execute: (Runnable) -> Unit,
    private val withScope: (String?, () -> Unit) -> Unit,
    private val failure: (Throwable) -> Unit,
) {
    private data class Work(val apkPath: String?, val action: () -> Unit)
    private val queue = ArrayDeque<Work>()
    private var scheduled = false

    @Synchronized fun submit(apkPath: String?, action: () -> Unit) {
        check(queue.size < 32) { "DEX preparation queue is full" }
        queue.addLast(Work(apkPath, action))
        if (scheduled) return
        scheduled = true
        try { execute(Runnable(::drain)) }
        catch (error: Throwable) {
            scheduled = false
            queue.removeLast()
            throw error
        }
    }

    private fun drain() {
        while (true) {
            val first = synchronized(this) {
                if (queue.isEmpty()) { scheduled = false; return }
                queue.removeFirst()
            }
            try {
                withScope(first.apkPath) {
                    var work: Work? = first
                    while (work != null) {
                        try { work.action() } catch (error: Throwable) { failure(error) }
                        work = synchronized(this) {
                            queue.firstOrNull()?.takeIf { it.apkPath == first.apkPath }?.let { queue.removeFirst() }
                        }
                    }
                }
            } catch (error: Throwable) { failure(error) }
        }
    }
}
