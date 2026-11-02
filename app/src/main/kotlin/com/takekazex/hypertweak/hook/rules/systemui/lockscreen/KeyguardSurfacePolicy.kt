package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

/** Either host can publish first while locking/unlocking. Occlusion alone is not a surface. */
internal object KeyguardSurfacePolicy {
    fun showing(barState: Int?, showing: Boolean?, occluded: Boolean?): Boolean? {
        val bar = barState?.takeIf { it in 0..2 }?.let { it != 0 }
        val keyguard = showing?.let { value -> occluded?.let { value && !it } }
        return if (bar == null && keyguard == null) null else bar == true || keyguard == true
    }
}
