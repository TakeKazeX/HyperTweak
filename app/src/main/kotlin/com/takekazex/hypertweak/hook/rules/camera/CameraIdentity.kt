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

    /** 徕卡一瞬 (Leica Moment) module id. */
    const val LEGENDARY_MOMENT_MODE_ID = 256

    /**
     * The full zoom-toggle stops served for mode 231 when the config has none
     * (`CameraImpersonationHooker.hookMasterLiveFullFocal`): the K100 Pro Max `v1()[231]`
     * line-up {0.7x, 1x, 2x, 5x, 10x} (510 `C1200.java:549-550`). K100 shares myron's
     * sensor axis bit-for-bit and these stops are exactly myron's real optics (0.7x OV50M
     * ultra / 1x OV50Q main / 2x digital / 5x·120mm JN5 tele / 10x digital), so the strip
     * shows the camera's complete focal range instead of the stock `{1.0x, 2.0x}` fallback
     * (`j.R` → `p723ur.i#q` fallback; RESEARCH_MYRON_12_MASTERLIVE_FOCAL_STRIP.md).
     */
    val MASTER_LIVE_FOCAL_STOPS = floatArrayOf(0.7f, 1.0f, 2.0f, 5.0f, 10.0f)

    /**
     * [MASTER_LIVE_FOCAL_STOPS] boxed as the `v1()` value type. The verified builds store
     * `Float[]` values (`SparseArray<Float[]>`); an existing primitive `float[]` entry is
     * mirrored defensively so a consumer's check-cast can never fail on the injected key.
     */
    fun masterLiveFocalStops(existingValue: Any?): Any =
        if (existingValue is FloatArray) MASTER_LIVE_FOCAL_STOPS else MASTER_LIVE_FOCAL_STOPS.toTypedArray()

    /**
     * The 更多 (more/overflow) marker mode id. ComponentModuleList (`u2.S` on 540, `u2.P` on
     * 510, `u2.U` on 460) splits the mode
     * strip at the FIRST item whose id is this marker (`C()`, jadx `p700u2/P.java:469-490`):
     * items before it form the carousel (`s()`), items after it the overflow panel (`v()`).
     */
    const val MODE_LIST_MORE_MARKER = 254

    /**
     * Front-load [MASTER_LIVE_MODE_ID] into a config's mode-ordering array (`M()[I`), or
     * return null when nothing changes (array already contains it, or null input).
     *
     * The per-device config's `M()` array is the ONLY consumer input of ComponentModuleList
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
     * Semantic shape of ComponentModuleList's static default list field (`k` / jadx `f62382k`,
     * `p700u2/P.java:51`): an int[] that contains BOTH the 更多 marker [MODE_LIST_MORE_MARKER]
     * and [MASTER_LIVE_MODE_ID]. On every verified build (6.6.000460.0 `u2.U`,
     * 6.6.000510.0 `u2/P.smali` clinit `{…0xfe(254)…0xe7(231)…}`) this is the ONLY static
     * int[] with that pair, which makes it a rename-proof class validator.
     */
    fun defaultModeListShape(list: IntArray?): Boolean =
        list != null && list.contains(MASTER_LIVE_MODE_ID) && list.contains(MODE_LIST_MORE_MARKER)

    /**
     * Place [MASTER_LIVE_MODE_ID] BEFORE the first [MODE_LIST_MORE_MARKER] of a resolved mode
     * order array, or return null when the order already satisfies that invariant (or cannot
     * be corrected). The input array is never mutated; a corrected COPY is returned.
     *
     * WHY THIS EXISTS (root cause of “实况运镜不在轮播里”, camera 6.6.000510.0):
     *
     * The config's `M()` fronting ([frontMasterLiveMode]) only reaches the visible strip
     * through `u2.P.t(Q)` (`p700u2/P.java:295-337`) — and `t(Q)` is consulted ONLY when BOTH
     * caches are cold:
     *  1. the in-memory sort cache `f62389h`, seeded by the constructor (`P.java:110`) and then
     *     REWRITTEN by `K(iArr3,false)` at the end of every render (`o()`, `P.java:822-824`)
     *     with the support-filtered rendered order — one session where 231 was not rendered
     *     (impersonation off, or the pre-fix no-op hook) erases it from the cache;
     *  2. the persisted `pref_camera_sort_modes_key` string preferred by `y(Q)`
     *     (`P.java:895-915`), written whenever the user edits modes
     *     (`K(iArr,true)` → `H()` → `I(x())`, `P.java:594-607/568-583`) and migrated across
     *     app upgrades (`Ac/e.java:155-246`).
     *
     * Hooking the FUNNEL `y(Q)` instead of any single source corrects whichever list wins:
     * the fast-path cache, the persisted user order, or the freshly built `t(Q)` output. The
     * static default list itself also lists 231 AFTER the marker (`f62382k`, index 12 vs
     * marker index 7), so even the stock fallback lands MasterLive in the overflow — hence the
     * explicit re-placement here rather than relying on defaults.
     *
     * Semantics:
     *  - null input or empty array → null (nothing sensible to reorder; an empty/absent order
     *    makes `C()` treat every supported item as carousel anyway).
     *  - 231 already before the first marker (Nezha-native arrays, previously-corrected
     *    caches) → null, keeping the caller's original reference (important: `o()` mutates its
     *    `iArrX` operand in place, which is stock behaviour for the un-corrected fast path).
     *  - otherwise → copy with ONE occurrence of 231 moved/inserted to sit immediately before
     *    the first marker; without any marker, 231 is inserted at the front (mirrors the
     *    Nezha layout).
     */
    fun placeMasterLiveModeBeforeMarker(order: IntArray?): IntArray? {
        if (order == null || order.isEmpty()) return null
        val firstMarker = order.indexOfFirst { it == MODE_LIST_MORE_MARKER }
        val currentIndex = order.indexOfFirst { it == MASTER_LIVE_MODE_ID }
        if (currentIndex >= 0 && (firstMarker < 0 || currentIndex < firstMarker)) return null

        val mutable = order.toMutableList()
        if (currentIndex >= 0) {
            mutable.removeAt(currentIndex)
        }
        // Re-locate the marker after a removal may have shifted indices (only when 231 sat
        // before the marker, which the guard above already excluded — kept defensive).
        val insertAt = mutable.indexOfFirst { it == MODE_LIST_MORE_MARKER }.takeIf { it >= 0 } ?: 0
        mutable.add(insertAt, MASTER_LIVE_MODE_ID)
        return mutable.toIntArray()
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
