package com.takekazex.hypertweak.hook.rules.module

internal object SettingsHeaderPlacement {
    /** No guessed placement: join only a native anchor that exists in the current header list. */
    fun after(anchorIndex: Int, listSize: Int): Int {
        require(anchorIndex in 0 until listSize)
        return anchorIndex + 1
    }
}
