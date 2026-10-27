package com.takekazex.hypertweak.util.platform

/** Installation alone deliberately disables overlapping features unless debugging overrides it. */
internal object UpstreamBackGesturePolicy {
    fun shouldYield(installed: Boolean, detectionDisabled: Boolean): Boolean = installed && !detectionDisabled
}
