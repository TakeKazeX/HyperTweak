package com.takekazex.hypertweak.settings.contract

internal data class DesktopGridStatus(val stage: Int, val requested: Int, val observed: Int) {
    fun valid(): Boolean = stage in 0..7 && LauncherDesktopKeys.validGrid(requested) && LauncherDesktopKeys.validGrid(observed)
    val applied: Boolean get() = stage == 6 && requested != 0 && requested == observed
    val restored: Boolean get() = stage == 0 && requested == 0
}
