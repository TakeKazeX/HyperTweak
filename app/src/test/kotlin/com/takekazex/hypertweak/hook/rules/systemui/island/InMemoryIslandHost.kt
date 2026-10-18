package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.HostRect
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.host.TemplateAreaPair
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandScene
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MusicDemandDimensions
import com.takekazex.hypertweak.hook.rules.systemui.island.model.NativeMeasureResult
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SafeInsets
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize

/** Island task/content view used by the in-memory host. */
internal class FakeTask(val key: String, val owner: Any) {
    var state: Any? = null
    var lastState: Any? = null
    var width: Int = 100
}

/** Stable slot handler; `setCurrent` and `addState` are addressed through this object. */
internal class FakeHandler(val key: String, val owner: Any, val slot: IslandSlot)

internal data class FakeState(val kind: IslandStateKind)

/**
 * Fake host for coordinator tests. It records every scheduling call in order and keeps the
 * handler/current-task distinction of the native coordinator.
 */
internal class InMemoryIslandHost : IslandHostPorts {
    val calls = mutableListOf<String>()
    val slots = mutableMapOf<Any, MutableMap<IslandSlot, Any?>>()
    val handlers = mutableMapOf<Any, MutableMap<IslandSlot, FakeHandler>>()
    val queue = mutableListOf<Any>()
    val sizes = mutableMapOf<Any, IslandContentSize>()
    var onCalculate: (Any) -> Unit = {}
    var scene = IslandScene(1200, 2600, 170, 170, 12, 72, 400, SafeInsets.NONE, IslandOrientation.PORTRAIT)
    var orientation = IslandOrientation.PORTRAIT

    fun bind(owner: Any, slot: IslandSlot, task: Any?): InMemoryIslandHost {
        slots.getOrPut(owner) { mutableMapOf() }[slot] = task
        return this
    }

    fun record(value: String): Unit {
        calls += value
    }

    override fun ownerOf(content: Any): Any? = (content as? FakeTask)?.owner
    override fun windowOf(owner: Any): Any? = null
    override fun orientationOf(owner: Any): IslandOrientation = orientation
    override fun smallestScreenWidthDp(owner: Any): Int = 400
    override fun isTinyScreen(owner: Any): Boolean = false
    override fun inConfigRebuild(owner: Any): Boolean = false
    override fun showOnce(owner: Any): Any? = null
    override fun tempShow(handler: Any): Any? {
        check(handler is FakeHandler && handler.slot == IslandSlot.BIG)
        return null
    }
    override fun current(owner: Any, slot: IslandSlot): Any? = slots[owner]?.get(slot)

    override fun handler(owner: Any, slot: IslandSlot): FakeHandler =
        handlers.getOrPut(owner) { mutableMapOf() }
            .getOrPut(slot) { FakeHandler(slot.name.lowercase(), owner, slot) }

    override fun queue(owner: Any): MutableList<Any> = queue
    override fun key(view: Any): String? = (view as? FakeTask)?.key
    override fun state(view: Any): Any? = (view as? FakeTask)?.state
    override fun stateKind(view: Any): IslandStateKind =
        (state(view) as? FakeState)?.kind ?: IslandStateKind.OTHER

    override fun isContent(view: Any): Boolean = view is FakeTask
    override fun isFake(view: Any): Boolean = false
    override fun isCoordinator(value: Any): Boolean = false
    override fun isSmallState(value: Any?): Boolean = (value as? FakeState)?.kind == IslandStateKind.SMALL
    override fun realContent(view: Any): Any? = view
    override fun nativeSmallWidth(view: Any): Int = (view as? FakeTask)?.width ?: 0

    override fun number(owner: Any, member: String): Int {
        record("number:${label(owner)}:$member")
        return 0
    }

    override fun ceilDimension(owner: Any, member: String): Int = number(owner, member)

    override fun call(owner: Any, member: String, vararg args: Any?): Any? {
        record("call:${label(owner)}:$member")
        if (member == "calculateBigIslandWidth") onCalculate(owner)
        if (member == "getCurrent" && owner is FakeHandler) {
            return current(owner.owner, owner.slot)
        }
        return null
    }

    override fun nextState(view: Any, slot: IslandSlot): Any = FakeState(
        when (slot) {
            IslandSlot.BIG -> IslandStateKind.BIG
            IslandSlot.SMALL -> IslandStateKind.SMALL
            IslandSlot.HIDDEN -> IslandStateKind.HIDDEN
            else -> IslandStateKind.OTHER
        },
    )

