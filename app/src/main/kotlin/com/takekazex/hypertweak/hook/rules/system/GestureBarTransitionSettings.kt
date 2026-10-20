package com.takekazex.hypertweak.hook.rules.system

/** Hiding the display copy must never disable the independently requested task attachment. */
internal data class GestureBarTransitionSettings(val attachToApp: Boolean, val hideInRecents: Boolean) {
    val hideDisplayCopy: Boolean get() = attachToApp && hideInRecents

    fun shouldAttach(nativeDefault: Boolean, navigationAvailable: Boolean, tinyTaskbar: Boolean): Boolean =
        navigationAvailable && !tinyTaskbar && (attachToApp || nativeDefault)
}

internal enum class GestureBarRestoreAction { NATIVE, DEFER, IGNORE_RETIRED }

internal fun gestureBarRestoreAction(
    explicitDetach: Boolean, ownsSession: Boolean, sessionDeferred: Boolean, sameController: Boolean
): GestureBarRestoreAction = when {
    ownsSession && explicitDetach -> GestureBarRestoreAction.DEFER
    !ownsSession && !sessionDeferred && sameController -> GestureBarRestoreAction.IGNORE_RETIRED
    else -> GestureBarRestoreAction.NATIVE
}
