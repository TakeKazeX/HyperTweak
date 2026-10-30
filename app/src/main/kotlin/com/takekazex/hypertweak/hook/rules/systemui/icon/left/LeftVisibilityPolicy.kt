package com.takekazex.hypertweak.hook.rules.systemui.icon.left

/** Temporary suppression retains clone/slot ownership; only configuration or detach releases it. */
internal object LeftVisibilityPolicy {
    enum class Result(val retain: Boolean, val visible: Boolean) {
        RELEASE(false, false), HOST_HIDDEN(true, false), OTHER_SURFACE(true, false),
        LOCKSCREEN_HIDDEN(true, false), ISLAND(true, false), VISIBLE(true, true)
    }

    fun resolve(
        configured: Boolean, attached: Boolean, hostShown: Boolean, keyguard: Boolean,
        keyguardShowing: Boolean, hideLockscreen: Boolean, island: Boolean
    ): Result = when {
        !configured || !attached -> Result.RELEASE
        keyguardShowing && hideLockscreen -> Result.LOCKSCREEN_HIDDEN
        keyguard != keyguardShowing -> Result.OTHER_SURFACE
        !hostShown -> Result.HOST_HIDDEN
        island -> Result.ISLAND
        else -> Result.VISIBLE
    }

    /** The shade may fade/hide HOME after taking its place; its measured clone remains the source. */
    fun handoverSource(result: Result, panelActive: Boolean, hostDrawn: Boolean,
                       keyguardShowing: Boolean, island: Boolean): Boolean =
        !keyguardShowing && !island && (result.visible && hostDrawn ||
            panelActive && (result == Result.VISIBLE || result == Result.HOST_HIDDEN))
}
