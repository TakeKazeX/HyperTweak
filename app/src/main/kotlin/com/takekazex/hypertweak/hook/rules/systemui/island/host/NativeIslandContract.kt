package com.takekazex.hypertweak.hook.rules.systemui.island.host

/** Shared runtime and target-DEX capability contract; names are from the verified unobfuscated API. */
internal object NativeIslandContract {
    val methods: Map<String, List<String>> = linkedMapOf(
        "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator" to listOf("handleEvent", "getBigIslandStateHandler", "getSmallIslandStateHandler",
            "getExpandedStateHandler", "getHiddenStateHandler", "getShowOnceIslandHandler", "getWindowView",
            "getAnimationController", "getUserExpanded", "setUserExpanded", "isTinyScreen", "getInConfigRebuild", "getTouchRegion", "getIslandRegion",
            "getSmallBigIslandRegion", "computeIslandRegion", "getBigIsLandAndSmallIsLandList", "hasIsland",
            "isBigOrSmallIsland", "updateTouchRegion", "dispatchSwipe", "resetSwipe", "getDefaultIslandTouchRegion"),
        "miui.systemui.dynamicisland.window.DynamicIslandWindowView" to listOf("getEventCoordinator", "updateDynamicIslandView", "onConfigChanged", "dispatchTouchEvent", "collapse", "isSwipeTowardsSmallIsland"),
        "miui.systemui.dynamicisland.event.SwipeEventCoordinator" to listOf("getDynamicIslandEventCoordinator", "handleAppEvent", "handleSwipe", "resetSwipe"),
        "miui.systemui.dynamicisland.event.handler.HiddenStateHandler" to listOf("enqueueHiddenStateCallback"),
        "miui.systemui.dynamicisland.event.handler.StateHandler" to listOf("getCurrent", "setCurrent", "getCurrentTempShow", "addState", "stop"),
        "miui.systemui.dynamicisland.event.DynamicIslandState" to listOf("changeToState", "getTempShow"),
        "miui.systemui.dynamicisland.window.content.DynamicIslandContentView" to listOf("getState", "setState", "getLastState", "setLastState", "getDynamicIslandEventCoordinator",
            "getCurrentIslandData", "setSmallIslandViewWidth", "updateSmallIslandView", "getSmallIslandViewWidth", "getSmallIslandRect", "getIslandViewMarginTop", "getIslandViewHeight",
            "getViewModel", "getWindowState", "doBigIslandAnim", "updateBigIslandLayoutWithAnim", "initBigIslandView", "updateBigIslandView", "updateBigIslandViewWidth", "getSpace", "onIslandClick", "calculateBigIslandWidth", "getBigIslandViewWidth", "getIslandKey",
            "getCurrentBigIslandX", "getCurrentBigIslandWidth", "getSmallIslandView", "getBigIslandView", "getBigIslandAreaLeft", "getBigIslandAreaRight", "getFakeView", "getFixedCutoutWidth", "getBigIslandRect", "getExpandedIslandRect", "onSwipe", "resetSwipe"),
        "miui.systemui.dynamicisland.window.DynamicIslandViewModel" to listOf("updateState"),
        "miui.systemui.dynamicisland.window.DynamicIslandWindowState" to listOf("getConfigChange", "setConfigChange"),
        "miui.systemui.dynamicisland.window.content.DynamicIslandContentFakeView" to listOf("getRealView", "getSmallIslandViewWidth", "getIslandViewHeight", "setSmallIslandViewWidth", "getFakeSmallIsland", "updateSmallIslandView", "getFakeFirstLayout", "getFakeSecondLayout", "setFakeSecondLayout", "updateBigIslandView"),
        "miui.systemui.dynamicisland.anim.ui.animator.IslandPropertyUpdater" to listOf("updateBigIsland"),
        "miui.systemui.dynamicisland.anim.model.IslandAnimProperties" to listOf("getContainerClipStart", "getContainerClipEnd", "getBigIslandAlpha", "getBigIslandTransX"),
        "miui.systemui.dynamicisland.anim.ui.animator.IslandCoordinateCalculator" to listOf("getSmallIslandX"),
        "miui.systemui.dynamicisland.anim.DynamicIslandAnimationController" to listOf("getSmallIslandX", "onStateChange", "getAnimator"),
        "miui.systemui.dynamicisland.anim.DynamicIslandAnimationDelegate" to listOf("getCoords", "getGestureAnimator"),
        "miui.systemui.dynamicisland.anim.domain.interactor.AnimTransitionResolver" to listOf("resolve"),
        "miui.systemui.dynamicisland.anim.ui.animator.IslandGestureAnimator" to listOf("onPressSmallIsland", "onPressBigIsland", "resetPressSmallIsland", "resetPressBigIsland"),
        "miui.systemui.dynamicisland.model.IslandContentViewCalculationParams" to listOf("getScreenWidth", "getScreenHeight", "getClockWidth", "getBatteryWidth", "getMaxWidth",
            "getSmallIslandViewWidth", "getSpace", "getCutoutWidth", "getBigIslandAreaLeftWidth", "getBigIslandAreaRightWidth"),
        "miui.systemui.dynamicisland.model.IslandContentViewCalculationResult" to listOf("getBigIslandViewWidth", "getBigIslandLeftWidth", "getBigIslandRightWidth", "getBigIslandX",
            "getBigIslandMarginWidth", "getBigIslandXHasSmallIsland", "getBigIslandViewWidthHasSmallIsland"),
        "miui.systemui.dynamicisland.window.content.helpers.DynamicIslandContentViewPhoneHelper" to listOf("calculateBigIslandWidth"),
        "miui.systemui.dynamicisland.touch.domain.interactor.DynamicIslandTouchInteractor" to listOf("onInterceptTouchEvent", "onTouchEvent", "performClick"),
        "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData" to listOf("getExtras"),
    )
}
