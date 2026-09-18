package com.takekazex.hypertweak.hook.rules.systemui

/**
 * Pure math behind the freeform blur transition, kept free of Android types so the JVM tests can
 * cover it.
 *
 * `MultiTaskingCoverLayerController.setupCoverLayerStyle(transaction, snapshotAlpha, darkAlpha,
 * iconAlpha, blurRadius)` is called on every animation frame by the Folme mask path. The ROM drives
 * `blurRadius` from 100 down to 0, but the sidebar linkage way reaches it with `blurRadius = 0`
 * while the icon is still visible — that frame would show a fully transparent cover with neither
 * blur nor scrim. Re-deriving both from `iconAlpha` keeps a mask on screen and fades it out with the
 * same curve instead of hard-cutting.
 *
 * [DEFAULT_BLUR_RADIUS] and [DEFAULT_DARK_ALPHA] are the values the linkage entry points seed the
 * mask with, so the synthetic ramp starts where the real transition starts.
 */

/** Mask blur radius the linkage path seeds before the window animation begins. */
internal const val DEFAULT_BLUR_RADIUS = 100.0f

/** Mask dark-scrim alpha the linkage path seeds before the window animation begins. */
internal const val DEFAULT_DARK_ALPHA = 0.2f

/** Mask icon alpha the linkage path seeds before the window animation begins. */
internal const val DEFAULT_ICON_ALPHA = 1.0f

/**
 * Blur radius to apply for a frame. A positive `blurRadius` (the Folme interpolation) always wins;
 * only the `0 while the icon is still up` case is re-derived, so the transition still fades out
 * smoothly instead of collapsing to "no blur".
 */
internal fun freeformBlurRadius(blurRadius: Float, iconAlpha: Float): Float =
    if (needsSyntheticCoverMask(blurRadius, iconAlpha)) {
        iconAlpha * DEFAULT_BLUR_RADIUS
    } else {
        blurRadius
    }

/**
 * Dark-scrim alpha to apply for a frame. Only synthesised on the same frame that needed a synthetic
 * blur, and only when the ROM left the scrim at zero — an explicit scrim from Folme is never
 * overridden, and a frame that carries real blur is never given a scrim it did not ask for.
 */
internal fun freeformDarkAlpha(darkAlpha: Float, blurRadius: Float, iconAlpha: Float): Float =
    if (needsSyntheticCoverMask(blurRadius, iconAlpha) && darkAlpha <= 0.0f) {
        iconAlpha * DEFAULT_DARK_ALPHA
    } else {
        darkAlpha
    }

/**
 * True when the frame arrived with no blur while the icon is still visible, i.e. the "fully
 * transparent cover" frame the sidebar linkage way produces. Both re-derivations are gated on this
 * one condition so a frame that deliberately carries blur but no scrim is left alone.
 */
private fun needsSyntheticCoverMask(blurRadius: Float, iconAlpha: Float): Boolean =
    blurRadius <= 0.0f && iconAlpha > 0.0f
