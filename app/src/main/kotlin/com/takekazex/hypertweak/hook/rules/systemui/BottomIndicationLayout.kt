package com.takekazex.hypertweak.hook.rules.systemui

import android.os.SystemClock
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.Editable
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Owns only presentation, so future bottom-message providers can reuse the same wide row. */
internal class BottomIndicationLayout(view: TextView) {
    private val reference = WeakReference(view)
    private val originalWidth = view.layoutParams.width
    private val originalMaxLines = view.maxLines
    private val originalSingleLine = view.isSingleLine
    private val originalHorizontalScrolling = view.isHorizontallyScrollable
    private val originalEllipsize = view.ellipsize
    private val originalGravity = view.gravity
    private val areaReference = WeakReference((view.parent as? ViewGroup)?.takeIf {
        runCatching { it.resources.getResourceEntryName(it.id) }.getOrNull() == "keyguard_indication_area"
    })
    private val originalMargins = (areaReference.get()?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        intArrayOf(it.leftMargin, it.rightMargin, it.marginStart, it.marginEnd)
    }
    private var active = false
    private var configuredMode: Boolean? = null
    private var twoRows = true
    private var base = ""
    private var detail = ""
    private var emitted: String? = null
    private val scroll = BottomIndicationScroll()
    private var framePending = false
    private var renderPending = false

