package com.takekazex.hypertweak.hook.rules.systemui.island.model

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout

/** One host `handleEvent` call. Snapshots are taken before the host mutates any handler. */
internal class SchedulingTransaction(
    val session: IslandSession,
    val visible: List<Any>,
    val expandedKey: String?,
    val eventView: Any? = null,
) {
    var oldStates = emptyMap<Any, Any?>()
    val oldLayout: ThreeIslandLayout? = session.scene.layout
    val oldFullFrames: Map<Any, FullIslandFrame> = session.scene.fullFrames.toMap()
    var settled = false
    var forcePublication = false
}
