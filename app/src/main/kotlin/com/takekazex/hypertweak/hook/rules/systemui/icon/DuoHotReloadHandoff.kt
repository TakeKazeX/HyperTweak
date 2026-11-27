package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Keeps an active Duo row on screen across a hot reload.
 *
 * Restoring the native row when the old generation retires and rebuilding Duo in the replacement
 * showed the stock signal and battery icons for 2-3 s on every reload. Instead the retiring
 * generation freezes its row: the view, the host's battery reference and the network-slot mask
 * stay as they are. The replacement then swaps its own row in within one frame, or puts the native
 * row back.
 *
 * A record crosses generations, so it holds only platform types, and every generation reads it by
 * these keys alone. Both sides mark what they took over, and the retiring generation's fallback
 * restores whatever the replacement never claimed, including a replacement that does not know the
 * handoff at all.
 */
internal object DuoHotReloadHandoff {
    const val BATTERY = "battery"
    const val VIEW = "view"
    const val PARENT = "parent"
    const val ICONS = "icons"
    const val HOST_HIDE_BATTERY = "hostHideBattery"
    const val HOST_IGNORED_AIRPLANE = "hostIgnoredAirplane"
    /** [com.takekazex.hypertweak.presentation.icon.PlanSurface] name of the frozen row. */
    const val SURFACE = "surface"
    /** The container's host-owned ignored slots, without any module mask. */
    const val HOST_IGNORED = "hostIgnored"
    const val MASK_ADOPTED = "maskAdopted"
    const val VIEW_ADOPTED = "viewAdopted"

    /** The replacement keeps a frozen row this long while its network sources bind. */
    const val HOLD_MS = 5_000L
    /** A provisional mask the replacement's Duo never re-asserted is released after this. */
    const val MASK_EXPIRY_MS = HOLD_MS + 1_000L
    /** The retiring generation restores anything still unclaimed after this. */
    const val FALLBACK_MS = 10_000L

    private const val TAG = "DuoHandoff"
    private const val BATTERY_FIELD = "mBattery"
    private const val HIDE_FIELD = "mIsHideBattery"
    private const val VISIBILITY_METHOD = "updateVisibility\$6"

    fun record(): HashMap<String, Any?> = HashMap()

    @Suppress("UNCHECKED_CAST")
    fun records(raw: Any?): List<MutableMap<String, Any?>> =
        (raw as? List<*>).orEmpty().mapNotNull { it as? MutableMap<String, Any?> }
            .filter { it[BATTERY] is View && it[VIEW] is View && it[PARENT] is ViewGroup && it[ICONS] is View }

    /**
     * Puts the host battery back in place of the frozen row and removes the row. Any generation
     * may run it, on the main thread, so it reflects on the host directly instead of through a
     * generation's caches. It is a no-op once the row has left its parent.
     */
    fun releaseView(record: MutableMap<String, Any?>, reason: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Duo handoff release off the main thread" }
        record[VIEW_ADOPTED] = true
        val battery = record[BATTERY] as? View ?: return false
        val view = record[VIEW] as? View ?: return false
        val parent = record[PARENT] as? ViewGroup ?: return false
        if (view.parent !== parent) return false
        runCatching {
            val layout = field(parent.javaClass, BATTERY_FIELD)
            if (layout?.get(parent) === view) layout.set(parent, battery)
            field(parent.javaClass, HIDE_FIELD)?.setBoolean(parent, record[HOST_HIDE_BATTERY] as? Boolean ?: false)
            view.visibility = View.GONE
            parent.removeView(view)
            method(battery.javaClass, VISIBILITY_METHOD)?.invoke(battery)
            parent.requestLayout()
        }.onFailure { DebugLog.w(TAG, "native battery restore failed", it) }
        DebugLog.i(TAG, "Duo handover released native surface=${record[SURFACE]} reason=$reason")
        return true
    }

    /** Restores the container's host slots when no replacement took the frozen mask over. */
    fun releaseMask(record: MutableMap<String, Any?>) {
        if (record[MASK_ADOPTED] == true) return
        record[MASK_ADOPTED] = true
        val container = record[ICONS] as? View ?: return
        val host = (record[HOST_IGNORED] as? List<*>)?.filterIsInstance<String>() ?: return
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val ignored = field(container.javaClass, "ignoredSlots")?.get(container) as? MutableList<Any?> ?: return
            ignored.clear()
            ignored.addAll(host)
            container.requestLayout()
        }.onFailure { DebugLog.w(TAG, "ignoredSlots restore failed", it) }
    }

    private fun field(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            try { return current.getDeclaredField(name).apply { isAccessible = true } } catch (_: NoSuchFieldException) {}
            current = current.superclass
        }
        return null
    }

    private fun method(type: Class<*>, name: String): java.lang.reflect.Method? =
        type.declaredMethods.singleOrNull { it.name == name && it.parameterCount == 0 }?.apply { isAccessible = true }
}
