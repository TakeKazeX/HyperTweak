package com.takekazex.hypertweak.dock

data class DockPresentation(val retainHost: Boolean, val showLayer: Boolean, val probeTexture: Boolean)

/** Parent Surface visibility/animation belongs to WMS. A hidden home window is still the same host. */
object DockPresentationPolicy {
    fun resolve(enabled: Boolean, validParent: Boolean, interactive: Boolean, locked: Boolean,
        nativeHidden: Boolean, onScreen: Boolean): DockPresentation {
        val retain = enabled && validParent
        // Power/keyguard state changes precede the parent's screen-off transition. Hiding
        // this child there cuts the Dock out of the still-running launcher animation.
        val show = retain && !nativeHidden
        return DockPresentation(retain, show, show && interactive && !locked && onScreen)
    }
}
