package com.takekazex.hypertweak.hook.rules.systemui.island.host

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandScene
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MusicDemandDimensions
import com.takekazex.hypertweak.hook.rules.systemui.island.model.NativeMeasureResult
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SafeInsets
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize

/** Native state slots this module addresses by name. */
internal enum class IslandSlot { BIG, SMALL, EXPANDED, HIDDEN, SHOW_ONCE }

internal enum class IslandStateKind { SMALL, BIG, HIDDEN, OTHER }

/** Pure rectangle passed across the host boundary. */
internal data class HostRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One real or fake template area pair. */
internal data class TemplateAreaPair(val left: Any, val right: Any)

internal data class TouchInput(val action: Int, val x: Float, val y: Float) {
    companion object {
        const val DOWN = 0
        const val CANCEL = 3
    }
}

/**
 * Narrow host boundary for the island coordinators. The adapter owns every Android and
 * reflection detail; coordinators only see primitive models and opaque host objects.
 */
internal interface IslandHostPorts {
    fun ownerOf(content: Any): Any?
    fun windowOf(owner: Any): Any?
    fun orientationOf(owner: Any): IslandOrientation
    fun smallestScreenWidthDp(owner: Any): Int
    fun isTinyScreen(owner: Any): Boolean
    fun inConfigRebuild(owner: Any): Boolean
    fun showOnce(owner: Any): Any?
    fun tempShow(handler: Any): Any?
    fun current(owner: Any, slot: IslandSlot): Any?
    fun handler(owner: Any, slot: IslandSlot): Any?
    fun queue(owner: Any): MutableList<Any>
    fun key(view: Any): String?
    fun state(view: Any): Any?
    fun stateKind(view: Any): IslandStateKind
    fun isContent(view: Any): Boolean
    fun isFake(view: Any): Boolean
    fun isCoordinator(value: Any): Boolean
    fun isSmallState(value: Any?): Boolean
    fun realContent(view: Any): Any?
    fun nativeSmallWidth(view: Any): Int
    fun number(owner: Any, member: String): Int
    fun ceilDimension(owner: Any, member: String): Int
    fun call(owner: Any, member: String, vararg args: Any?): Any?
    fun nextState(view: Any, slot: IslandSlot): Any
    fun setState(view: Any, state: Any?)
    fun setLastState(view: Any, state: Any?)
    fun lendToHidden(view: Any)
    fun renderBig(source: Any?): Any
    fun setCurrent(handler: Any, view: Any?)
    fun addState(handler: Any, view: Any, state: Any?)
    fun stop(handler: Any)
    fun enqueueHidden(handler: Any, view: Any)
    fun updateTouchRegion(owner: Any)
    fun userExpanded(owner: Any): Boolean
    fun setUserExpanded(owner: Any, value: Boolean)
    fun animationController(owner: Any): Any?
    fun windowCoordinator(window: Any): Any?
    fun swipeCoordinator(coordinator: Any): Any?
    fun fakeView(view: Any): Any?
    fun templateAreas(view: Any): List<TemplateAreaPair>
    fun fixedCutoutWidth(view: Any): Int
    fun bigIslandView(view: Any): Any?
    fun packageName(view: Any): String?
    fun smallRect(view: Any, x: Int): HostRect?
    fun bigRect(view: Any): HostRect?
    fun expandedRect(view: Any): HostRect?
    fun isSwipeTowardsSmall(window: Any, x: Float): Boolean
    fun touchAction(event: Any): Int
    fun touchX(event: Any): Float
    fun touchY(event: Any): Float
    fun isSwipeRight(event: Any): Boolean
    fun isSwipeLeft(event: Any): Boolean
    fun onSwipe(view: Any, x: Float, y: Float, current: Any?, next: Any?)
    fun resetSwipe(view: Any)
    fun measuredContentSize(view: Any, input: Any): IslandContentSize?
    fun safeInsets(owner: Any): SafeInsets
    fun sceneOf(owner: Any, input: Any, orientation: IslandOrientation): IslandScene
    fun minimumBigWidth(helper: Any): Int
    fun nativeResult(result: Any): NativeMeasureResult
    fun createResult(geometry: IslandResultGeometry): Any
    fun musicDemand(view: Any): MusicDemandDimensions?
    fun mediaAreasAvailable(view: Any): Boolean
    fun lyricModuleInstalled(view: Any): Boolean
    fun windowState(view: Any): Any?
    fun configChange(windowState: Any): Boolean
    fun setConfigChange(windowState: Any, value: Boolean)
    fun viewModel(view: Any): Any?
    fun updateViewState(viewModel: Any, view: Any)
    fun animationView(animator: Any): Any?
    fun pressBigIsland(animator: Any, view: Any)
    fun resetPressBigIsland(animator: Any, view: Any)
}