    private val renderRunnable = Runnable {
        renderPending = false
        boundary {
            reference.get()?.takeIf { active }?.let { render(it) }
        }
    }
    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = requestRender()
        override fun afterTextChanged(s: Editable?) = Unit
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        requestRender()
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = requestRender()
        override fun onViewDetachedFromWindow(v: View) {
            v.removeCallbacks(frame)
            v.removeCallbacks(renderRunnable)
            framePending = false
            renderPending = false
            scroll.reset()
        }
    }
    private val frame = object : Runnable {
        override fun run() {
            framePending = false
            boundary {
                val target = reference.get() ?: return@boundary
                if (!active || twoRows || !target.isAttachedToWindow || !target.isShown) {
                    scroll.pause()
                    return@boundary
                }
                val overflow = overflow(target)
                if (overflow <= 1) {
                    scroll.reset()
                    target.scrollTo(0, 0)
                    return@boundary
                }
                val offset = scroll.offset(
                    SystemClock.uptimeMillis(), overflow,
                    target.resources.displayMetrics.density * 24f
                )
                target.scrollTo(offset.roundToInt(), 0)
                scheduleFrame(target)
            }
        }
    }

    init {
        view.addTextChangedListener(textWatcher)
        view.addOnLayoutChangeListener(layoutListener)
        view.addOnAttachStateChangeListener(attachListener)
    }

    fun show(base: String, detail: String, twoRows: Boolean) {
        val view = reference.get() ?: return
        if (!active || this.twoRows != twoRows || this.base != base) scroll.reset()
        active = true
        this.base = base
        this.detail = detail
        this.twoRows = twoRows
        render(view)
    }

    fun restore() {
        val view = reference.get() ?: return
        if (!active) return
        active = false
        configuredMode = null
        view.removeCallbacks(frame)
        view.removeCallbacks(renderRunnable)
        framePending = false
        renderPending = false
        scroll.reset()
        if (view.text?.toString() == emitted) view.text = base
        restoreMargins()
        setWidth(view, originalWidth)
        view.isSingleLine = originalSingleLine
        view.setHorizontallyScrolling(originalHorizontalScrolling)
        view.maxLines = originalMaxLines
        view.ellipsize = originalEllipsize
        view.gravity = originalGravity
        view.scrollTo(0, 0)
        emitted = null
    }

    fun dispose() {
        restore()
        reference.get()?.let {
            it.removeTextChangedListener(textWatcher)
            it.removeOnLayoutChangeListener(layoutListener)
            it.removeOnAttachStateChangeListener(attachListener)
        }
    }

    private fun render(view: TextView) {
        // Never overwrite a different native indication during its animated text handoff.
        val current = view.text?.toString().orEmpty()
        if (emitted != null && current != emitted && current != base) return
        if (configuredMode != twoRows) {
            view.isSingleLine = !twoRows
            view.maxLines = if (twoRows) 2 else 1
            view.ellipsize = null // Own bidirectional overflow; disable the native looping marquee.
            view.gravity = Gravity.CENTER_HORIZONTAL
            configuredMode = twoRows
        }
        if (twoRows) {
            restoreMargins()
            setWidth(view, originalWidth)
        } else {
            widenArea(view)
            setWidth(view, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val text = SpannableStringBuilder(base).append(if (twoRows) "\n" else " | ")
        text.append(detail)
        text.setSpan(RelativeSizeSpan(DETAIL_SCALE), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!twoRows) {
            val available = (view.width - view.compoundPaddingLeft - view.compoundPaddingRight).coerceAtLeast(0)
            val overflowing = Layout.getDesiredWidth(text, view.paint) > available
            // Center a fitting row; overflow starts at its leading edge for full-range scrolling.
            val gravity = (if (overflowing) Gravity.START else Gravity.CENTER_HORIZONTAL) or Gravity.CENTER_VERTICAL
            if (view.gravity != gravity) view.gravity = gravity
            if (view.isHorizontallyScrollable != overflowing) view.setHorizontallyScrolling(overflowing)
        }
        if (!TextUtils.equals(view.text, text) || view.text !is Spanned) view.text = text
        emitted = text.toString()
        if (twoRows) view.scrollTo(0, 0) else scheduleFrame(view)
    }

    private fun widenArea(view: TextView) {
        val area = areaReference.get() ?: return
        val params = area.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val insets = view.rootWindowInsets?.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val density = view.resources.displayMetrics.density
        // Rounded corners can exceed the nominal 24dp gutter on strongly curved screens.
        val corners = view.rootWindowInsets
        val leftCorner = max(
            corners?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0,
            corners?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        ) / 2
        val rightCorner = max(
            corners?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0,
            corners?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
        ) / 2
        val left = max((24f * density).roundToInt(), max(insets?.left ?: 0, leftCorner))
        val right = max((24f * density).roundToInt(), max(insets?.right ?: 0, rightCorner))
        val rtl = area.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val start = if (rtl) right else left
        val end = if (rtl) left else right
        if (params.leftMargin != left || params.rightMargin != right || params.marginStart != start || params.marginEnd != end) {
            params.leftMargin = left
            params.rightMargin = right
            params.marginStart = start
            params.marginEnd = end
            area.layoutParams = params
        }
    }

    private fun restoreMargins() {
        val area = areaReference.get() ?: return
        val original = originalMargins ?: return
        val params = area.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.leftMargin != original[0] || params.rightMargin != original[1] ||
            params.marginStart != original[2] || params.marginEnd != original[3]) {
            params.leftMargin = original[0]
            params.rightMargin = original[1]
            params.marginStart = original[2]
            params.marginEnd = original[3]
            area.layoutParams = params
        }
    }

    private fun setWidth(view: TextView, width: Int) {
        val params = view.layoutParams
        if (params.width != width) {
            params.width = width
            view.layoutParams = params
        }
    }

    private fun overflow(view: TextView): Int {
        val layout = view.layout ?: return 0
        if (layout.lineCount == 0) return 0
        return ceil(layout.getLineWidth(0) - (view.width - view.compoundPaddingLeft - view.compoundPaddingRight)).toInt().coerceAtLeast(0)
    }

    private fun requestRender() {
        if (!active || renderPending) return
        reference.get()?.let {
            renderPending = true
            it.post(renderRunnable)
        }
    }

    private fun scheduleFrame(view: TextView) {
        if (!framePending && view.isAttachedToWindow) {
            framePending = true
            view.postOnAnimation(frame)
        }
    }

    private fun boundary(action: () -> Unit) = HookFailurePolicy.open("BottomIndicationLayout", "layout callback", Unit, action)

    private companion object {
        const val DETAIL_SCALE = 0.8f
    }
}
