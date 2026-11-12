package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Picture
import android.view.View

/** An endpoint's own opacity when it is not the view's `alpha`, e.g. a glyph drawn inside text. */
internal interface EndpointAlpha {
    var value: Float
}

/**
 * A glyph drawn inside a larger [view]: [picture] holds the glyph alone, in the view's own
 * coordinates and size, and [value] is the glyph's own opacity.
 */
internal interface GlyphEndpoint : EndpointAlpha {
    /** Null after a weak label endpoint has been collected. */
    val view: View?
    fun picture(): Picture?
}
