package com.takekazex.hypertweak.hook.rules.camera

/**
 * Per-effect-type video-size binding for 实况运镜 (MasterLive, mode 231)
 * (`Preferences.KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE`).
 *
 * WHY PER-TYPE (2026-08-28 user round): the previous probe pinned EVERY MasterLive capture to
 * the single 16:9 size 2304x1296. That made 主角非线性 ("2") and 自由线性 ("3") capture clean,
 * but broke the 4:3 高像素/超清实况 ("0") effect with the green-screen artifact again — a 16:9
 * stream geometry cannot serve a 4:3 effect. Each effect type therefore binds its OWN size:
 *
 *  - "0" 超清实况 (ultra-pixel): 4:3 [SIZE_4_3] — the geometry of the device's own CLEAN normal
 *    实况照片 4:3 live stream (on-device verified clean; RESEARCH_MYRON_09 §1).
 *  - "1"/"2"/"3" 红毯/主角/自由 (movement effects): 16:9 [SIZE_16_9] — the previously verified
 *    clean MasterLive movement size (user-verified captures on device).
 *
 * The effect type is the camera's own MasterLive component value (`pref_master_live_key`, read
 * through `com.android.camera.data.data.j#A(231)`, which additionally returns "" unless mode
 * 231 is the ACTIVE mode). An unreadable/empty type falls back to [SIZE_16_9] — the globally
 * verified-clean behaviour this feature shipped with — never to garbage.
 */
internal object CameraMasterLiveSizeBinding {

    /** 超清实况 (ultra-pixel, 4:3) — the K100 table's DEFAULT MasterLive effect (`g=true`). */
    const val TYPE_ULTRA_PIXEL = "0"

    /** 红毯运镜 (slow-motion movement) — present on the 17U table, merged in by HyperTweak. */
    const val TYPE_SLOW_MOTION = "1"

    /** 主角运镜 (non-linear movement). */
    const val TYPE_NON_LINEAR = "2"

    /** 自由运镜 (linear movement). */
    const val TYPE_LINEAR = "3"

    /**
     * 16:9 2304x1296 — the movement-effect binding: the device's own normal 实况照片 live-photo
     * stream size (on-device verified: `getVideoSize 1296x2304`, frames 2304x1296, clean) and
     * exactly the fallback used by the live-shot surface `c()` method (`Kj.C` on 540, `Kj.D` on
     * older builds), so stream, ImageReader, encoder canvas and compose
     * surfaces stay coherent everywhere.
     */
    val SIZE_16_9: Pair<Int, Int> = 2304 to 1296

    /**
     * 4:3 1728x1296 — the ultra-pixel (超清实况) binding: the same HEIGHT as the 16:9 movement
     * size, i.e. the geometry of the device's own clean 4:3 live stream source
     * (RESEARCH_MYRON_09: normal 实况照片 uses ratio `A()` and a 4:3 stream, source
     * 1728x1296, user-verified clean).
     */
    val SIZE_4_3: Pair<Int, Int> = 1728 to 1296

    /**
     * The size to bind for [effectType], or null when the incoming [w]×[h] already equals the
     * target (nothing to substitute — pass the original through untouched). Unknown/null types
     * bind [SIZE_16_9].
     */
    fun boundSize(effectType: String?, w: Int, h: Int): Pair<Int, Int>? {
        val target = targetSize(effectType)
        return if (w == target.first && h == target.second) null else target
    }

    /** The target size for [effectType]; unknown/null/blank binds [SIZE_16_9]. */
    fun targetSize(effectType: String?): Pair<Int, Int> =
        when (effectType) {
            TYPE_ULTRA_PIXEL -> SIZE_4_3
            TYPE_SLOW_MOTION, TYPE_NON_LINEAR, TYPE_LINEAR -> SIZE_16_9
            else -> SIZE_16_9
        }
}
