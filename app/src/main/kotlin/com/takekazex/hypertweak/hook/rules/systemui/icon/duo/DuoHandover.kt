package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

internal object DuoHandover {
    const val GRACE_MS = 350L
    fun keepPrevious(since: Long, now: Long): Boolean = since >= 0 && now - since in 0 until GRACE_MS
}
