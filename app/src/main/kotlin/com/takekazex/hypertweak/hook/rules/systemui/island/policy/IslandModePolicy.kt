package com.takekazex.hypertweak.hook.rules.systemui.island.policy

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.model.LayoutFlags

/** Pure gate shared by every host callback. No preference or Android reads occur here. */
internal object IslandModePolicy {
    /** Installed hooks are not ownership: only switches for this window's orientation count. */
    fun requested(orientation: IslandOrientation, threeIslands: Boolean, landscapeFull: Boolean, landscapeMore: Boolean): Boolean =
        orientation != IslandOrientation.UNDEFINED &&
            (threeIslands || orientation == IslandOrientation.LANDSCAPE && (landscapeFull || landscapeMore))

    /**
     * Session-level enablement for the scheduling and row features. Placeholder removal is a
     * stateless rewrite of each measurement and never enables the scheduling session.
     */
    fun enabled(
        retiring: Boolean,
        failed: Boolean,
        suspended: Int,
        threeIslands: Boolean,
        landscapeFull: Boolean,
        landscapeMore: Boolean,
    ): Boolean = !retiring && !failed && suspended == 0 &&
        (threeIslands || landscapeFull || landscapeMore)

    /** Whether any island style switch needs the native island hooks installed. */
    fun installed(threeIslands: Boolean, landscapeFull: Boolean, landscapeMore: Boolean, removePlaceholder: Boolean): Boolean =
        threeIslands || landscapeFull || landscapeMore || removePlaceholder

    /** Only phones with a resolved orientation and no transient/full-screen owner qualify. */
    fun phoneEligible(
        enabled: Boolean,
        orientation: IslandOrientation,
        smallestScreenWidthDp: Int,
        tinyScreen: Boolean,
        inConfigRebuild: Boolean,
        showOncePresent: Boolean,
        tempShowPresent: Boolean,
    ): Boolean = enabled && orientation != IslandOrientation.UNDEFINED &&
        smallestScreenWidthDp < 600 && !tinyScreen && !inConfigRebuild &&
        !showOncePresent && !tempShowPresent

    fun flags(
        phoneEligible: Boolean,
        orientation: IslandOrientation,
        threeIslands: Boolean,
        landscapeFull: Boolean,
        landscapeMore: Boolean,
        removePlaceholder: Boolean = true,
    ): LayoutFlags {
        val eligible = phoneEligible && orientation != IslandOrientation.UNDEFINED
        val landscape = eligible && orientation == IslandOrientation.LANDSCAPE
        val full = landscape && landscapeFull
        val more = landscape && landscapeMore
        val portrait = eligible && orientation == IslandOrientation.PORTRAIT && removePlaceholder
        return LayoutFlags(
            landscapeFull = full,
            landscapeMore = more,
            portraitCompact = portrait,
            rowMode = full || more,
            summarySwipe = eligible && (threeIslands || more),
        )
    }
}
