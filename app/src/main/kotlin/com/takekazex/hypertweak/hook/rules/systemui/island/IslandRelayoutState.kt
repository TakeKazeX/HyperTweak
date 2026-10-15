package com.takekazex.hypertweak.hook.rules.systemui.island

/** New requests invalidate any preparation made for older geometry/template nodes. */
internal class IslandRelayoutState {
    enum class Phase { IDLE, PREPARE, PUBLISH }
    var phase = Phase.IDLE
        private set
    var generation = 0L
        private set
    fun request() { generation++; phase = Phase.PREPARE }
    fun prepared(token: Long) { if (token == generation && phase == Phase.PREPARE) phase = Phase.PUBLISH }
    fun published(token: Long) { if (token == generation && phase == Phase.PUBLISH) phase = Phase.IDLE }
    fun cancel() { generation++; phase = Phase.IDLE }
}
