package com.takekazex.hypertweak.hook.rules.ime

import kotlin.math.pow

/** Restrict theme reads to keyboard surfaces, excluding individual keys and toolbars. */
internal object ImeNativeBottomPolicy {
    fun isSurface(width: Int, height: Int, keyboardWidth: Int, keyboardHeight: Int): Boolean =
        keyboardWidth > 0 && keyboardHeight > 0 && width.toLong() * 4 >= keyboardWidth.toLong() * 3 &&
            height.toLong() * 4 >= keyboardHeight

    /** Choose the higher-contrast native button tint for the actual keyboard background. */
    fun iconColor(background: Int): Int {
        fun linear(channel: Int): Double {
            val s = channel / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val luminance = 0.2126 * linear(background ushr 16 and 255) +
            0.7152 * linear(background ushr 8 and 255) + 0.0722 * linear(background and 255)
        return if (luminance > 0.179128784747792) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
}
