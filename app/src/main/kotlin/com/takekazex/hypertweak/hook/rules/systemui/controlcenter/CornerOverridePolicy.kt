package com.takekazex.hypertweak.hook.rules.systemui.controlcenter

/**
 * Control-center corner override rule shared by the hooker and its tests.
 *
 * "No override" is not zero: while the master switch is off, or the stored value is not a usable
 * positive radius, the host's own request is passed through untouched. Only an active override may
 * substitute a value, and releasing it restores the host's own radius.
 */
internal object CornerOverridePolicy {
    fun applies(enabled: Boolean, radiusPx: Float): Boolean =
        enabled && radiusPx.isFinite() && radiusPx > 0f

    /** The host radius recorded before the module replaced it, or null when nothing was recorded. */
    fun releaseRadius(record: CornerOverrideRecord?): Float? = record?.hostRadius
}

/** One surface the module rounded, with the host's own radius and drawable/outline. */
internal class CornerOverrideRecord(
    var hostRadius: Float,
    var background: android.graphics.drawable.Drawable?,
    var outlineProvider: android.view.ViewOutlineProvider? = null,
    var appliedBackground: android.graphics.drawable.Drawable? = background
)
