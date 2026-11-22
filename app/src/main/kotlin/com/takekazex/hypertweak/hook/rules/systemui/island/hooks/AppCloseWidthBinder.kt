package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.rules.systemui.island.ISLAND_TAG
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandMembers
import com.takekazex.hypertweak.hook.rules.systemui.island.spectrum.IslandLyricConflict
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * The host hands WM the BIG close rect at `request_close_position`, then re-measures the
 * template at `close_app_start`. The fake view pre-scales by screen / *new* width while the
 * window still shrinks into the *old* rect, so a width change in between (lyric templates
 * fill `AT_MOST(screen/2)` areas) leaves the island at old / new scale. Until the close ends,
 * every re-measure of that view keeps the result WM was given; afterwards the host re-measures.
 * The width changes both ways: injected lyric views appear (258 -> 714) or hide (714 -> 258).
 * The pinned geometry is read from the view's stored fields, which is what the rect came from;
 * the last helper result we saw is not, because another module may rewrite those fields.
 */
internal class AppCloseWidthPin(private val now: () -> Long = SystemClock::uptimeMillis) {
    private class Pin(val result: Any, val width: Int, val deadline: Long) {
        var diverged = false
    }
    private val pins = WeakHashMap<Any, Pin>()

    /**
     * Pin [stored], the view's geometry when the host built the close rect. Rejected when the
     * rect does not come from it (a full-template geometry override answered the rect).
     */
    fun pin(view: Any, stored: Any, rectWidth: Int, widths: (Any) -> List<Int>): Boolean {
        if (rectWidth !in widths(stored)) {
            pins.remove(view)
            return false
        }
        pins[view] = Pin(stored, rectWidth, now() + TIMEOUT_MS)
        return true
    }

    /** The pinned result when [natural] differs from it, or null to keep the host measurement. */
    fun held(view: Any, natural: Any, sameWidth: (Any, Any) -> Boolean): Any? {
        val pin = pins[view] ?: return null
        if (now() > pin.deadline) return null
        if (sameWidth(natural, pin.result)) return null
        pin.diverged = true
        return pin.result
    }

    fun pinnedWidth(view: Any): Int? = pins[view]?.width

    /** Drop the pin; true when a held measurement must now be redone by the host. */
    fun release(view: Any): Boolean = pins.remove(view)?.diverged == true

    fun clear() = pins.clear()

    companion object { const val TIMEOUT_MS = 1500L }
}

internal object AppCloseWidthBinder {
    fun install(context: IslandHookContext) {
        val access = context.access
        val runtime = context.runtime
        val registrar = context.registrar
        val pin = context.closePin
        val closeTarget: Method = access.windowController.declaredMethods.single {
            it.returnType == Rect::class.java && it.parameterTypes.toList() ==
                listOf(access.state, access.content, String::class.java, Boolean::class.javaPrimitiveType)
        }.apply { isAccessible = true }.also(registrar::deoptimize)
        val closeEnd = NativeIslandMembers.named(access.content, "updateViewStateWhenCloseEnd", emptyList())

        fun widths(result: Any): List<Int> = context.host.nativeResult(result).let { listOf(it.bigWidth, it.bigWidthWithSmall) }

        registrar.after(closeTarget, "three_islands_close_width_pin") { param ->
            if (!access.big.isInstance(param.args[0])) return@after
            val view = param.args[1] ?: return@after
            val rect = param.result as? Rect ?: return@after
            if (!runtime.isMainThread() || runtime.retiring() || runtime.sessionForView(view) == null) return@after
            runCatching {
                if (pin.pin(view, context.host.storedResult(view), rect.width(), ::widths)) {
                    runtime.main.postDelayed({ finish(context, view) }, AppCloseWidthPin.TIMEOUT_MS + 100)
                }
            }.onFailure { DebugLog.w(ISLAND_TAG, "pin app-close BIG width", it) }
        }
        registrar.after(closeEnd.also(registrar::deoptimize), "three_islands_close_width_release") { param ->
            val view = param.thisObject
            // Let the remaining synchronous close-end callbacks finish on the pinned geometry.
            runtime.main.post { finish(context, view) }
        }
    }

    /** Called from the helper result hook before any module geometry is planned. */
    fun hold(context: IslandHookContext, view: Any, natural: Any): Any? {
        val pin = context.closePin
        if (pin.pinnedWidth(view) == null) return null
        // A module media-return projection owns this render; its frame is the close target.
        context.runtime.sessionForView(view)?.let { session ->
            if (context.runtime.spectrum.renderingReturnFrame(session, view) != null) return null
        }
        val held = pin.held(view, natural) { first, second ->
            context.host.nativeResult(first) == context.host.nativeResult(second)
        } ?: return null
        DebugLog.i(ISLAND_TAG, "app close keeps BIG width ${pin.pinnedWidth(view)}; native re-measure " +
            "${context.host.nativeResult(natural).bigWidth} lyric=${lyricState(context, view)}")
        return held
    }

    private fun finish(context: IslandHookContext, view: Any) {
        if (!context.closePin.release(view) || context.runtime.retiring()) return
        runCatching { context.access.call(view, "updateBigIslandViewWidth") }
            .onFailure { DebugLog.w(ISLAND_TAG, "re-measure BIG after app close", it) }
    }

    /** Which injected lyric views the host measured; diagnostics only. */
    private fun lyricState(context: IslandHookContext, view: Any): String = runCatching {
        val shown = ArrayList<String>()
        fun walk(node: View) {
            if (IslandLyricConflict.injectedTag(node.tag)) {
                shown += "${node.tag}:${node.visibility}:${node.measuredWidth}"
                return
            }
            if (node is ViewGroup) for (i in 0 until node.childCount) walk(node.getChildAt(i))
        }
        listOf("getBigIslandAreaLeft", "getBigIslandAreaRight").forEach { name ->
            (context.access.call(view, name) as? View)?.let(::walk)
        }
        val guard = context.runtime.sessionForView(view)?.spectrum?.lyricGuard != null
        "${shown.ifEmpty { listOf("none") }} guard=$guard"
    }.getOrElse { "unknown" }
}
