package com.takekazex.hypertweak.hook.rules.systemui.icon

/**
 * One owner's host-flow subscriptions. Partial binding can be rolled back as a group;
 * initial StateFlow replay uses the same generation gate as subsequent emissions.
 */
internal class HostFlowSubscriptions(
    private val acquired: (HostFlowCollector.Handle) -> Unit = {},
    private val released: (List<HostFlowCollector.Handle>) -> Unit = {},
    private val post: (() -> Unit) -> Unit = HostFlowCollector::post,
    private val initialValue: (Any) -> Any? = IconTunerFlows::readFlowValue,
    private val collectHost: (Any?, Any?, (Any?) -> Unit, () -> Boolean) -> HostFlowCollector.Handle? =
        { scope, flow, consumer, current -> HostFlowCollector.collect(scope, flow, consumer, current) }
) {
    @Volatile private var epoch = 0L
    private val handles = ArrayList<HostFlowCollector.Handle>()
    val isBound: Boolean get() = handles.isNotEmpty()
    fun snapshot(): List<HostFlowCollector.Handle> = handles.toList()

    fun collect(
        scope: Any?, flow: Any?, isCurrent: () -> Boolean,
        replay: Boolean = false, consumer: (Any?) -> Unit
    ): Boolean {
        return collectUsing(flow, isCurrent, replay,
            start = { guarded, current -> collectHost(scope, flow, guarded, current) }, consumer = consumer)
    }

    /** Custom host collectors (such as native visibility registration) use the same retirement gate. */
    fun collectUsing(
        flow: Any?, isCurrent: () -> Boolean, replay: Boolean = false,
        start: ((Any?) -> Unit, () -> Boolean) -> HostFlowCollector.Handle?,
        consumer: (Any?) -> Unit
    ): Boolean {
        val ticket = epoch
        val current = { ticket == epoch && isCurrent() }
        val handle = start({ if (current()) consumer(it) }, current) ?: return false
        handles += handle
        acquired(handle)
        if (replay && flow != null) initialValue(flow)?.let { initial ->
            post { if (current()) consumer(initial) }
        }
        return true
    }

    fun clear() {
        epoch++
        val retiring = snapshot()
        handles.clear()
        retiring.forEach { it.cancel() }
        released(retiring)
    }
}
