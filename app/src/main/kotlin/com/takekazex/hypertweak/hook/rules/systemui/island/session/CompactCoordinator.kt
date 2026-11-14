package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.CompactIslandGeometry
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.CompactIslandInput
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandTemplateWidthLease
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.WeakHashMap

/** Camera-seated main templates: placeholder removal and the portrait three-island main island. */
internal class CompactCoordinator(private val runtime: IslandSessionState) : CompactActions {
    private val host get() = runtime.host
    private var retired = false
    private class Presented(val geometry: IslandResultGeometry, val screenWidth: Int, val scheduled: Boolean)
    private val presented = WeakHashMap<Any, Presented>()
    private val leases = IslandTemplateWidthLease()

    override fun afterMeasure(view: Any, input: Any, result: Any, claimed: IslandResultGeometry?): IslandResultGeometry? {
        if (retired) return null
        val owner = host.ownerOf(view) ?: run {
            forget(view)
            return null
        }
        val portrait = portraitPhone(owner)
        if (claimed != null) {
            if (portrait && CompactIslandGeometry.asymmetric(claimed)) {
                val scene = host.sceneOf(owner, input, IslandOrientation.PORTRAIT)
                present(owner, view, claimed, scene.screenWidth, scene.gap, scheduled = true)
            } else {
                forget(view)
            }
            return claimed
        }
        if (!portrait || !runtime.preferences.removePlaceholder || runtime.retiring()) {
            forget(view)
            return null
        }
        val geometry = try {
            compact(owner, view, input, result)
        } catch (error: Throwable) {
            DebugLog.w(TAG, "compact island measurement failed; native width kept", error)
            null
        }
        if (geometry == null) {
            forget(view)
            return null
        }
        val scene = host.sceneOf(owner, input, IslandOrientation.PORTRAIT)
        present(owner, view, geometry, scene.screenWidth, scene.gap, scheduled = false)
        return geometry
    }

    private fun portraitPhone(owner: Any): Boolean =
        host.orientationOf(owner) == IslandOrientation.PORTRAIT &&
            host.smallestScreenWidthDp(owner) < 600 && !host.isTinyScreen(owner)

    private fun compact(owner: Any, view: Any, input: Any, result: Any): IslandResultGeometry? {
        val scene = host.sceneOf(owner, input, IslandOrientation.PORTRAIT)
        if (scene.screenHeight <= scene.screenWidth) return null
        val content = host.measuredContentSize(view, input) ?: return null
        // A side-media lease may still report a zero seat; the main template always keeps the camera.
        val cutout = runtime.nativeCutoutRead.run { host.fixedCutoutWidth(view) }
        return CompactIslandGeometry.calculate(CompactIslandInput(
            screenWidth = scene.screenWidth,
            screenHeight = scene.screenHeight,
            clock = scene.clockWidth,
            battery = scene.batteryWidth,
            cutout = cutout,
            maxWidth = scene.maxWidth,
            space = scene.gap,
            smallWidth = host.measuredSmallWidth(input),
            content = content,
            margin = host.nativeResult(result).marginWidth,
            safeLeft = scene.safe.left,
            safeRight = scene.safe.right,
        ))
    }

    private fun present(owner: Any, view: Any, geometry: IslandResultGeometry, screenWidth: Int, gap: Int, scheduled: Boolean) {
        presented[view] = Presented(geometry, screenWidth, scheduled)
        // A full row or side-media template owns the same area pair; never stack two snapshots.
        if (runtime.sessionForView(view)?.template?.templateWidths?.holds(view) == true) return
        val areas = host.templateAreas(view).mapNotNull { pair ->
            val left = pair.left as? View ?: return@mapNotNull null
            val right = pair.right as? View ?: return@mapNotNull null
            left to right
        }
        // A content refresh can replace one real/fake pair; retire only the detached pair.
        leases.retain(view, areas)
        val cutout = geometry.width - geometry.left - geometry.right
        areas.forEach { (left, right) -> leases.acquire(view, left, right, cutout) }
    }

    override fun translation(view: Any, args: Array<Any?>): Array<Any?>? {
        if (retired || runtime.retiring()) return null
        val owner = host.ownerOf(view) ?: return null
        if (!portraitPhone(owner) || !runtime.preferences.removePlaceholder && !runtime.preferences.threeIslands) return null
        val current = presented[view] ?: return null
        if (current.scheduled && !schedulingOwns(owner)) return null
        val geometry = current.geometry
        if (args.size < 4) return null
        // The host chooses the small/no-small field set; only redirect when it publishes ours.
        if ((args[0] as? Number)?.toInt() != geometry.left || (args[1] as? Number)?.toInt() != geometry.right ||
            (args[2] as? Number)?.toInt() != geometry.width) return null
        val native = (args[3] as? Number)?.toFloat() ?: return null
        return args.copyOf().also {
            it[3] = native + CompactIslandGeometry.translation(geometry, current.screenWidth)
        }
    }

    override fun holds(view: Any): Boolean = leases.holds(view)

    private fun schedulingOwns(owner: Any): Boolean = runtime.sessions[owner]?.let(runtime::enabled) == true

    override fun forget(view: Any) {
        presented.remove(view)
        if (leases.holds(view)) leases.release(view)
    }

    override fun refresh() {
        runtime.sessions.snapshot().forEach { session ->
            val owner = session.owner
            val big = host.current(owner, IslandSlot.BIG)
            // The frame relayout republishes the visible summaries. Hidden, queued and expanded
            // views keep stored fields that their next native return animation reads.
            val views = (host.queue(owner) + session.slot.extras + listOfNotNull(
                host.current(owner, IslandSlot.SMALL),
                host.current(owner, IslandSlot.EXPANDED),
            ) + presented.keys.filter { host.ownerOf(it) === owner }).distinct().filter { it !== big }
            views.forEach { view ->
                runCatching {
                    runtime.layoutMeasurement.run { runtime.measure(view) }
                    if (presented[view] == null) (host.bigIslandView(view) as? View)?.translationX = 0f
                }.onFailure { DebugLog.w(TAG, "refresh compact island fields", it) }
            }
        }
    }

    override fun retire() {
        retired = true
        presented.keys.toList().forEach { view ->
            runCatching { (host.bigIslandView(view) as? View)?.translationX = 0f }
                .onFailure { DebugLog.w(TAG, "retire compact island position", it) }
        }
        presented.clear()
        runCatching { leases.releaseAll() }.onFailure { DebugLog.w(TAG, "retire compact island areas", it) }
    }

    private companion object { const val TAG = "ThreeIslands" }
}
