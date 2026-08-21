package com.takekazex.hypertweak.hook.rules.camera

/**
 * Sensor/lens identity comparison for camera config candidates, shared by the K100 Pro Max
 * resolution path in [CameraImpersonationHooker].
 *
 * The identity getters report the sensor/lens an impersonated config would feed to the MIVI/HAL
 * CCM·WB pipeline. A candidate that does not share this device's own sensor identity must be
 * rejected — that invariant is what keeps Leica Classic + tele RAW from turning purple.
 */
internal object CameraIdentity {

    /** The compared getter names: `O1` = sensor param String, `D`/`r1` = ints, `q1` = `int[]`. */
    val IDENTITY_GETTERS = arrayOf("O1", "D", "q1", "r1")

    /**
     * Mode id of 实况运镜 (MasterLive) in the camera's mode list.
     */
    const val MASTER_LIVE_MODE_ID = 231

    /**
     * Front-load [MASTER_LIVE_MODE_ID] into a config's mode-ordering array (`M()[I`), or
     * return null when nothing changes (array already contains it, or null input).
     *
     * The per-device config's `M()` array is the ONLY consumer input of `u2.P`
     * (ComponentModuleList) for ordering the mode carousel against the 更多 (254) marker: the
     * Nezha (17 Ultra) config fronts `{231,…}` so MasterLive opens the carousel, while the
     * K100 Pro Max config omits it entirely — the mode then lands after the marker, i.e. only
     * in the overflow list. Restoring Nezha-era placement on the K100 target keeps C1200's own
     * REDMI effect table (`q0()`), so no 17U tele/12.9x crash path is resurrected.
     */
    fun frontMasterLiveMode(current: IntArray?): IntArray? {
        if (current == null || current.contains(MASTER_LIVE_MODE_ID)) return null
        return intArrayOf(MASTER_LIVE_MODE_ID) + current
    }

    /**
     * True when every identity getter returns the same value on both configs (a wrong pick
     * feeds 17-Ultra calibration to a different sensor — purple RAW photos).
     *
     * REGRESSION HISTORY (do not reintroduce): comparing the results with plain `==`/equals()
     * silently rejected EVERY K100 candidate on 6.6.000510.0 and fell back to Nezha. `q1()`
     * returns a freshly allocated `int[]` on each call, so reference equality never holds;
     * array values must be deep-compared. A getter invocation FAILURE counts as not-equal
     * (never silently pass), while two genuine nulls count as equal (same unknown identity).
     */
    fun sharesImagingIdentity(candidate: Any, original: Any): Boolean =
        IDENTITY_GETTERS.all { name ->
            val candidateValue = runCatching { candidate.javaClass.getMethod(name).invoke(candidate) }
            val originalValue = runCatching { original.javaClass.getMethod(name).invoke(original) }
            if (candidateValue.isFailure || originalValue.isFailure) {
                false
            } else {
                valueEquals(candidateValue.getOrNull(), originalValue.getOrNull())
            }
        }

    /**
     * Value equality across scalar-or-array identity values (see
     * [sharesImagingIdentity]): scalars by value, arrays element-wise (recursive, so nested
     * arrays and boxed primitive elements behave), `null` only equal to `null`.
     */
    fun valueEquals(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == null && b == null
        if (a.javaClass != b.javaClass) return false
        if (!a.javaClass.isArray) return a == b
        val length = java.lang.reflect.Array.getLength(a)
        if (length != java.lang.reflect.Array.getLength(b)) return false
        for (i in 0 until length) {
            if (!valueEquals(java.lang.reflect.Array.get(a, i), java.lang.reflect.Array.get(b, i))) {
                return false
            }
        }
        return true
    }
}
