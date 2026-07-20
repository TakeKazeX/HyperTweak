package com.takekazex.hypertweak.hook.rules.module

internal object SettingsHeaderPlacement {
    fun before(anchorIndex: Int, listSize: Int): Int {
        if (anchorIndex < 0) return minOf(2, listSize)
        return anchorIndex.coerceAtMost(listSize)
    }
}
