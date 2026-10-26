package com.takekazex.hypertweak.hook.rules.systemui.island.host

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandScene
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MusicDemandDimensions
import com.takekazex.hypertweak.hook.rules.systemui.island.model.NativeMeasureResult
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SafeInsets
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.InvocationTargetException

/** Adapter from the narrow coordinator ports to the pre-resolved native registry. */
internal class NativeIslandHost(val access: NativeIslandAccess) : IslandHostPorts {
    @Volatile private var lyricModulePresent: Boolean? = null

    override fun ownerOf(content: Any): Any? = try {
        access.call(content, "getDynamicIslandEventCoordinator")
    } catch (error: Throwable) {
        DebugLog.e(TAG, "resolve content owner", error)
        null
    }

    override fun windowOf(owner: Any): Any? = access.call(owner, "getWindowView")

    override fun orientationOf(owner: Any): IslandOrientation {
        val window = windowOf(owner) as? View ?: return IslandOrientation.UNDEFINED
        return when (window.resources.configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> IslandOrientation.PORTRAIT
            Configuration.ORIENTATION_LANDSCAPE -> IslandOrientation.LANDSCAPE
            else -> IslandOrientation.UNDEFINED
        }
    }

    override fun smallestScreenWidthDp(owner: Any): Int {
        val window = windowOf(owner) as? View ?: return Int.MAX_VALUE
        return window.resources.configuration.smallestScreenWidthDp
    }

    override fun isTinyScreen(owner: Any): Boolean = access.call(owner, "isTinyScreen") == true

    override fun inConfigRebuild(owner: Any): Boolean = access.call(owner, "getInConfigRebuild") == true

    override fun showOnce(owner: Any): Any? = access.current(owner, "getShowOnceIslandHandler")

    override fun tempShow(handler: Any): Any? = access.call(handler, "getCurrentTempShow")

    override fun current(owner: Any, slot: IslandSlot): Any? = when (slot) {
        IslandSlot.BIG -> access.current(owner, "getBigIslandStateHandler")
        IslandSlot.SMALL -> access.current(owner, "getSmallIslandStateHandler")
        IslandSlot.EXPANDED -> access.current(owner, "getExpandedStateHandler")
        IslandSlot.HIDDEN -> access.current(owner, "getHiddenStateHandler")
        IslandSlot.SHOW_ONCE -> access.current(owner, "getShowOnceIslandHandler")
    }

    /** The stable native slot handler, as opposed to [current]'s current task. */
    override fun handler(owner: Any, slot: IslandSlot): Any? = when (slot) {
        IslandSlot.BIG -> access.call(owner, "getBigIslandStateHandler")
        IslandSlot.SMALL -> access.call(owner, "getSmallIslandStateHandler")
        IslandSlot.EXPANDED -> access.call(owner, "getExpandedStateHandler")
        IslandSlot.HIDDEN -> access.call(owner, "getHiddenStateHandler")
        IslandSlot.SHOW_ONCE -> access.call(owner, "getShowOnceIslandHandler")
    }

    override fun queue(owner: Any): MutableList<Any> = access.queue(owner)

    override fun key(view: Any): String? = access.call(view, "getIslandKey") as? String

    override fun state(view: Any): Any? = access.call(view, "getState")

    override fun stateKind(view: Any): IslandStateKind = when {
        access.small.isInstance(state(view)) -> IslandStateKind.SMALL
        access.big.isInstance(state(view)) -> IslandStateKind.BIG
        access.hidden.isInstance(state(view)) -> IslandStateKind.HIDDEN
        else -> IslandStateKind.OTHER
    }

    override fun isContent(view: Any): Boolean = access.content.isInstance(view)

    override fun isFake(view: Any): Boolean = access.fake.isInstance(view)

    override fun isCoordinator(value: Any): Boolean = access.coordinator.isInstance(value)

    override fun isSmallState(value: Any?): Boolean = access.small.isInstance(value)

    override fun realContent(view: Any): Any? = try {
        when {
            isContent(view) -> view
            isFake(view) -> access.call(view, "getRealView")
            else -> null
        }
    } catch (error: Throwable) {
        DebugLog.e(TAG, "resolve native template owner", error)
        null
    }

    override fun nativeSmallWidth(view: Any): Int = access.number(view, "getSmallIslandViewWidth")

    override fun number(owner: Any, member: String): Int = access.number(owner, member)

    override fun ceilDimension(owner: Any, member: String): Int = access.ceilDimension(owner, member)

