package com.takekazex.hypertweak.hook.rules.aicr

import android.content.res.Configuration
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Adapts verified native data to the existing title; never changes its metrics, paint or hierarchy. */
internal class AicrBubbleTitleRenderer private constructor(
    private val owner: Class<*>, private val cueGetter: Method,
    private val displayGetter: Method, private val categoryGetter: Method,
    private val target: Field, private val card: Field, private val title: Field,
    private val text: Field, private val highlights: Field, private val start: Field, private val end: Field
) {
    private val styled = WeakHashMap<TextView, Boolean>() // All callers run on the main looper.

    fun bind(root: View, cue: Any, browser: String, label: String): Boolean {
        if (!owner.isInstance(root) || categoryGetter.invoke(cue) != "copy_jump") return false
        val display = displayGetter.invoke(cue) ?: return false
        if (target.get(display) != browser) return false
        val brief = card.get(display) ?: return false
        val value = title.get(brief) ?: return false
        val content = text.get(value) as? String ?: return false
        val ranges = (highlights.get(value) as? List<*>)?.mapNotNull { range ->
            if (range == null) return@mapNotNull null
            val first = start.getInt(range)
            val last = end.getInt(range)
            if (first < 0 || last <= first || last > content.length || content.substring(first, last) != label) null
            else first to last
        }.orEmpty().distinct()
        if (label.isBlank() || ranges.isEmpty()) return false
        val id = root.resources.getIdentifier("bubble_title", "id", root.context.packageName)
        val view = root.findViewById<View>(id) as? TextView ?: return false
        // Aggregated cards use a different title. Do not color a same-length unrelated string.
        if (view.text.toString() != content) return false
        val result = withoutOurSpans(view.text)
        ranges.forEach { (first, last) ->
            result.setSpan(BrowserGradient(view, content, first, last), first, last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = result
        styled[view] = true
        return true
    }

    fun recover(root: View, browser: String, label: String): Int {
        var applied = 0
        var visited = 0
        fun visit(view: View) {
            if (++visited > 512) return
            if (owner.isInstance(view)) {
                val cue = cueGetter.invoke(view)
                if (cue != null && bind(view, cue, browser, label)) applied++
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return applied
    }

    fun clear(): Int {
        var cleared = 0
        styled.keys.toList().forEach { view ->
            val current = view.text as? Spanned ?: return@forEach
            if (current.getSpans(0, current.length, BrowserGradient::class.java).isNotEmpty()) {
                view.text = withoutOurSpans(current)
                cleared++
            }
        }
        styled.clear()
        return cleared
    }

    private fun withoutOurSpans(value: CharSequence) = SpannableString(value).apply {
        getSpans(0, length, BrowserGradient::class.java).forEach(::removeSpan)
    }

    /** Draw-only style: native Layout keeps bidi, ellipsis, font scaling and all glyph advances. */
    private class BrowserGradient(view: TextView, private val content: String, private val first: Int, private val last: Int) :
        CharacterStyle(), UpdateAppearance {
        private val view = WeakReference(view)
        private var shader: Shader? = null
        private var left = Float.NaN
        private var right = Float.NaN
        private var dark = false
        private var locating = false
        override fun updateDrawState(paint: TextPaint) {
            // A stale platform Layout must fall back to native text, never crash the host draw.
            try { updateShader(paint) } catch (_: RuntimeException) { /* Keep the incoming paint. */ }
        }
        private fun updateShader(paint: TextPaint) {
            // Layout.getPrimaryHorizontal re-enters CharacterStyle through TextLine.measureRun.
            // Nested measurement needs only the native metrics, never another position lookup.
            if (locating) return
            val host = view.get() ?: return
            val layout = host.layout ?: return
            // Layout exposes an Ellipsizer string when truncated; TextView retains the source.
            if (host.text.toString() != content || last > layout.text.length) return
            locating = true
            val positions = try {
                layout.getPrimaryHorizontal(first) to layout.getPrimaryHorizontal(last)
            } finally { locating = false }
            val (a, b) = positions
            val x0 = minOf(a, b)
            val x1 = maxOf(a, b)
            if (x1 <= x0) return
            val night = host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            if (shader == null || left != x0 || right != x1 || dark != night) {
                left = x0; right = x1; dark = night
                shader = LinearGradient(x0, 0f, x1, 0f,
                    if (night) 0xff74a9ff.toInt() else 0xff006bff.toInt(),
                    if (night) 0xffc69aff.toInt() else 0xff9b36ff.toInt(), Shader.TileMode.CLAMP)
            }
            paint.shader = shader
        }
    }

    companion object {
        fun resolve(bind: Method): AicrBubbleTitleRenderer? = runCatching {
            check(bind.parameterCount == 1 && bind.returnType == Void.TYPE && ViewGroup::class.java.isAssignableFrom(bind.declaringClass))
            val cue = bind.parameterTypes.single()
            fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
            val displayGetter = cue.getMethod("getDisplay")
            val display = displayGetter.returnType
            val target = field(display, "targetPackage").also { check(it.type == String::class.java) }
            val card = field(display, "briefCard")
            val title = field(card.type, "title")
            val text = field(title.type, "text").also { check(it.type == String::class.java) }
            val highlights = field(title.type, "highlights").also { check(List::class.java.isAssignableFrom(it.type)) }
            val highlight = cue.declaredClasses.single { it.simpleName == "Highlight" }
            val start = field(highlight, "start").also { check(it.type == Int::class.javaPrimitiveType) }
            val end = field(highlight, "end").also { check(it.type == Int::class.javaPrimitiveType) }
            AicrBubbleTitleRenderer(bind.declaringClass, bind.declaringClass.getMethod("getCueData"),
                displayGetter, cue.getMethod("getCategory"), target, card, title, text, highlights, start, end)
        }.getOrNull()
    }
}
