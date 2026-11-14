package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SchedulingTransaction
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SummaryIslandScheduler

/** Session capability; contains no host assembly or coordinator implementation. */
internal interface SettlementActions {
    fun commitSwipe(session: IslandSession, before: List<Any>, change: SummaryIslandScheduler.Change): Unit
    fun selectSummaries(session: IslandSession): List<Any>
    fun claimSmall(session: IslandSession, target: Any): Unit
    fun returnToQueue(session: IslandSession): Unit
    fun settle(tx: SchedulingTransaction): Unit
    /** Revoke module projections without publishing or changing native handler state. */
    fun clearLayout(session: IslandSession): Unit
    /** Repair native ownership before publishing; does not flush the host state list itself. */
    fun reconcileNative(session: IslandSession, refresh: Boolean = false): Boolean
    fun restore(session: IslandSession): Unit
    fun swipeCapacity(session: IslandSession, visible: List<Any>): Int
}
