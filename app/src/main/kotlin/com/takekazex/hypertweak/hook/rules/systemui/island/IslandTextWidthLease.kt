package com.takekazex.hypertweak.hook.rules.systemui.island

import android.text.TextUtils
import android.text.Layout
import android.widget.LinearLayout
import java.lang.reflect.Method
import kotlin.math.ceil
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap

/** Native TimerTextEffectSpan reports zero intrinsic width for a fixed-width TextView. */
internal class IslandTextWidthLease {
    private data class Original(val params: ViewGroup.LayoutParams, val width: Int,
        val maxWidth: Int, val ellipsize: TextUtils.TruncateAt?)
    private val targetText = HashMap<Class<*>, Method?>()
    private val originals = WeakHashMap<TextView, Original>()

    private fun visit(view: View, action: (TextView) -> Unit) {
        if (view is TextView) action(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), action)
        // Child forceLayout alone cannot invalidate a cached native area.measure call.
        view.forceLayout()
    }

    fun acquire(root: View) {
        visit(root) { text ->
            val params = text.layoutParams ?: return@visit
            originals.getOrPut(text) { Original(params, params.width, text.maxWidth, text.ellipsize) }
            if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                text.layoutParams = params
            }
            text.maxWidth = Int.MAX_VALUE
            text.ellipsize = null
            text.forceLayout()
        }
        IslandLayoutInvalidation.request(root)
    }

    /** Account for native module/fade containers whose old exact width clips wider timer text. */
    fun minimumWidth(view: View): Int {
        if (view.visibility == View.GONE) return 0
        if (view is TextView) {
            val type = view.javaClass
            val getter = if (targetText.containsKey(type)) targetText[type] else {
                type.methods.singleOrNull { it.name == "getTargetDisplayText" && it.parameterCount == 0 &&
                    CharSequence::class.java.isAssignableFrom(it.returnType) }.also { targetText[type] = it }
            }
            val target = getter?.invoke(view) as? CharSequence ?: view.text
            val desired = maxOf(Layout.getDesiredWidth(view.text, view.paint),
                Layout.getDesiredWidth(target, view.paint), view.paint.measureText(target.toString()))
            return ceil(desired.toDouble()).toInt() + view.compoundPaddingLeft + view.compoundPaddingRight
        }
        if (view !is ViewGroup) return view.measuredWidth
        val children = (0 until view.childCount).map(view::getChildAt).filter { it.visibility != View.GONE }
        if (children.isEmpty()) return view.measuredWidth
        val widths = children.map { child ->
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            minimumWidth(child) + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0)
        }
        val childWidth = if (view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL) widths.sum()
            else widths.maxOrNull() ?: 0
        return maxOf(view.minimumWidth, childWidth + view.paddingLeft + view.paddingRight)
    }

    private fun release(text: TextView) {
        val original = originals.remove(text) ?: return
        val params = text.layoutParams
        if (params === original.params && params.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.width = original.width
            text.layoutParams = params
        }
        if (text.maxWidth == Int.MAX_VALUE) text.maxWidth = original.maxWidth
        if (text.ellipsize == null) text.ellipsize = original.ellipsize
        text.forceLayout()
    }

    fun release(root: View) {
        visit(root, ::release)
        IslandLayoutInvalidation.request(root)
    }
    fun releaseAll() = originals.keys.toList().forEach { text ->
        release(text)
        IslandLayoutInvalidation.request(text)
    }
}
