package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphFrame
import com.takekazex.hypertweak.hook.rules.systemui.icon.PanelGlyphTravel
import com.takekazex.hypertweak.hook.rules.systemui.icon.SignalTargetOwnership
import kotlin.math.abs
import kotlin.math.roundToInt

/** Moves all remaining slots as one coherent row, leaving network/battery to their existing owners. */
internal class DuoStatusIconsMotion {
    var excludedSlots: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            value.forEach { slot -> entries.remove(slot)?.copies?.forEach(::release) }
            layer.invalidateSelf()
        }
    private class Entry {
        val source = PanelGlyphFrame()
        val target = PanelGlyphFrame()
        val travel = PanelGlyphTravel()
        val from = RectF()
        val to = RectF()
        var hasSource = false
        var hasTarget = false
        var opacity = 1f
        var copies: List<View> = emptyList()
    }
    private class Alpha(val view: View, var saved: Float, var applied: Float)
    private var host: ViewGroup? = null
    private var ownership: SignalTargetOwnership<View>? = null
    private val masks = java.util.IdentityHashMap<View, Alpha>()
    private val entries = LinkedHashMap<String, Entry>()
    private var progress = 0f
    private var drawSignature = 0L
    private val usedSlots = HashSet<String>()
    private val usedViews = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
    private var failed = false
    private var reportedFailure = false
    private val matrix = Matrix()
    private val inverseRoot = Matrix()
    private val layer = object : Drawable() {
        override fun draw(canvas: Canvas) {
            if (failed) return
            entries.values.forEach { entry ->
                val save = canvas.saveLayerAlpha(null, (entry.opacity * 255).roundToInt().coerceIn(0, 255))
                try {
                    when {
                        entry.hasSource && entry.hasTarget -> entry.travel.draw(canvas, entry.source, entry.target, entry.from, entry.to, progress)
                        entry.hasSource -> entry.source.draw(canvas, entry.from, ((1f - progress) * 255).roundToInt())
                        entry.hasTarget -> entry.target.draw(canvas, entry.to, (progress * 255).roundToInt())
                    }
                    reportedFailure = false
                } catch (error: Throwable) {
                    failed = true
                    restoreMasks()
                    if (!reportedFailure) com.takekazex.hypertweak.util.logging.DebugLog.w("DuoSignal", "status icon handoff failed", error)
                    reportedFailure = true
                } finally { canvas.restoreToCount(save) }
            }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun update(root: ViewGroup, home: View, proxy: View, expanded: View, fraction: Float,
               owners: SignalTargetOwnership<View>) {
        if (failed || !root.isAttachedToWindow || !home.isAttachedToWindow || !proxy.isAttachedToWindow ||
            !expanded.isAttachedToWindow || fraction >= 1f || !fraction.isFinite()) { clear(); return }
        if (host !== root || ownership !== owners) {
            clear(); host = root; ownership = owners; root.overlay.add(layer)
        }
        progress = fraction.coerceIn(0f, 1f)
        matrix.reset(); root.transformMatrixToGlobal(matrix)
        if (!matrix.invert(inverseRoot)) { clear(); return }
        val sourceSlots = slots(home)
        val fakeSlots = slots(proxy)
        val targetSlots = slots(expanded)
        usedSlots.clear()
        usedViews.clear()
        (fakeSlots.keys + targetSlots.keys).forEach { slot ->
            if (slot in excludedSlots) return@forEach
            val source = sourceSlots[slot]
            val fake = fakeSlots[slot]
            val target = targetSlots[slot]
            val views = listOfNotNull(fake, target)
            // Reserve both copies atomically; one collapsed proxy owns a target at a time.
            if (views.any { it in owners && !owners.owns(it, this) }) return@forEach
            val entry = entries.getOrPut(slot) { Entry() }
            entry.hasSource = source != null && entry.source.captureView(source)
            entry.hasTarget = target != null && entry.target.captureView(target)
            if (!entry.hasSource && !entry.hasTarget) return@forEach
            if (entry.hasSource) bounds(source!!, entry.source.ink, entry.from)
            if (entry.hasTarget) bounds(target!!, entry.target.ink, entry.to)
            val sourceAlpha = source?.alpha ?: 1f
            val targetAlpha = target?.let { masks[it]?.let { lease ->
                if (abs(it.alpha - lease.applied) < .001f) lease.saved else it.alpha
            } ?: it.alpha } ?: 1f
            entry.opacity = when {
                entry.hasSource && entry.hasTarget -> sourceAlpha + (targetAlpha - sourceAlpha) * progress
                entry.hasSource -> sourceAlpha
                else -> targetAlpha
            }.coerceIn(0f, 1f)
            views.forEach { view ->
                owners.acquire(view, this)
                val lease = masks.getOrPut(view) { Alpha(view, view.alpha, view.alpha) }
                if (abs(view.alpha - lease.applied) > .001f) lease.saved = view.alpha
                view.alpha = 0f; lease.applied = 0f
                usedViews += view
            }
            entry.copies = views
            usedSlots += slot
        }
        entries.keys.retainAll(usedSlots)
        masks.keys.toList().filter { it !in usedViews }.forEach(::release)
        layer.setBounds(0, 0, root.width, root.height)
        // A drag changes progress every frame; the signatures only suppress redundant frames.
        var signature = progress.hashCode().toLong() * 31 + root.width * 131 + root.height
        entries.forEach { (slot, entry) ->
            signature = signature * 31 + slot.hashCode() + entry.source.revision * 17L +
                entry.target.revision * 13L + (if (entry.hasSource) 3 else 0) +
                (if (entry.hasTarget) 5 else 0) + entry.opacity.hashCode()
            signature = signature * 31 + entry.from.hashCode()
            signature = signature * 31 + entry.to.hashCode()
        }
        if (drawSignature != signature) { drawSignature = signature; layer.invalidateSelf() }
    }

    private fun bounds(view: View, ink: RectF, out: RectF) {
        out.set(ink)
        matrix.reset(); view.transformMatrixToGlobal(matrix); matrix.mapRect(out); inverseRoot.mapRect(out)
    }
    private fun release(view: View) {
        masks.remove(view)?.let { if (abs(view.alpha - it.applied) < .001f) view.alpha = it.saved }
        ownership?.release(view, this)
    }
    private fun restoreMasks() { masks.keys.toList().forEach(::release) }
    fun clear() {
        restoreMasks(); host?.overlay?.remove(layer)
        host = null; ownership = null; entries.clear(); failed = false; drawSignature = 0L
        usedSlots.clear(); usedViews.clear()
    }

    companion object {
        private val separate = setOf("airplane", "mobile", "stacked_mobile", "wifi", "demo_wifi", "battery",
            "stacked_mobile_icon", "stacked_mobile_type", "single_mobile_sim1", "single_mobile_sim2")
        /** Match identities, never child indices: layouts, ignored slots and overflow differ by row. */
        // Slot identity is fixed for a view instance; the reflective read is the hot cost.
        private val slotCache = java.util.WeakHashMap<View, String>()
        private fun slotOf(view: View): String? {
            synchronized(slotCache) { slotCache[view]?.let { return it.ifEmpty { null } } }
            val slot = (HostAccess.read(view, "mSlot") as? String) ?: runCatching {
                HostAccess.publicMethod(view.javaClass, "getSlot", accessible = false)?.takeIf {
                    it.returnType == String::class.java
                }?.invoke(view) as? String
            }.getOrNull()
            if (slot != null) synchronized(slotCache) { slotCache[view] = slot }
            return slot
        }
        fun slots(container: View): Map<String, View> {
            val candidates = LinkedHashMap<String, MutableList<View>>()
            fun visit(view: View) {
                if (view.visibility != View.VISIBLE) return
                val slot = slotOf(view)
                if (slot != null) {
                    if (slot !in separate && view.width > 0 && view.height > 0)
                        candidates.getOrPut(slot) { ArrayList() }.add(view)
                    return
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(container)
            return candidates.mapNotNull { (slot, views) -> views.singleOrNull()?.let { slot to it } }.toMap()
        }
    }
}
