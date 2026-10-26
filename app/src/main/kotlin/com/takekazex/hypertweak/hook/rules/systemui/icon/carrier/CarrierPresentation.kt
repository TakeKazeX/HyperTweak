@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.TwoRowIconLayout
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import java.lang.reflect.Field
import kotlin.math.roundToInt

private val hideCarrierOne by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_ONE, false) }
private val hideCarrierTwo by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_TWO, false) }

/** Owns carrier row construction, styling, sizing and exact native presentation restoration. */
internal class CarrierPresentation(private val host: Host, private val rowRenderer: CarrierRowRenderer) {
    interface Host {
        val carrierTextField: Field?
        val badgeTexts: List<String>
        val enabled: Boolean
        val rowOrder: List<Int>
        fun rowGeometry(layout: ViewGroup): TwoRowIconLayout.Geometry?
        fun releaseMask(block: CarrierBlock)
        fun releaseHandover()
    }
    fun buildRow(row: LinearLayout, slot: Int): CarrierRowParts? {
        val text = host.carrierTextField?.get(row) as? TextView ?: return null
        val context = row.context
        val networkSlot = FrameLayout(context).apply {
            visibility = View.GONE
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val type = glyphView(context)
        val wifi = glyphView(context)
        networkSlot.addView(type, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        networkSlot.addView(wifi, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        return CarrierRowParts(slot, row, text, glyphView(context), TextView(context).apply {
            this.text = host.badgeTexts[slot]
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, networkSlot, wifi, type).also { parts ->
            parts.showHd = readField(row, "showHdIcon") as? Boolean
            parts.hd = (readField(row, "hdText") as? View)?.let(::CarrierViewState)
            parts.plus = (readField(row, "plusText") as? View)?.let(::CarrierViewState)
        }
    }

    private fun glyphView(context: Context): ImageView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun configureBlock(block: CarrierBlock, restyle: Boolean = false) {
        val compact = host.enabled && ControlCenterHeaderHooker.supportsCompactLayout(block.layout)
        if (block.compact == compact && !restyle) return
        block.compact = compact
        block.geometry = null
        block.presentationValid = false
        if (restyle) {
            rowRenderer.invalidate(block.layout.resources)
            block.rows.forEach { rowRenderer.invalidate(it.row.resources) }
        }
        block.rows.forEach { it.rendered = null }
        if (!compact) {
            restoreBlockStyle(block)
            host.releaseMask(block)
            host.releaseHandover()
            return
        }
        ControlCenterHeaderHooker.ensureCompactCarrierVisible(block.layout)
        block.layout.orientation = LinearLayout.VERTICAL
        block.layout.gravity = Gravity.START or Gravity.TOP
        collapse(block.separator)
        collapse(block.keyguardSeparator)
        // Cancel any horizontal budget queued before we acquired this layout.
        val handler = readField(block.layout, "handler") as? Handler
        val tasks = readField(block.layout, "pendingTasks") as? MutableMap<*, *>
        tasks?.values?.filterIsInstance<Runnable>()?.forEach { handler?.removeCallbacks(it) }
        tasks?.clear()
        (readField(block.layout, "lastMaxWidth") as? IntArray)?.fill(0)
        block.rows.forEach { parts ->
            val density = parts.row.resources.displayMetrics.density
            val gap = (4 * density).roundToInt()
            val icon = rowRenderer.iconHeightPx(parts.row.context)
            parts.row.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            parts.row.isBaselineAligned = false
            parts.row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (parts.slot == 0) 0 else (3 * density).roundToInt() }
            parts.carrierText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            parts.carrierText.includeFontPadding = false
            parts.carrierText.ellipsize = TextUtils.TruncateAt.END
            parts.carrierText.visibility = if (parts.carrierText.text.isNullOrBlank()) View.GONE else View.VISIBLE
            parts.signal.layoutParams = LinearLayout.LayoutParams(icon, icon).apply { marginEnd = gap }
            parts.networkWidth = icon
            parts.networkSlot.layoutParams = LinearLayout.LayoutParams(icon,
                CarrierBlockPolicy.typeHeight(icon, density, parts.row.resources.configuration.fontScale)).apply {
                marginStart = gap
            }
            parts.badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            parts.badge.setPaddingRelative((2 * density).roundToInt(), 0, (2 * density).roundToInt(), 0)
            parts.badge.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                (13 * density * parts.row.resources.configuration.fontScale).roundToInt()).apply { marginEnd = gap }
            field(parts.row.javaClass, "showHdIcon")?.setBoolean(parts.row, false)
            (readField(parts.row, "hdText") as? View)?.visibility = View.GONE
            (readField(parts.row, "plusText") as? View)?.visibility = View.GONE
        }
        ControlCenterHeaderHooker.updateCarrierLayout(block.layout)
    }

    private fun collapse(view: View?) {
        view ?: return
        view.visibility = View.GONE
        (view.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0; params.height = 0
            params.setMargins(0, 0, 0, 0); params.marginStart = 0; params.marginEnd = 0
            view.layoutParams = params
        }
    }

    fun restoreBlockStyle(block: CarrierBlock) {
        block.originalChildren.filter { it.parent === block.layout }.forEach(block.layout::bringChildToFront)
        block.layout.orientation = block.orientation
        block.layout.minimumHeight = block.minimumHeight
        block.layout.gravity = block.gravity
        block.separator?.let { block.separatorState?.restore(it) }
        block.keyguardSeparator?.let { block.keyguardSeparatorState?.restore(it) }
        block.rows.forEach { parts ->
            parts.rowState.restore(parts.row)
            parts.textState.restore(parts.carrierText)
            parts.carrierText.text = parts.nativeCarrierText
            if ((if (parts.slot == 0) hideCarrierOne else hideCarrierTwo)) parts.carrierText.visibility = View.GONE
            parts.row.gravity = parts.gravity
            parts.row.isBaselineAligned = parts.baselineAligned
            parts.row.contentDescription = parts.contentDescription
            parts.carrierText.setTextSize(TypedValue.COMPLEX_UNIT_PX, parts.textSize)
            parts.carrierText.maxWidth = parts.maxWidth
            parts.carrierText.ellipsize = parts.ellipsize
            parts.carrierText.includeFontPadding = parts.fontPadding
            parts.showHd?.let { field(parts.row.javaClass, "showHdIcon")?.setBoolean(parts.row, it) }
            (readField(parts.row, "hdText") as? View)?.let { parts.hd?.restore(it) }
            (readField(parts.row, "plusText") as? View)?.let { parts.plus?.restore(it) }
            parts.networkFade.clear()
            listOf(parts.signal, parts.badge, parts.airplaneText, parts.networkSlot, parts.wifi, parts.type)
                .forEach { it.visibility = View.GONE }
        }
        (readField(block.layout, "lastMaxWidth") as? IntArray)?.fill(0)
    }

    fun measureRows(block: CarrierBlock, width: Int) {
        block.rows.forEach { parts ->
            val fixed = listOf(parts.signal, parts.networkSlot)
                .filter { it.visibility != View.GONE }.sumOf { view ->
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    val lp = view.layoutParams as LinearLayout.LayoutParams
                    (if (lp.width >= 0) lp.width else view.measuredWidth) + lp.marginStart + lp.marginEnd
                }
            val textParams = parts.carrierText.layoutParams as? ViewGroup.MarginLayoutParams
            val padding = block.layout.paddingStart + block.layout.paddingEnd + parts.row.paddingStart +
                parts.row.paddingEnd + (textParams?.marginStart ?: 0) + (textParams?.marginEnd ?: 0)
            val available = CarrierBlockPolicy.textWidth(width, fixed + padding)
            val gap = (4 * parts.row.resources.displayMetrics.density).roundToInt()
            val badgeWidth = if (parts.badge.isVisible) CarrierBlockPolicy.badgeWidth(
                available,
                kotlin.math.ceil(parts.badge.paint.measureText(parts.badge.text.toString())).toInt() +
                    parts.badge.paddingStart + parts.badge.paddingEnd,
                gap,
                kotlin.math.ceil(parts.carrierText.paint.measureText("MMMM")).toInt()
            ) else 0
            val badgeParams = parts.badge.layoutParams as LinearLayout.LayoutParams
            val badgeGap = if (badgeWidth > 0) gap else 0
            if (badgeParams.width != badgeWidth || badgeParams.marginEnd != badgeGap) {
                badgeParams.width = badgeWidth
                badgeParams.marginEnd = badgeGap
                parts.badge.layoutParams = badgeParams
            }
            val max = CarrierBlockPolicy.textWidth(available, badgeWidth + badgeGap)
            if (parts.carrierText.maxWidth != max) parts.carrierText.maxWidth = max
            if (parts.airplaneText.maxWidth != max) parts.airplaneText.maxWidth = max
        }
    }

    fun updateRowGeometry(block: CarrierBlock) {
        if (!block.compact) return
        val geometry = host.rowGeometry(block.layout) ?: return
        if (!capturePresentationInputs(block) && block.presentationValid) return
        block.presentationValid = false
        val gravity = Gravity.START or Gravity.TOP
        if (block.layout.gravity != gravity) block.layout.gravity = gravity
        val ordered = block.rows.sortedBy { host.rowOrder.indexOf(it.slot) }
        val indices = ordered.map { block.layout.indexOfChild(it.row) }
        if (indices != indices.sorted()) ordered.forEach { block.layout.bringChildToFront(it.row) }
        val gap = (3 * block.layout.resources.displayMetrics.density).roundToInt()
        val margins = TwoRowIconLayout.visibleRowMargins(ordered.filter { it.row.isVisible }.map { it.slot }, gap)
        block.rows.forEach { parts ->
            if (parts.row.isVisible) {
                val params = parts.row.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
                val margin = margins.getValue(parts.slot)
                if (params.topMargin != margin) { params.topMargin = margin; parts.row.layoutParams = params }
            }
        }
        val height = maxOf(block.minimumHeight, geometry.minimumHeight)
        if (block.layout.minimumHeight != height) block.layout.minimumHeight = height
        val visible = ordered.filter { it.row.isVisible && it.row.isLaidOut && it.row.height > 0 }
        val tops = TwoRowIconLayout.carrierRowTops(visible.map { it.slot to it.row.height }, geometry)
        visible.forEach { parts ->
            val row = parts.row
            val top = tops.getValue(parts.slot)
            // Align actual carrier bounds as well as header constraints. A hidden SIM must not
            // leave the surviving row centered inside the reserved two-row canvas.
            if (row.top != top) row.layout(row.left, top, row.right, top + row.height)
        }
        // Record our writes too, so the next unchanged pre-draw is a cheap comparison.
        capturePresentationInputs(block)
        block.presentationValid = true
    }

    /** Include native relayouts even when measurement itself did not change. No per-frame lists. */
    private fun capturePresentationInputs(block: CarrierBlock): Boolean {
        val inputs = block.presentationInputs
        inputs.begin()
        val geometry = checkNotNull(block.geometry)
        inputs.add(geometry.firstCenter.toBits())
        inputs.add(geometry.secondCenter.toBits())
        inputs.add(geometry.minimumHeight)
        inputs.add(block.layout.minimumHeight)
        inputs.add(block.layout.gravity)
        for (index in block.rows.indices) {
            val parts = block.rows[index]
            val row = parts.row
            inputs.add(row.visibility)
            inputs.add(row.left); inputs.add(row.top); inputs.add(row.right); inputs.add(row.bottom)
            inputs.add(if (row.isLaidOut) 1 else 0)
            inputs.add(block.layout.indexOfChild(row))
            inputs.add(host.rowOrder.indexOf(parts.slot))
            inputs.add((row.layoutParams as? LinearLayout.LayoutParams)?.topMargin ?: Int.MIN_VALUE)
        }
        return inputs.changed
    }

    private fun readField(target: Any, name: String): Any? = runCatching { field(target.javaClass, name)?.get(target) }.getOrNull()
    private fun field(type: Class<*>, name: String): Field? = HostAccess.field(type, name)

}
