package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

/**
 * What the host last asked for a keyguard status bar view - kept apart from the module's own
 * suppression. Each intercepted argument updates exactly one dimension, so a host that changes
 * visibility while the row is hidden does not lose its alpha (and vice versa). Disabling writes
 * this record back verbatim.
 */
internal data class LockscreenStatusBarIntent(val visibility: Int, val alpha: Float) {
    fun withVisibility(value: Int): LockscreenStatusBarIntent = copy(visibility = value)
    fun withAlpha(value: Float): LockscreenStatusBarIntent = copy(alpha = value)
}
