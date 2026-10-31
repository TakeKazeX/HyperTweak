package com.takekazex.hypertweak.presentation.icon.duo

import android.graphics.Path
import android.graphics.Matrix
import androidx.core.graphics.PathParser

/** Cupertino Icons bolt_fill (U+F598), MIT; see META-INF/CUPERTINO_ICONS_LICENSE.txt.
 * Source blob d580ce749ea55b12b92f5db7747290419c975070, normalized to 7.2 units high.
 * Widen the silhouette slightly so its waist survives rasterization beside the Wi-Fi arcs.
 */
internal object DuoBoltArtwork {
    private const val OPTICAL_WIDTH_SCALE = 1.15f
    const val WIDTH = 4.530998f * OPTICAL_WIDTH_SCALE
    const val HEIGHT = 7.2f
    fun path(): Path = requireNotNull(PathParser.createPathFromPathData(PATH_DATA)).apply {
        transform(Matrix().apply { setScale(OPTICAL_WIDTH_SCALE, 1f) })
    }
    private const val PATH_DATA = "M-2.0404 0.6093Q-2.2655 0.6093 -2.2655 0.3993Q-2.2655 0.2943 -2.1755 0.1892L0.7352 -3.4566Q0.9002 -3.6666 1.0877 -3.5691Q1.2753 -3.4716 1.1853 -3.2315L0.21 -0.6209H2.0404Q2.2655 -0.6209 2.2655 -0.4109Q2.2655 -0.2909 2.1755 -0.2008L-0.7352 3.46Q-0.9002 3.67 -1.0802 3.565Q-1.2603 3.46 -1.1703 3.2199L-0.225 0.6093Z"
}
