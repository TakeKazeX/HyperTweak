package com.takekazex.hypertweak.hook.base.dex

/**
 * Per-resolution phase timing for the `DexRuntime` result line.
 *
 * Before this, one `scanned in N ms` number mixed the APK hash, the wait for a sibling process,
 * opening the DexKit bridge, loading dexlib2 class definitions and the resolver itself, so a slow
 * cold feature could not be attributed. Each [DexRuntime.resolve] opens a frame on its thread;
 * [measure] adds to the innermost frame, so a nested resolution (operand string lookups) keeps its
 * own phases. Work that a shared artifact already did for an earlier feature costs nothing here,
 * which is the point: the line shows what this feature actually waited for.
 */
internal object DexPhases {
    private val frames = ThreadLocal<ArrayDeque<LinkedHashMap<String, Long>>>()

    inline fun <T> measure(phase: String, block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            add(phase, System.nanoTime() - started)
        }
    }

    fun add(phase: String, nanos: Long) {
        val frame = frames.get()?.lastOrNull() ?: return
        frame[phase] = (frame[phase] ?: 0L) + nanos
    }

    /** Runs [block] in a new frame; [block] receives a formatter for the frame's phases so far. */
    fun <T> frame(block: (() -> String) -> T): T {
        val stack = frames.get() ?: ArrayDeque<LinkedHashMap<String, Long>>().also(frames::set)
        val frame = LinkedHashMap<String, Long>()
        stack.addLast(frame)
        try {
            return block { format(frame) }
        } finally {
            stack.removeLast()
            if (stack.isEmpty()) frames.remove()
        }
    }

    /** `identity:3,bridge:40,scan:812`; phases under 1 ms are left out. */
    fun format(frame: Map<String, Long>): String = frame.entries
        .filter { it.value >= 1_000_000L }
        .joinToString(",") { it.key + ":" + it.value / 1_000_000L }
}
