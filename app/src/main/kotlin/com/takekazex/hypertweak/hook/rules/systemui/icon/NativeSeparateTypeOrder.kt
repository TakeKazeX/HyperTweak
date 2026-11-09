package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy.TYPE_ORDER_SWAP
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy.TYPE_ORDER_RIGHT
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.abs

/** Positions the host labels while leaving their original binder and measured widths intact. */
internal class NativeSeparateTypeOrder {
    private val labels = WeakHashMap<View, WeakReference<TextView>>()
    private val originalIndices = WeakHashMap<TextView, Int>()
    private val groups = IdentityHashMap<ViewGroup, Group>()

    fun bind(root: ViewGroup, signal: View, text: TextView, mode: Int) {
        val row = signal.parent as? LinearLayout ?: return
        if (text.parent !== row || row.parent !== root || row.orientation != LinearLayout.HORIZONTAL) return
        val index = row.indexOfChild(text)
        if (index < 0) return
        val original = originalIndices.getOrPut(text) { index }
        val signalIndex = row.indexOfChild(signal)
        if (mode == TYPE_ORDER_SWAP) {
            if (index == original) move(row, text, signalIndex)
            return
        }
        labels[root] = WeakReference(text)
        // The measured box is reserved at the end of each row, then redistributed by the group.
        move(row, text, row.childCount - 1)
    }

    fun onContainerLayout(container: ViewGroup, mode: Int, ensureUnclipped: (ViewGroup) -> Unit) {
        if (labels.isEmpty()) return
        val rows = buildList {
            for (index in 0 until container.childCount) {
                val root = container.getChildAt(index)
                val text = labels[root]?.get() ?: continue
                val row = text.parent as? ViewGroup ?: continue
                add(Row(root, row, text))
            }
        }
        if (rows.isEmpty() || !container.isAttachedToWindow) {
            groups.remove(container)?.close()
            return
        }
        val group = groups.getOrPut(container) { Group(container, mode, ensureUnclipped) }
        group.update(rows)
    }

    fun retire() {
        groups.values.toList().forEach(Group::close)
        groups.clear()
        originalIndices.forEach { (text, index) ->
            val row = text.parent as? ViewGroup ?: return@forEach
            runCatching { move(row, text, index.coerceAtMost(row.childCount - 1)) }
                .onFailure { DebugLog.w(TAG, "separate type order restore failed", it) }
        }
        originalIndices.clear()
        labels.clear()
    }

    private fun move(row: ViewGroup, text: TextView, index: Int) {
        val current = row.indexOfChild(text)
        if (current < 0 || current == index) return
        val params = text.layoutParams
        row.removeViewAt(current)
        row.addView(text, index, params)
    }

    private data class Row(val root: View, val content: ViewGroup, val text: TextView) {
        var edge = 0f
        var typeWidth = 0
    }

    private inner class Group(
        private val container: ViewGroup,
        private val mode: Int,
        private val ensureUnclipped: (ViewGroup) -> Unit
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val observer = container.viewTreeObserver
        private var rows = emptyList<Row>()
        private val visible = ArrayList<Row>(2)
        private val shifts = IdentityHashMap<View, Shift>()

        init {
            observer.addOnPreDrawListener(this)
            container.addOnAttachStateChangeListener(this)
        }

        fun update(next: List<Row>) {
            if (rows == next) return
            clearShifts()
            rows = next
        }

        override fun onPreDraw(): Boolean {
            runCatching { applyOrder() }.onFailure {
                clearShifts()
                DebugLog.w(TAG, "separate type group layout failed", it)
            }
            return true
        }

        private fun applyOrder() {
            val rtl = container.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val direction = if (rtl) -1 else 1
            visible.clear()
            var totalTypeWidth = 0
            for (row in rows) {
                if (row.root.parent !== container || !signalEndpointVisible(row.content, container)) {
                    restore(row.content)
                    restore(row.text)
                    continue
                }
                row.edge = (row.root.x + if (rtl) row.content.left else row.content.right) * direction
                row.typeWidth = if (row.text.visibility == View.VISIBLE) row.text.width else 0
                totalTypeWidth += row.typeWidth
                visible += row
            }
            if (totalTypeWidth == 0 ||
                visible.any { abs(it.root.y - visible[0].root.y) > 1f }
            ) {
                clearShifts()
                return
            }
            visible.sortBy { it.edge }
            val groupEnd = visible.last().edge
            val groupStart = visible.first().let { it.edge - it.content.width }
            val atEnd = (mode == TYPE_ORDER_RIGHT) != rtl
            var precedingTypeWidth = 0
            for (row in visible) {
                val remainingTypeWidth = totalTypeWidth - precedingTypeWidth - row.typeWidth
                val contentShift = (if (atEnd) -precedingTypeWidth
                    else totalTypeWidth - precedingTypeWidth) * direction
                val textShift = when {
                    row.typeWidth == 0 -> 0f
                    atEnd -> (groupEnd - row.edge - remainingTypeWidth + precedingTypeWidth) * direction
                    else -> (groupStart + precedingTypeWidth - row.edge + row.typeWidth) * direction -
                        contentShift
                }
                if (container.clipChildren && (contentShift != 0 || textShift != 0f)) {
                    ensureUnclipped(container)
                }
                translate(row.content, contentShift.toFloat())
                translate(row.text, textShift)
                precedingTypeWidth += row.typeWidth
            }
        }

        private fun translate(view: View, offset: Float) {
            if (offset == 0f) {
                restore(view)
                return
            }
            val current = view.translationX
            val previous = shifts[view]
            val baseline = if (previous != null && current == previous.applied) previous.baseline else current
            val target = baseline + offset
            if (current != target) view.translationX = target
            if (previous?.applied != target || previous.baseline != baseline) {
                shifts[view] = Shift(baseline, target)
            }
        }

        private fun restore(view: View) {
            val shift = shifts.remove(view) ?: return
            if (view.translationX == shift.applied) view.translationX = shift.baseline
        }

        private fun clearShifts() {
            shifts.forEach { (view, shift) ->
                if (view.translationX == shift.applied) view.translationX = shift.baseline
            }
            shifts.clear()
        }

        fun close() {
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            container.removeOnAttachStateChangeListener(this)
            clearShifts()
        }

        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            groups.remove(container)
            close()
        }
    }

    private data class Shift(val baseline: Float, val applied: Float)

    private companion object {
        const val TAG = "IconTuner"
    }
}