    override fun setState(view: Any, state: Any?) {
        record("setState:${label(view)}=${kind(state)}")
        (view as? FakeTask)?.state = state
    }

    override fun setLastState(view: Any, state: Any?) {
        record("setLastState:${label(view)}=${kind(state)}")
        (view as? FakeTask)?.lastState = state
    }

    override fun lendToHidden(view: Any) {
        record("lend:${label(view)}")
        (view as? FakeTask)?.state = FakeState(IslandStateKind.HIDDEN)
    }

    override fun renderBig(source: Any?): Any = source ?: Any()

    override fun setCurrent(handler: Any, view: Any?) {
        record("setCurrent:${label(handler)}=${label(view)}")
        if (handler !is FakeHandler) return
        slots.getOrPut(handler.owner) { mutableMapOf() }[handler.slot] = view
    }

    override fun addState(handler: Any, view: Any, state: Any?) {
        record("addState:${label(handler)}:${label(view)}=${kind(state)}")
        if (state != null) (view as? FakeTask)?.state = state
    }

    override fun stop(handler: Any) = record("stop:${label(handler)}")
    override fun enqueueHidden(handler: Any, view: Any) = record("enqueue:${label(view)}")
    override fun updateTouchRegion(owner: Any) = record("region")
    override fun userExpanded(owner: Any): Boolean = false
    override fun setUserExpanded(owner: Any, value: Boolean) = record("userExpanded=$value")
    override fun animationController(owner: Any): Any? = null
    override fun windowCoordinator(window: Any): Any? = null
    override fun swipeCoordinator(coordinator: Any): Any? = null
    override fun fakeView(view: Any): Any? = null
    override fun templateAreas(view: Any): List<TemplateAreaPair> = emptyList()
    override fun fixedCutoutWidth(view: Any): Int = 0
    override fun bigIslandView(view: Any): Any? = null
    override fun packageName(view: Any): String? = null
    override fun smallRect(view: Any, x: Int): HostRect? = null
    override fun bigRect(view: Any): HostRect? = null
    override fun expandedRect(view: Any): HostRect? = null
    override fun isSwipeTowardsSmall(window: Any, x: Float): Boolean = false
    override fun touchAction(event: Any): Int = 0
    override fun touchX(event: Any): Float = 0f
    override fun touchY(event: Any): Float = 0f
    override fun isSwipeRight(event: Any): Boolean = false
    override fun isSwipeLeft(event: Any): Boolean = false
    override fun onSwipe(view: Any, x: Float, y: Float, current: Any?, next: Any?) = record("swipe:${label(view)}")
    override fun resetSwipe(view: Any) = record("resetSwipe:${label(view)}")
    override fun measuredContentSize(view: Any, input: Any): IslandContentSize? = sizes[view]
    override fun safeInsets(owner: Any): SafeInsets = SafeInsets.NONE
    override fun sceneOf(owner: Any, input: Any, orientation: IslandOrientation): IslandScene = scene
    override fun minimumBigWidth(helper: Any): Int = 0
    override fun nativeResult(result: Any): NativeMeasureResult = NativeMeasureResult(0, 0, 0, 0)
    override fun createResult(geometry: IslandResultGeometry): Any = geometry
    override fun musicDemand(view: Any): MusicDemandDimensions? = null
    override fun mediaAreasAvailable(view: Any): Boolean = false
    override fun lyricModuleInstalled(view: Any): Boolean = false
    override fun windowState(view: Any): Any? = null
    override fun configChange(windowState: Any): Boolean = false
    override fun setConfigChange(windowState: Any, value: Boolean) = record("configChange=$value")
    override fun viewModel(view: Any): Any? = null
    override fun updateViewState(viewModel: Any, view: Any) = record("updateState:${label(view)}")
    override fun animationView(animator: Any): Any? = null
    override fun pressBigIsland(animator: Any, view: Any) = record("press:${label(view)}")
    override fun resetPressBigIsland(animator: Any, view: Any) = record("resetPress:${label(view)}")

    private fun kind(state: Any?): String = when ((state as? FakeState)?.kind) {
        IslandStateKind.BIG -> "BIG"
        IslandStateKind.SMALL -> "SMALL"
        IslandStateKind.HIDDEN -> "HIDDEN"
        IslandStateKind.OTHER -> "OTHER"
        null -> "null"
    }

    private fun label(view: Any?): String? = when (view) {
        is FakeTask -> view.key
        is FakeHandler -> view.key
        else -> null
    }
}
