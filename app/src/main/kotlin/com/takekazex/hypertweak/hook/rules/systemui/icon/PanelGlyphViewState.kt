package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/** Reusable per-endpoint snapshot. Mutable host text/rect/matrix values are copied as values. */
internal class PanelGlyphViewState {
    private val ints = IntArray(21)
    private val floats = FloatArray(15)
    private val refs = arrayOfNulls<Any>(9)
    private var text: String? = null

    fun read(view: View, crop: RectF?, previous: PanelGlyphViewState) {
        val image = view as? ImageView
        val drawable = image?.drawable
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        val label = view as? TextView
        ints[0] = view.width; ints[1] = view.height
        ints[2] = view.paddingLeft; ints[3] = view.paddingTop
        ints[4] = view.paddingRight; ints[5] = view.paddingBottom
        ints[6] = drawable?.level ?: -1; ints[7] = drawable?.alpha ?: -1
        ints[8] = bitmap?.generationId ?: -1; ints[9] = image?.imageAlpha ?: -1
        ints[10] = view.drawableState.contentHashCode()
        ints[11] = label?.currentTextColor ?: 0; ints[12] = if (label?.includeFontPadding == true) 1 else 0
        ints[13] = label?.gravity ?: 0; ints[14] = label?.baseline ?: 0
        drawable?.bounds.let { rect ->
            ints[15] = rect?.left ?: 0; ints[16] = rect?.top ?: 0
            ints[17] = rect?.right ?: 0; ints[18] = rect?.bottom ?: 0
        }
        ints[19] = if (image == null) 0 else 1; ints[20] = if (crop == null) 0 else 1
        floats[0] = label?.textSize ?: 0f; floats[1] = label?.letterSpacing ?: 0f
        if (image != null) image.imageMatrix.getValues(floatsMatrix) else floatsMatrix.fill(0f)
        floatsMatrix.copyInto(floats, 2)
        floats[11] = crop?.left ?: 0f; floats[12] = crop?.top ?: 0f
        floats[13] = crop?.right ?: 0f; floats[14] = crop?.bottom ?: 0f
        refs[0] = view; refs[1] = drawable; refs[2] = bitmap
        refs[3] = image?.colorFilter; refs[4] = drawable?.colorFilter
        refs[5] = image?.imageTintList; refs[6] = label?.typeface
        refs[7] = image?.scaleType; refs[8] = image?.imageTintMode
        // TextView.getText may return a mutable Spannable; never retain it as the comparison baseline.
        val current = label?.text
        text = if (TextUtils.equals(current, previous.text)) previous.text else current?.toString()
    }
    private val floatsMatrix = FloatArray(9)
    fun same(other: PanelGlyphViewState): Boolean = ints.contentEquals(other.ints) &&
        floats.contentEquals(other.floats) && refs.contentEquals(other.refs) && text == other.text
    fun clear() { refs.fill(null); text = null }
}
