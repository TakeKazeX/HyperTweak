package com.takekazex.hypertweak.hook.rules.systemui.island

/** Live-update small icons use notification alpha masks; custom island artwork is excluded. */
internal object LiveUpdateIconPolicy {
    const val PICTURE = "miui.focus.pic_liveupdate"

    fun eligible(promoted: Boolean, picture: String?, type: Int?, effect: String?): Boolean =
        promoted && picture == PICTURE && type == 3 && effect.isNullOrEmpty()

    fun darkMonochrome(pixels: IntArray): Boolean {
        var visible = false
        for (pixel in pixels) {
            if (pixel ushr 24 < 32) continue
            visible = true
            val r = pixel ushr 16 and 255
            val g = pixel ushr 8 and 255
            val b = pixel and 255
            if (maxOf(r, g, b) > 64 || maxOf(r, g, b) - minOf(r, g, b) > 8) return false
        }
        return visible
    }
}
