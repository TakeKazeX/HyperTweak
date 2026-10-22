package com.takekazex.hypertweak.hook.rules.aod

/** Native eligibility includes preview layouts and styles with no battery container. */
internal object AodRowPolicy {
    fun showNativeRow(nativeRequested: Boolean, moduleShowRow: Boolean): Boolean =
        nativeRequested && moduleShowRow
}
