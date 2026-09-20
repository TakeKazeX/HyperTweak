package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

/** Slot identity survives sorting, data-SIM changes and a hidden first SIM. */
data class DuoSignalRow(val subId: Int, val slotIndex: Int, val level: Int)

internal object DuoSignalRows {
    fun ordered(rows: List<DuoSignalRow>): List<DuoSignalRow> = rows
        .distinctBy { it.subId }
        .sortedBy { if (it.slotIndex >= 0) it.slotIndex else Int.MAX_VALUE }
}
