package com.takekazex.hypertweak.hook.rules.camera

/** Compatibility for borrowing motion effects only, not a hardware/calibration identity check. */
internal object CameraMasterLiveCompatibility {
    fun accepts(nativeDdf: Int?, sourceDdf: Int?, nativePhoto: FloatArray?, sourcePhoto: FloatArray?, sourceLive: FloatArray?): Boolean {
        if (nativeDdf == null || nativeDdf <= 0 || nativeDdf != sourceDdf) return false
        fun valid(values: FloatArray?) = values != null && values.isNotEmpty() &&
            values.all { it.isFinite() && it > 0f } && values.asList().zipWithNext().all { (a, b) -> a < b }
        return valid(nativePhoto) && valid(sourcePhoto) && valid(sourceLive) &&
            nativePhoto!!.contentEquals(sourcePhoto!!) && nativePhoto.contentEquals(sourceLive!!)
    }
}
