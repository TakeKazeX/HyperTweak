package com.takekazex.hypertweak.hook.base.dex

/** Adjacent preparations for one APK share a lazy native session, without retaining it at idle. */
internal class DexWorkBatch(
    private val execute: (Runnable) -> Unit,
    private val withScope: (String?, () -> Unit) -> Unit,
    private val failure: (Throwable) -> Unit,
) {
    private data class Work(val apkPath: String?, val action: () -> Unit, val low: Boolean)
    private val queue = ArrayDeque<Work>()
    private var scheduled = false

    /**
     * [low] work runs after every normal item queued before it finishes, keeping its own FIFO order.
     * The single worker is never preempted, so this only reorders what is still waiting: on a cold
     * Security Center start, the game-only GameBooster scans had held the sidebar Dock back ~10 s.
     */
    @Synchronized fun submit(apkPath: String?, low: Boolean = false, action: () -> Unit) {
        check(queue.size < 32) { "DEX preparation queue is full" }
        val work = Work(apkPath, action, low)
        val firstLow = if (low) -1 else queue.indexOfFirst { it.low }
        if (firstLow < 0) queue.addLast(work) else queue.add(firstLow, work)
        if (scheduled) return
        scheduled = true
        try { execute(Runnable(::drain)) }
        catch (error: Throwable) {
            scheduled = false
            queue.remove(work)
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
