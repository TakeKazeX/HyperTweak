package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Color
import android.graphics.Picture
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoSignalRow
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.SignalRowMotion
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.abs

/** Uses the same per-SIM overlay as Duo while retaining the normal host icon-controller slot. */
internal class StackedPanelMotion(private val callback: Any) {
    private val getters = HashMap<Class<*>, Method?>()
    private var expandedRow: View? = null
    private var fakeRow: View? = null
    private val fields = HashMap<Pair<Class<*>, String>, Field?>()
    private val motions = HashMap<Int, SignalRowMotion>()
    private val targets = HashMap<Int, View>()
    private val masked = IdentityHashMap<View, Float>()
    private var root: ViewGroup? = null
    private var observer: ViewTreeObserver? = null
    private var visible = false
    private var progress = 0f
    private var rows: List<DuoSignalRow> = emptyList()
    private var picture: Picture? = null
    private val crop = RectF()
    private val sourceBounds = RectF()
    private val listener = ViewTreeObserver.OnPreDrawListener {
        runCatching { render() }.onFailure { clearLayers() }
        true
    }

    fun update(nextRows: List<DuoSignalRow>, commands: Picture?, fraction: Float?, showing: Boolean?) {
        rows = nextRows; picture = commands
        fraction?.takeIf { it.isFinite() }?.let { progress = it.coerceIn(0f, 1f); if (it > 0f) visible = true }
        showing?.let { visible = it }
        if (!visible || picture == null || rows.isEmpty()) { clear(); return }
        val owner = read(callback, "this\$0") ?: return
        val lazyHeader = read(owner, "headerController") ?: return
        val lazyClass = lazyHeader.javaClass
        if (!getters.containsKey(lazyClass)) getters[lazyClass] = lazyClass.methods
            .firstOrNull { it.name == "get" && it.parameterCount == 0 }
        val header = getters[lazyClass]?.invoke(lazyHeader) ?: return
        val expanded = read(header, "controlCenterStatusBar") as? View ?: return
        val nextRoot = expanded.rootView as? ViewGroup ?: return
        if (root !== nextRoot || observer?.isAlive != true) {
            clearLayers(); observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            root = nextRoot; observer = nextRoot.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
        }
        expandedRow = expanded
        fakeRow = (read(header, "controlCenterFakeStatusBar") ?: read(header, "fakeStatusBar")) as? View
        render()
    }

    private fun render() {
        val host = root ?: return
        val commands = picture ?: return
        if (!host.isAttachedToWindow) { clear(); return }
        val owner = read(callback, "this\$0") ?: return
        val real = read(owner, "realSystemIcons") as? View ?: return
        val source = find(real) { read(it, "mSlot") == "stacked_mobile_icon" } as? ImageView
            ?: run { clearLayers(); return }
        if (source.width <= 0 || source.height <= 0 || !source.isVisible) { clearLayers(); return }
        val expanded = expandedRow?.takeIf { it.isAttachedToWindow }
            ?: run { clearLayers(); return }
        val container = find(expanded) { it.javaClass.name.endsWith(".MiuiStatusIconContainer") }
            ?: run { clearLayers(); return }
        val signalId = host.resources.getIdentifier("mobile_signal", "id", host.context.packageName)
        val endpoints = rows.map { row ->
            ControlCenterCarrierBlockHooker.signalHandoverTarget(container, row.subId)
                ?: find(container) { read(it, "subId") == row.subId }
                    ?.findViewById<View>(signalId)?.takeIf { it.isVisible && it.width > 0 }
        }
        if (endpoints.any { it == null }) { clearLayers(); return }
        val drawable = source.drawable ?: run { clearLayers(); return }
        var allReady = true
        val used = rows.map { it.subId }.toSet()
        motions.keys.toList().filter { it !in used }.forEach(::release)
        rows.forEachIndexed { index, row ->
            val target = endpoints[index] ?: return@forEachIndexed
            if (targets[row.subId] !== target) {
                release(row.subId)
                val carrier = ControlCenterCarrierBlockHooker.signalHandoverTarget(container, row.subId)
                if (carrier != null && !ControlCenterCarrierBlockHooker.acquireDuoTarget(carrier, this)) {
                    allReady = false; return@forEachIndexed
                }
                targets[row.subId] = target
            }
            val top = if (rows.size > 1 && index == 1) .632f else 0f
            val bottom = if (rows.size > 1 && index == 0) .632f else 1f
            crop.set(0f, top * commands.height, commands.width.toFloat(), bottom * commands.height)
            sourceBounds.set(drawable.bounds)
            source.imageMatrix.mapRect(sourceBounds)
            sourceBounds.offset(source.paddingLeft.toFloat(), source.paddingTop.toFloat())
            val height = sourceBounds.height()
            val originY = sourceBounds.top
            sourceBounds.top = originY + height * top
            sourceBounds.bottom = originY + height * bottom
            val tint = (read(source, "mDrawableColor") as? Int)?.takeIf { it != 0 } ?: Color.WHITE
            val ready = motions.getOrPut(row.subId) { SignalRowMotion() }.update(host, source,
                sourceBounds, target, commands, crop, tint, progress)
            allReady = allReady && ready
        }
        if (allReady) {
            // Only duplicate status-slot images are hidden. The home source remains available for
            // coordinate measurement; native per-SIM targets belong to their row overlays.
            val copies = listOfNotNull(expandedRow, fakeRow).flatMap { row ->
                collect(row) { read(it, "mSlot") == "stacked_mobile_icon" }
            }
            masked.keys.toList().filter { it !in copies }.forEach { view ->
                val alpha = masked.remove(view)
                if (alpha != null && abs(view.alpha) < .001f) view.alpha = alpha
            }
            copies.forEach { view ->
                if (!masked.containsKey(view) || abs(view.alpha) > .001f) masked[view] = view.alpha
                view.alpha = 0f
            }
        } else restoreMasks()
    }

    private fun release(subId: Int) {
        motions.remove(subId)?.clear()
        targets.remove(subId)?.let { ControlCenterCarrierBlockHooker.releaseDuoTarget(it, this) }
    }
    private fun restoreMasks() {
        masked.forEach { (view, alpha) -> if (abs(view.alpha) < .001f) view.alpha = alpha }
        masked.clear()
    }
    private fun clearLayers() {
        motions.keys.toList().forEach(::release)
        restoreMasks()
    }
    fun clear() {
        clearLayers()
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        observer = null; root = null; expandedRow = null; fakeRow = null
    }
    private fun read(owner: Any, name: String): Any? {
        val key = owner.javaClass to name
        if (!fields.containsKey(key)) fields[key] = generateSequence(owner.javaClass) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }.firstOrNull()
        return fields[key]?.get(owner)
    }
    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), predicate)?.let { return it }
        return null
    }
    private fun collect(view: View, predicate: (View) -> Boolean): List<View> = buildList {
        if (predicate(view)) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(collect(view.getChildAt(i), predicate))
    }
}