    override fun call(owner: Any, member: String, vararg args: Any?): Any? = access.call(owner, member, *args)

    override fun nextState(view: Any, slot: IslandSlot): Any {
        val target = when (slot) {
            IslandSlot.SMALL -> access.small
            IslandSlot.BIG -> access.big
            IslandSlot.HIDDEN -> access.hidden
            else -> error("Unsupported transition slot $slot")
        }
        return access.nextState(view, target)
    }

    override fun setState(view: Any, state: Any?) = access.call(view, "setState", state).let { }

    override fun setLastState(view: Any, state: Any?) = access.call(view, "setLastState", state).let { }

    override fun lendToHidden(view: Any) = access.lendToHidden(view)

    override fun renderBig(source: Any?): Any = access.renderBig(source)

    override fun setCurrent(handler: Any, view: Any?) = access.call(handler, "setCurrent", view).let { }

    override fun addState(handler: Any, view: Any, state: Any?) =
        access.call(handler, "addState", view, state).let { }

    override fun stop(handler: Any) = access.call(handler, "stop").let { }

    override fun enqueueHidden(handler: Any, view: Any) {
        access.method(access.hiddenHandler, "enqueueHiddenStateCallback").invoke(handler, view, true)
    }

    override fun updateTouchRegion(owner: Any) = access.call(owner, "updateTouchRegion").let { }

    override fun userExpanded(owner: Any): Boolean = access.call(owner, "getUserExpanded") == true

    override fun setUserExpanded(owner: Any, value: Boolean) =
        access.call(owner, "setUserExpanded", value).let { }

    override fun animationController(owner: Any): Any? = access.call(owner, "getAnimationController")

    override fun windowCoordinator(window: Any): Any? = try {
        access.windowCoordinator.invoke(window)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }

    override fun swipeCoordinator(coordinator: Any): Any? =
        access.call(coordinator, "getDynamicIslandEventCoordinator")

    override fun fakeView(view: Any): Any? = access.call(view, "getFakeView")

    override fun templateAreas(view: Any): List<TemplateAreaPair> {
        val pairs = ArrayList<TemplateAreaPair>(2)
        val left = access.call(view, "getBigIslandAreaLeft") as? View
        val right = access.call(view, "getBigIslandAreaRight") as? View
        if (left != null && right != null) pairs += TemplateAreaPair(left, right)
        fakeView(view)?.let { fake ->
            val fakeLeft = access.call(fake, "getFakeFirstLayout") as? View
            val fakeRight = access.call(fake, "getFakeSecondLayout") as? View
            if (fakeLeft != null && fakeRight != null) pairs += TemplateAreaPair(fakeLeft, fakeRight)
        }
        return pairs
    }

    override fun fixedCutoutWidth(view: Any): Int = access.number(view, "getFixedCutoutWidth")

    override fun bigIslandView(view: Any): Any? = access.call(view, "getBigIslandView")

    override fun packageName(view: Any): String? {
        val data = access.call(view, "getCurrentIslandData") ?: return null
        val extras = access.call(data, "getExtras") as? Bundle ?: return null
        return extras.getString("miui.pkg.name")
    }

    override fun smallRect(view: Any, x: Int): HostRect? = (access.call(view, "getSmallIslandRect", x) as? Rect)?.toHost()

    override fun bigRect(view: Any): HostRect? =
        (access.call(view, "getBigIslandRect", null) as? Rect)?.toHost()

    override fun expandedRect(view: Any): HostRect? =
        (access.call(view, "getExpandedIslandRect") as? Rect)?.toHost()

    override fun isSwipeTowardsSmall(window: Any, x: Float): Boolean =
        access.call(window, "isSwipeTowardsSmallIsland", x) == true

    override fun touchAction(event: Any): Int = (event as android.view.MotionEvent).actionMasked

    override fun touchX(event: Any): Float = (event as android.view.MotionEvent).x

    override fun touchY(event: Any): Float = (event as android.view.MotionEvent).y

    override fun isSwipeRight(event: Any): Boolean = access.swipeRight.isInstance(event)

    override fun isSwipeLeft(event: Any): Boolean = access.swipeLeft.isInstance(event)

    override fun onSwipe(view: Any, x: Float, y: Float, current: Any?, next: Any?) =
        access.call(view, "onSwipe", x, y, view, current, next).let { }

    override fun resetSwipe(view: Any) = access.call(view, "resetSwipe", view).let { }

