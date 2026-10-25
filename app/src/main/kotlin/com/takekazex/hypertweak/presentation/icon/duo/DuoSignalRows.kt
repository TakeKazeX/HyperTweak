package com.takekazex.hypertweak.presentation.icon.duo

/** Slot identity survives sorting, data-SIM changes and a hidden first SIM. */
data class DuoSignalRow(val subId: Int, val slotIndex: Int, val level: Int)

internal object DuoSignalRows {
    fun ordered(rows: List<DuoSignalRow>, activeDataSubId: Int = -1,
                dataFirst: Boolean = false): List<DuoSignalRow> = rows
        .distinctBy { it.subId }
        .sortedWith(compareBy<DuoSignalRow> {
            if (dataFirst && it.subId == activeDataSubId) 0 else 1
        }.thenBy { if (it.slotIndex >= 0) it.slotIndex else Int.MAX_VALUE })
}
