package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.rules.systemui.icon.DuoHotReloadHandoff
import com.takekazex.hypertweak.presentation.icon.duo.DuoSurface
import com.takekazex.hypertweak.util.logging.DebugLog

/** A frozen row handed over by the retiring generation, as this generation reads it. */
internal class DuoHandoffRow(val record: MutableMap<String, Any?>, val deadline: Long) {
    val battery get() = record[DuoHotReloadHandoff.BATTERY] as View
    val oldView get() = record[DuoHotReloadHandoff.VIEW] as View
    val hostHideBattery get() = record[DuoHotReloadHandoff.HOST_HIDE_BATTERY] as? Boolean ?: false
    val hostIgnoredAirplane get() = record[DuoHotReloadHandoff.HOST_IGNORED_AIRPLANE] as? Boolean
}

/**
 * Retiring side, main thread: stops the eligible active rows in place and hands them over instead of
 * restoring them. A row is eligible only in a plain, settled state; AOD, the open panel, a running
 * battery animation and airplane mode keep the ordinary native restore. Returns the new records.
 */
internal fun DuoRenderer.freezeForHandoff(): List<MutableMap<String, Any?>> {
    handoffFrozen?.let { return it }
    // Only a hot reload saves a destination; a live-settings recreation never freezes.
    val destination = handoffRecords ?: return emptyList()
    if (!enabled || panelVisible) return emptyList<MutableMap<String, Any?>>().also { handoffFrozen = it }
    val frozen = ArrayList<MutableMap<String, Any?>>()
    bindings.values.toList().forEach { binding ->
        if (!handoffEligible(binding)) return@forEach
        val surface = binding.planSurface ?: return@forEach
        binding.preDraw?.let { listener -> binding.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
        binding.preDraw = null
        binding.view.drawFailed = null
        binding.view.setOnClickListener(null)
        binding.view.isClickable = false
        binding.view.setClickTarget(null)
        binding.expiry?.let(main::removeCallbacks)
        binding.expiry = null
        binding.networkMotionSettle?.let(main::removeCallbacks)
        binding.networkMotionSettle = null
        binding.view.finishTransition()
        bindings.remove(binding.battery)
        val record = DuoHotReloadHandoff.record().apply {
            put(DuoHotReloadHandoff.BATTERY, binding.battery)
            put(DuoHotReloadHandoff.VIEW, binding.view)
            put(DuoHotReloadHandoff.PARENT, binding.parent)
            put(DuoHotReloadHandoff.ICONS, binding.icons)
            put(DuoHotReloadHandoff.HOST_HIDE_BATTERY, binding.hostHideBattery)
            put(DuoHotReloadHandoff.HOST_IGNORED_AIRPLANE, binding.hostIgnoredAirplane)
            put(DuoHotReloadHandoff.SURFACE, surface.name)
        }
        destination.add(record)
        frozen += record
        // Pins this generation until it runs; a replacement normally adopts the row in 1-3 s.
        main.postDelayed({
            if (record[DuoHotReloadHandoff.VIEW_ADOPTED] != true) {
                DuoHotReloadHandoff.releaseView(record, "replacement never adopted")
            }
            DuoHotReloadHandoff.releaseMask(record)
        }, DuoHotReloadHandoff.FALLBACK_MS)
    }
    handoffFrozen = frozen
    if (frozen.isNotEmpty()) DebugLog.i(TAG, "hot reload froze Duo rows=${frozen.map { it[DuoHotReloadHandoff.SURFACE] }}")
    return frozen
}

private fun DuoRenderer.handoffEligible(binding: DuoBinding): Boolean =
    binding.active && !binding.failed && binding.nativeReason == null &&
        (binding.surface == DuoSurface.HOME || binding.surface == DuoSurface.COLLAPSED_PROXY ||
            binding.surface == DuoSurface.KEYGUARD && binding.rowPhase == AodHostPhase.LIT) &&
        !binding.aodNativeSuppressed && binding.fullAodNativeState == null &&
        binding.batteryAnimationToAod == null && binding.view.isAttachedToWindow &&
        binding.view.parent === binding.parent && batteryLayoutField?.get(binding.parent) === binding.view &&
        binding.view.icon.content?.airplaneMode != true

/** Replacement side: reads the records the retiring generation saved. */
internal fun DuoRenderer.readHandoff(raw: Any?) {
    val deadline = SystemClock.uptimeMillis() + DuoHotReloadHandoff.HOLD_MS
    DuoHotReloadHandoff.records(raw).forEach { record ->
        if (record[DuoHotReloadHandoff.VIEW_ADOPTED] == true) return@forEach
        val row = DuoHandoffRow(record, deadline)
        handoffRows[row.battery] = row
    }
}

/** The host battery reference [attach] accepts: the battery itself, or the row frozen in its place. */
internal fun DuoRenderer.heldByHandoff(battery: View, held: Any?): Boolean =
    held != null && handoffRows[battery]?.oldView === held

/** Replacement side, main thread: [binding] has just taken the host reference from the frozen row. */
internal fun DuoRenderer.completeHandoff(binding: DuoBinding) {
    val row = binding.handoff ?: return
    binding.handoff = null
    binding.handoffRetry?.let(main::removeCallbacks)
    binding.handoffRetry = null
    handoffRows.remove(binding.battery)
    row.record[DuoHotReloadHandoff.VIEW_ADOPTED] = true
    val old = row.oldView
    old.visibility = View.GONE
    (old.parent as? ViewGroup)?.takeIf { it === binding.parent }?.removeView(old)
    DebugLog.i(TAG, "Duo handover adopted surface=${binding.surface}")
}

/** Replacement side: gives a frozen row back to the native battery instead of adopting it. */
internal fun DuoRenderer.releaseHandoff(binding: DuoBinding, reason: String) {
    val row = binding.handoff ?: return
    binding.handoff = null
    binding.handoffRetry?.let(main::removeCallbacks)
    binding.handoffRetry = null
    handoffRows.remove(binding.battery)
    DuoHotReloadHandoff.releaseView(row.record, reason)
}

/** Frozen rows no binding of this generation took: their batteries never attached here. */
internal fun DuoRenderer.releaseUnboundHandoff(reason: String) {
    handoffRows.values.toList().forEach { row ->
        if (bindings[row.battery]?.handoff === row) return@forEach
        handoffRows.remove(row.battery)
        DuoHotReloadHandoff.releaseView(row.record, reason)
    }
}

/**
 * While the replacement's network sources bind, Duo has no content yet. A frozen row stays up to
 * its deadline instead of flashing the native row; any other native reason releases it at once.
 */
internal fun DuoRenderer.holdsHandoff(binding: DuoBinding, nativeReason: String): Boolean {
    val row = binding.handoff ?: return false
    val remaining = row.deadline - SystemClock.uptimeMillis()
    if (!nativeReason.startsWith("no content") || remaining <= 0L) return false
    if (binding.handoffRetry == null) {
        binding.handoffRetry = Runnable {
            binding.handoffRetry = null
            if (bindings[binding.battery] === binding) guarded { reconcile(binding) }
        }.also { main.postDelayed(it, remaining + 1L) }
    }
    return true
}
