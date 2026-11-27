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
    /**
     * One resolution's phases. Phases are exclusive: time a phase spends inside a nested phase
     * (the `lock` wait inside `identity`, the `bridge` open inside `scan`) is reported only under
     * the nested one, so the printed phases add up instead of counting a wait twice.
     */
    class Frame {
        val phases = LinkedHashMap<String, Long>()
        /** Nested time collected by each phase still running, innermost last. */
        val open = ArrayDeque<LongArray>()
    }

    private val frames = ThreadLocal<ArrayDeque<Frame>>()

    fun current(): Frame? = frames.get()?.lastOrNull()

    inline fun <T> measure(phase: String, block: () -> T): T {
        val frame = current() ?: return block()
        val nested = LongArray(1)
        frame.open.addLast(nested)
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsed = System.nanoTime() - started
            frame.open.removeLast()
            record(frame, phase, elapsed - nested[0], elapsed)
        }
    }

    /** Records [nanos] of an already measured wait; it counts as nested time of the running phase. */
    fun add(phase: String, nanos: Long) {
        val frame = current() ?: return
        record(frame, phase, nanos, nanos)
    }

    fun record(frame: Frame, phase: String, own: Long, total: Long) {
        frame.phases[phase] = (frame.phases[phase] ?: 0L) + own.coerceAtLeast(0L)
        frame.open.lastOrNull()?.let { it[0] += total }
    }

    /** Runs [block] in a new frame; [block] receives a formatter for the frame's phases so far. */
    fun <T> frame(block: (() -> String) -> T): T {
        val stack = frames.get() ?: ArrayDeque<Frame>().also(frames::set)
        val frame = Frame()
        stack.addLast(frame)
        try {
            return block { format(frame.phases) }
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