    override fun measuredContentSize(view: Any, input: Any): IslandContentSize? =
        IslandContentSize.measured(
            access.number(input, "getBigIslandAreaLeftWidth"),
            access.number(input, "getBigIslandAreaRightWidth"),
            access.number(view, "getIslandViewHeight"),
        )

    override fun safeInsets(owner: Any): SafeInsets {
        val window = windowOf(owner) as? View ?: return SafeInsets.NONE
        val cutout = window.rootWindowInsets?.displayCutout ?: return SafeInsets.NONE
        return SafeInsets(cutout.safeInsetLeft, cutout.safeInsetRight)
    }

    override fun sceneOf(owner: Any, input: Any, orientation: IslandOrientation): IslandScene = IslandScene(
        screenWidth = access.number(input, "getScreenWidth"),
        screenHeight = access.number(input, "getScreenHeight"),
        clockWidth = access.ceilDimension(input, "getClockWidth"),
        batteryWidth = access.ceilDimension(input, "getBatteryWidth"),
        gap = access.number(input, "getSpace"),
        cutoutWidth = access.number(input, "getCutoutWidth"),
        maxWidth = access.number(input, "getMaxWidth"),
        safe = safeInsets(owner),
        orientation = orientation,
    )

    override fun minimumBigWidth(helper: Any): Int =
        (access.minimumBig.invoke(helper) as Number).toInt()

    override fun nativeResult(result: Any): NativeMeasureResult = NativeMeasureResult(
        bigWidth = access.number(result, "getBigIslandViewWidth"),
        bigWidthWithSmall = access.number(result, "getBigIslandViewWidthHasSmallIsland"),
        bigXWithSmall = access.number(result, "getBigIslandXHasSmallIsland"),
        marginWidth = access.number(result, "getBigIslandMarginWidth"),
    )

    override fun createResult(geometry: IslandResultGeometry): Any = access.resultConstructor.newInstance(
        geometry.width, geometry.left, geometry.right, geometry.x, geometry.margin,
        geometry.viewWidth, geometry.viewLeft, geometry.viewRight, geometry.viewX,
    )

    override fun musicDemand(view: Any): MusicDemandDimensions? = try {
        val root = view as View
        fun dimension(name: String): Int {
            val id = root.resources.getIdentifier(name, "dimen", "miui.systemui.plugin")
            check(id != 0) { "Missing native music dimension $name" }
            return root.resources.getDimensionPixelSize(id)
        }
        MusicDemandDimensions(
            dimension("island_area_padding"),
            dimension("island_fix_icon_size"),
            dimension("island_fix_icon_margin"),
            dimension("island_area_padding_cutout"),
        )
    } catch (error: Throwable) {
        DebugLog.w(TAG, "native music dimensions unavailable", error)
        null
    }

    override fun mediaAreasAvailable(view: Any): Boolean = try {
        access.call(view, "getBigIslandAreaLeft") is View && access.call(view, "getBigIslandAreaRight") is View
    } catch (error: Throwable) {
        DebugLog.w(TAG, "native media areas unavailable", error)
        false
    }

    override fun lyricModuleInstalled(view: Any): Boolean {
        lyricModulePresent?.let { return it }
        val present = runCatching {
            val context = (view as View).context
            context.packageManager.getPackageInfo(
                "com.juren233.hyperlyricsenhanced",
                PackageManager.PackageInfoFlags.of(0),
            )
            true
        }.getOrDefault(false)
        lyricModulePresent = present
        return present
    }

    override fun windowState(view: Any): Any? = access.call(view, "getWindowState")

    override fun configChange(windowState: Any): Boolean = access.call(windowState, "getConfigChange") == true

    override fun setConfigChange(windowState: Any, value: Boolean) =
        access.call(windowState, "setConfigChange", value).let { }

    override fun viewModel(view: Any): Any? = access.call(view, "getViewModel")

    override fun updateViewState(viewModel: Any, view: Any) =
        access.call(viewModel, "updateState", view).let { }

    override fun animationView(animator: Any): Any? = try {
        access.gestureView.get(animator)
    } catch (error: Throwable) {
        DebugLog.e(TAG, "resolve gesture animator view", error)
        null
    }

    override fun pressBigIsland(animator: Any, view: Any) {
        access.method(access.gestureAnimator, "onPressBigIsland").invoke(animator, view)
    }

    override fun resetPressBigIsland(animator: Any, view: Any) {
        access.method(access.gestureAnimator, "resetPressBigIsland").invoke(animator, view)
    }

    private fun Rect.toHost() = HostRect(left, top, right, bottom)

    private companion object { const val TAG = "ThreeIslands" }
}
