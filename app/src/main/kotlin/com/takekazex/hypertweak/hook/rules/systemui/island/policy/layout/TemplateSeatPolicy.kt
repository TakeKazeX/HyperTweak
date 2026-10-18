package com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout


/** Whether a native template keeps its physical camera seat between the two content areas. */
internal enum class TemplateSeatMode { NORMAL, CAMERA_RELEASING }

internal object TemplateSeatPolicy {
    /**
     * Landscape full rows and an admitted portrait side-media template have no camera between
     * their areas. Portrait main, expanded/big promotion and compact non-full templates keep it.
     */
    fun modeFor(isSideMusic: Boolean, rowMode: Boolean, frame: FullIslandFrame?): TemplateSeatMode =
        if (isSideMusic || (rowMode && frame?.full == true)) TemplateSeatMode.CAMERA_RELEASING
        else TemplateSeatMode.NORMAL
}
