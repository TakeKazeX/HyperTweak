package com.takekazex.hypertweak.hook.rules.camera

/**
 * Pure decision logic for injecting the 红毯运镜 (MasterLive effect type `"1"`, slow-motion
 * movement) entry into the served MasterLive effect table
 * (`Preferences.KEY_CAMERA_MASTERLIVE_RED_CARPET`; research:
 * RESEARCH_MYRON_10_MASTERLIVE_REDCARPET.md).
 *
 * Background: the K100 Pro Max table (`q0()` → `Map<String, Le.a>`) ships types `"0"`
 * (超清实况), `"2"` (主角非线性) and `"3"` (自由线性) only — the 17-Ultra-exclusive `"1"`
 * 红毯运镜 is absent, which is why it never appeared in the effect selector even though every
 * UI resource for it ships on every device (the panel hints, icon and preview video are
 * hard-coded per type in `C4673d0#initItems`). The hooker synthesizes the `"1"` entry as a
 * CLONE of the proven-working linear entry (`"3"`) with a changed type id and a cleared
 * default flag, so every decrypted role/range string stays byte-identical to what already
 * resolves on the device — no decrypted role name ever exists outside the running process.
 *
 * Entry-bean facts grounding the field handling (`Le/a.java`; the jadx display aliases
 * `f9658a..f9664h` exist only because of name collisions — the real dex field names are the
 * single letters `a`..`h`, see the `renamed from` comments): all-public mutable bean with a
 * default constructor; `a`=type string ("0".."3"), `b`=role list, `c`=flattened zoom pairs
 * (EXACTLY two floats per role), `d`=per-role booleans (dead field on 510, copied
 * defensively), `e`=`"min:max"` range strings (EXACTLY one per role — `C4673d0#m()` splits
 * them, a length mismatch throws IOOBE mid-capture), `f`=optional handle factors, `g`=the
 * DEFAULT-effect flag (MUST be false on the clone or the camera boots into 红毯 as the
 * default effect instead of 超清实况), `h`=optional default lens (null → wide).
 */
internal object CameraMasterLiveRedCarpet {

    /** The 红毯运镜 (slow-motion movement) effect type id. */
    const val RED_CARPET_TYPE = "1"

    /** The ultra-pixel (超清实况) type id — the stock DEFAULT effect; never steal its flag. */
    const val ULTRA_PIXEL_TYPE = "0"

    /**
     * The effect types the synthetic 红毯 entry is cloned FROM, tried in order (自由线性 first
     * — the movement geometry user-verified clean on this device; 主角非线性 as fallback).
     */
    val CLONE_SOURCE_TYPES = listOf(TYPE_LINEAR, TYPE_NON_LINEAR)

    internal const val TYPE_NON_LINEAR = "2"
    internal const val TYPE_LINEAR = "3"

    /**
     * The rebuilt key order of the served table: `[超清, 红毯, 主角, 自由]` — `"1"`
     * inserted immediately after `"0"` (or at the front when no ultra-pixel entry exists),
     * mirroring the stock effect-panel hint order (`R4/b.java:166-169`). Unknown keys keep
     * their relative order after the known ones. Returns null when the order is already
     * canonical (nothing to rebuild).
     */
    fun orderedKeys(keys: Collection<String>): List<String>? {
        val rest = keys.filter { it != RED_CARPET_TYPE }
        val insertAt = rest.indexOf(ULTRA_PIXEL_TYPE) + 1 // 0 when no ultra-pixel entry
        val reordered = rest.subList(0, insertAt) + RED_CARPET_TYPE + rest.drop(insertAt)
        return reordered.takeIf { it != keys.toList() }
    }

    /**
     * Segment-consistency invariant of a MasterLive effect entry ([RED_CARPET_TYPE] clones
     * inherit it from the source entry; violating it makes `C4673d0#m()`/`p()` throw IOOBE at
     * capture time). Roles may be absent natively (the ultra-pixel entry has no movement
     * segments at all) — that counts as consistent.
     *
     * @param roles number of role/lens entries (`b.size`)
     * @param zoomPairs number of flattened zoom floats (`c.size`) — must be exactly roles×2
     * @param rangeStrings number of `"min:max"` strings (`e.size`) — must be exactly roles;
     *   null is tolerated (callers fall back to `{1,1}` ranges)
     */
    fun segmentsConsistent(roles: Int?, zoomPairs: Int?, rangeStrings: Int?): Boolean {
        if (roles == null || roles == 0) return zoomPairs == null || zoomPairs == 0
        if (zoomPairs != roles * 2) return false
        return rangeStrings == null || rangeStrings == roles
    }
}
