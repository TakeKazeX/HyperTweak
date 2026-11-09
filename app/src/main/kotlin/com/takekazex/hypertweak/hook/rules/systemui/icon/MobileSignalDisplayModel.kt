package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.presentation.icon.MobileSignalKind
import com.takekazex.hypertweak.presentation.icon.MobileSignalModel
import com.takekazex.hypertweak.presentation.icon.MobileSubscriptionState

/** One subscription's final host artwork wins over the generic AOSP strength estimate. */
internal class MobileSignalDisplayModel(private val iconLevels: Map<Int, Int>) {
    private var model = MobileSignalModel.unknown()
    var iconId: Int? = null
        private set

    fun model(value: MobileSignalModel): MobileSignalModel {
        model = value
        return resolved()
    }

    fun icon(value: Int?): MobileSignalModel {
        iconId = value?.takeIf { it > 0 }
        return resolved()
    }

    private fun resolved(): MobileSignalModel {
        // An integer alone cannot turn unknown/satellite state into supported cellular state.
        val level = iconLevels[iconId] ?: return model
        return if (model.kind == MobileSignalKind.CELLULAR) model.copy(level = level, numberOfLevels = 5)
            else model
    }

    companion object {
        fun levels(tables: List<IntArray>): Map<Int, Int> {
            val levels = LinkedHashMap<Int, Int>()
            val ambiguous = HashSet<Int>()
            tables.filter { it.size >= 2 && it.all { id -> id != 0 } }.forEach { table ->
                table.forEachIndexed { index, id ->
                    val level = MobileSubscriptionState.normalizeLevel(index, table.size)
                    if (levels.putIfAbsent(id, level)?.let { it != level } == true) ambiguous += id
                }
            }
            ambiguous.forEach(levels::remove)
            return levels
        }
    }
}
