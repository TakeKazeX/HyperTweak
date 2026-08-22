package com.takekazex.hypertweak.hook.rules.camera

/**
 * Decision logic for the MasterLive (实况运镜) circular-encoder size pin
 * (`Preferences.KEY_CAMERA_MASTERLIVE_CODEC_PIN`, mechanism [M0] of
 * RESEARCH_MYRON_09_MASTERLIVE_ARTIFACT.md).
 *
 * The LiveShot circular encoder's `updateCodecSize` (`p859ym.d#E(Size)`) is invoked with the
 * per-shot preview-snapshot size on every capture; a codec-format rewrite follows whenever it
 * differs from the current format, while the GL render canvas stays at the construction size —
 * the codec input surface then ends up partially unwritten (zero-fill = pure green) with edge
 * clamp/wrap (repeated lines). The pin restores the invariant the renderer was built for: the
 * codec format is the initial format size (`A`×`B` fields of the encoder), so a divergent
 * rewrite can never happen.
 */
internal object CameraCodecSizePin {

    /**
     * The size to substitute into `updateCodecSize` for an incoming [w]×[h] against the
     * encoder's initial format size [initialW]×[initialH], or null when the incoming size
     * already matches (nothing to pin — the original argument passes through untouched).
     */
    fun pinnedSize(w: Int, h: Int, initialW: Int, initialH: Int): Pair<Int, Int>? =
        if (w == initialW && h == initialH) null else initialW to initialH
}