package com.takekazex.hypertweak.hook.rules.screenshot

import kotlin.math.abs

/** Only a complete, primary-display portrait capture can enter the native phone-frame renderer. */
internal object ScreenshotFramePolicy {
    const val MAX_PIXELS = 24_000_000L

    fun eligible(type: Int, displayId: Int, width: Int, height: Int, displayWidth: Int, displayHeight: Int): Boolean =
        type == 1 && displayId == 0 && width > 0 && height > width &&
            width == displayWidth && height == displayHeight && width.toLong() * height <= MAX_PIXELS

    fun matchesMaterial(width: Int, height: Int, materialWidth: Float, materialHeight: Float): Boolean =
        materialWidth.isFinite() && materialHeight.isFinite() && materialWidth > 0 && materialHeight > 0 &&
            width > 0 && height > width && abs(width.toDouble() / height - materialWidth / materialHeight) < 0.001

    fun safeOutput(width: Float, height: Float): Boolean = width.isFinite() && height.isFinite() &&
        width > 0 && height > width && width.toDouble() * height <= MAX_PIXELS

    /** Android adds a user prefix when passing an image to its own screenshot editor. */
    private val mediaUri = Regex("^content://(?:(\\d+)@)?media/(external|external_primary)/images/media/(\\d+)(?:[?#].*)?$")
    fun mediaKey(uri: String, user: Int): String? {
        val match = mediaUri.matchEntire(uri) ?: return null
        val explicitUser = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (explicitUser != null && explicitUser != user) return null
        return "$user:external:${match.groupValues[3]}"
    }
}
