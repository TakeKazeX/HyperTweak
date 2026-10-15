package com.takekazex.hypertweak.hook.rules.systemui.island

/** Shared runtime and target-DEX capability contract; names are from the verified unobfuscated API. */
internal object NativeIslandContract {
    val methods: Map<String, List<String>> = linkedMapOf(
        "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator" to listOf("handleEvent", "getBigIslandStateHandler", "getSmallIslandStateHandler",
            "getExpandedStateHandler", "getHiddenStateHandler", "getShowOnceIslandHandler", "getWindowView",
            "getAnimationController", "isTinyScreen", "getInConfigRebuild", "getTouchRegion", "getIslandRegion",
            "getSmallBigIslandRegion", "computeIslandRegion", "getBigIsLandAndSmallIsLandList", "hasIsland",
            "isBigOrSmallIsland", "updateTouchRegion", "dispatchSwipe", "resetSwipe", "getDefaultIslandTouchRegion"),
        "miui.systemui.dynamicisland.window.DynamicIslandWindowView" to listOf("getEventCoordinator", "onConfigChanged", "dispatchTouchEvent", "collapse", "isSwipeTowardsSmallIsland"),
        "miui.systemui.dynamicisland.event.SwipeEventCoordinator" to listOf("getDynamicIslandEventCoordinator", "handleAppEvent", "handleSwipe", "resetSwipe"),
        "miui.systemui.dynamicisland.event.handler.HiddenStateHandler" to listOf("enqueueHiddenStateCallback"),
        "miui.systemui.dynamicisland.event.handler.StateHandler" to listOf("getCurrent", "setCurrent", "getCurrentTempShow", "addState", "stop"),
        "miui.systemui.dynamicisland.event.DynamicIslandState" to listOf("changeToState", "getTempShow"),
        "miui.systemui.dynamicisland.window.content.DynamicIslandContentView" to listOf("getState", "setState", "getLastState", "setLastState", "getDynamicIslandEventCoordinator",
            "getCurrentIslandData", "getSmallIslandViewWidth", "getSmallIslandRect", "getIslandViewMarginTop", "getIslandViewHeight",
            "getSpace", "onIslandClick", "calculateBigIslandWidth", "getBigIslandViewWidth", "getIslandKey",
            "getCurrentBigIslandX", "getCurrentBigIslandWidth", "getSmallIslandView", "getBigIslandRect", "getExpandedIslandRect", "onSwipe", "resetSwipe"),
        "miui.systemui.dynamicisland.anim.ui.animator.IslandCoordinateCalculator" to listOf("getSmallIslandX"),
        "miui.systemui.dynamicisland.anim.DynamicIslandAnimationController" to listOf("getSmallIslandX"),
        "miui.systemui.dynamicisland.model.IslandContentViewCalculationParams" to listOf("getScreenWidth", "getScreenHeight", "getClockWidth", "getBatteryWidth", "getMaxWidth",
            "getSmallIslandViewWidth", "getSpace", "getCutoutWidth"),
        "miui.systemui.dynamicisland.model.IslandContentViewCalculationResult" to listOf("getBigIslandViewWidth", "getBigIslandLeftWidth", "getBigIslandRightWidth", "getBigIslandX",
            "getBigIslandMarginWidth"),
        "miui.systemui.dynamicisland.window.content.helpers.DynamicIslandContentViewPhoneHelper" to listOf("calculateBigIslandWidth"),
        "miui.systemui.dynamicisland.touch.domain.interactor.DynamicIslandTouchInteractor" to listOf("onInterceptTouchEvent", "onTouchEvent", "performClick"),
        "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData" to listOf("getExtras"),
    )
}
