package com.takekazex.hypertweak.hook.rules.systemui.island.policy

/** Capture the task at DOWN; native handler reassignment cannot retarget the same gesture. */
internal class IslandGestureScope {
    var clickKey: String? = null
        private set
    var cancelled = false
        private set
    var expandedKey: String? = null
        private set

    fun begin(key: String?, expanded: Boolean = false) {
        cancelled = false
        clickKey = key
        expandedKey = key.takeIf { expanded }
    }
    fun cancel() { cancelled = true }
    fun mayCollapse(currentExpanded: String?): Boolean = !cancelled &&
        (expandedKey == null || expandedKey == currentExpanded)
    fun allows(viewKey: String?): Boolean = clickKey == null || clickKey == viewKey
}
