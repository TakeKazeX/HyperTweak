// SPDX-License-Identifier: Apache-2.0
package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

/** Separates the live background resource's material coverage and white edge. */
final class MiuiIndicatorMask {
    private MiuiIndicatorMask() {}

    static void normalizeAlpha(int[] pixels) {
        int peak = 0;
        for (int pixel : pixels) {
            peak = Math.max(peak, pixel >>> 24);
        }
        if (peak == 0) {
            throw new IllegalArgumentException("Indicator mask has no visible pixels");
        }
        for (int i = 0; i < pixels.length; i++) {
            int alpha = pixels[i] >>> 24;
            int coverage = (alpha * 255 + peak / 2) / peak;
            pixels[i] = (coverage << 24) | 0x00FFFFFF;
        }
    }

    static void isolateWhiteStroke(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            // The stock bitmap combines a black fill with a translucent white edge.
            // Its common RGB component is the white contribution; carry that into
            // alpha without normalizing it or retaining the black fill over the blur.
            int white = Math.min((pixel >>> 16) & 255,
                    Math.min((pixel >>> 8) & 255, pixel & 255));
            int alpha = ((pixel >>> 24) * white + 127) / 255;
            pixels[i] = (alpha << 24) | 0x00FFFFFF;
        }
    }
}
