package com.takekazex.hypertweak.hook.rules.systemui.island.host

import java.lang.reflect.Method

/** Resolve once, before installation. No member lookup occurs on animation/touch callbacks. */
internal class NativeIslandAccess(private val discovery: NativeIslandDiscovery) {
    private fun load(name: String): Class<*> = discovery.classes.getValue(name)
    private val root = "miui.systemui.dynamicisland."
    val templateWrite = discovery.templateWrite
    val widthTween = discovery.entries.getValue("widthTween")
    val widthSetTo = NativeIslandMembers.named(widthTween.declaringClass, "setTo", widthTween.parameterTypes.toList()).also {
        check(it.returnType == widthTween.returnType)
    }
    val coordinator = load(root + "event.DynamicIslandEventCoordinator")
    val content = load(root + "window.content.DynamicIslandContentView")
    val fake = load(root + "window.content.DynamicIslandContentFakeView")
    val viewModel = load(root + "window.DynamicIslandViewModel")
    val windowState = load(root + "window.DynamicIslandWindowState")
    val base = load(root + "window.content.DynamicIslandBaseContentView")
    val state = load(root + "event.DynamicIslandState")
    val small = load(root + "event.DynamicIslandState\$SmallIsland")
    val hidden = load(root + "event.DynamicIslandState\$Hidden")
    val big = load(root + "event.DynamicIslandState\$BigIsland")
    val swipe = load(root + "event.SwipeEventCoordinator")
    val swipeLeft = load(root + "event.DynamicIslandEvent\$SwipeLeft")
    val swipeRight = load(root + "event.DynamicIslandEvent\$SwipeRight")
    val handler = load(root + "event.handler.StateHandler")
    val hiddenHandler = load(root + "event.handler.HiddenStateHandler")
    val touch = load(root + "touch.domain.interactor.DynamicIslandTouchInteractor")
    val coords = load(root + "anim.ui.animator.IslandCoordinateCalculator")
    val anim = load(root + "anim.DynamicIslandAnimationController")
    val helper = load(root + "window.content.helpers.DynamicIslandContentViewPhoneHelper")
    val params = load(root + "model.IslandContentViewCalculationParams")
    val result = load(root + "model.IslandContentViewCalculationResult")
    val event = load(root + "event.DynamicIslandEvent")
    val data = load("com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData")
    val resolver = load(root + "anim.domain.interactor.AnimTransitionResolver")
    val delegate = load(root + "anim.DynamicIslandAnimationDelegate")
    val fakeAnimator = load(root + "anim.ui.animator.FakeViewAnimator")
    val fakeHelper = load(root + "anim.ui.animator.FakeViewAnimHelper")
    val windowController = load(root + "window.DynamicIslandWindowViewController")
    val gestureAnimator = load(root + "anim.ui.animator.IslandGestureAnimator")
    val updater = load(root + "anim.ui.animator.IslandPropertyUpdater")
    val properties = load(root + "anim.model.IslandAnimProperties")
    // Removing module text measurement relies on this native premeasurement contract.
    private val nativePreMeasure = NativeIslandMembers.named(content, "applyPreMeasureMode",
        listOf(android.view.ViewGroup::class.java, Boolean::class.javaPrimitiveType!!)).also {
        check(it.returnType == Boolean::class.javaPrimitiveType)
    }
    private val members = HashMap<Pair<Class<*>, String>, Method>()
    private fun resolve(owner: Class<*>, name: String): Method {
        val semantic = when {
            owner == coordinator && name == "handleEvent" -> "transaction"
            owner == handler && name == "stop" -> "publication"
            owner == content && name == "calculateBigIslandWidth" -> "contentMeasure"
            owner == helper && name == "calculateBigIslandWidth" -> "phoneMeasure"
            owner == anim && name == "onStateChange" -> "animation"
            owner == swipe && name == "handleSwipe" -> "swipe"
            owner == resolver && name == "resolve" -> "transitions"
            else -> null
        }
        if (semantic != null) return discovery.entries.getValue(semantic)
        if (name == "updateBigIslandView" || name == "updateSmallIslandView") return owner.declaredMethods.single { it.name == name &&
            it.parameterTypes.take(2) == listOf(data, Boolean::class.javaPrimitiveType) && it.parameterCount == 3 }.apply { isAccessible = true }
        val f = Float::class.javaPrimitiveType!!
        val z = Boolean::class.javaPrimitiveType!!
        val parameters = when (name) {
            "doBigIslandAnim" -> listOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, f,
                android.widget.FrameLayout::class.java, android.widget.FrameLayout::class.java, android.widget.LinearLayout::class.java,
                android.widget.FrameLayout::class.java, content, z, Int::class.javaPrimitiveType!!)
            "updateBigIslandLayoutWithAnim" -> listOf(android.widget.FrameLayout::class.java, android.widget.FrameLayout::class.java,
                android.widget.LinearLayout::class.java, android.widget.FrameLayout::class.java, content, z)
            "updateState" -> listOf(content)
            "updateDynamicIslandView" -> listOf(data, z, z)
            "setSmallIslandViewWidth" -> listOf(Int::class.javaPrimitiveType!!)
            "setUserExpanded" -> listOf(z)
            "setConfigChange" -> listOf(Boolean::class.javaObjectType)
            "setFakeSecondLayout" -> listOf(android.widget.FrameLayout::class.java)
            "handleAppEvent" -> listOf(event, content, ArrayList::class.java)
            "dispatchSwipe" -> listOf(f, f, z)
            "resetSwipe" -> when (owner) {
                coordinator -> listOf(f, f)
                swipe -> listOf(f, f, ArrayList::class.java)
                else -> listOf(content)
            }
            "onSwipe" -> listOf(f, f, content, state, state)
            "enqueueHiddenStateCallback" -> listOf(content, Boolean::class.javaObjectType)
            "setCurrent" -> listOf(content)
            "getAnimator" -> listOf(base)
            "updateBigIsland" -> listOf(properties)
            "onPressSmallIsland", "onPressBigIsland", "resetPressBigIsland" -> listOf(content)
            "addState" -> listOf(content, state)
            "setState", "setLastState", "changeToState" -> listOf(state)
            "getSmallIslandRect" -> listOf(Int::class.javaPrimitiveType!!)
            "getCurrentBigIslandX", "getCurrentBigIslandWidth", "getBigIslandRect" -> listOf(Boolean::class.javaObjectType)
            "hasIsland", "collapse" -> listOf(String::class.java)
            "isBigOrSmallIsland" -> listOf(base)
            "isSwipeTowardsSmallIsland" -> listOf(f)
            "onConfigChanged" -> listOf(android.content.res.Configuration::class.java)
            "dispatchTouchEvent" -> listOf(android.view.MotionEvent::class.java)
            "onInterceptTouchEvent", "onTouchEvent" -> listOf(android.view.MotionEvent::class.java, String::class.java)
            else -> emptyList()
        }
        return NativeIslandMembers.named(owner, name, parameters)
    }
    fun method(owner: Class<*>, name: String): Method = members.getValue(owner to name)

    /**
     * Concrete host classes are disjoint; resolve each class once instead of re-running the
     * instance chain on every animation/touch callback.
     */
    private val ownerTypes = java.util.concurrent.ConcurrentHashMap<Class<*>, Class<*>>()
    private fun typeOf(owner: Any, name: String): Class<*> {
        ownerTypes[owner.javaClass]?.let { return it }
        val type = when {
            coordinator.isInstance(owner) -> coordinator
            swipe.isInstance(owner) -> swipe
            window.isInstance(owner) -> window
            content.isInstance(owner) -> content
            fake.isInstance(owner) -> fake
            viewModel.isInstance(owner) -> viewModel
            windowState.isInstance(owner) -> windowState
            handler.isInstance(owner) -> handler
            state.isInstance(owner) -> state
            coords.isInstance(owner) -> coords
            anim.isInstance(owner) -> anim
            params.isInstance(owner) -> params
            result.isInstance(owner) -> result
            properties.isInstance(owner) -> properties
            data.isInstance(owner) -> data
            else -> error("Unresolved island owner ${owner.javaClass.name} for $name")
        }
        ownerTypes[owner.javaClass] = type
        return type
    }
    fun call(owner: Any, name: String, vararg args: Any?): Any? =
        method(typeOf(owner, name), name).invoke(owner, *args)
    fun number(owner: Any, name: String): Int = (call(owner, name) as Number).toInt()
    fun ceilDimension(owner: Any, name: String): Int = kotlin.math.ceil((call(owner, name) as Number).toDouble()).toInt()
    val hiddenList = NativeIslandMembers.field(coordinator, ArrayList::class.java)
    val coordsView = NativeIslandMembers.field(coords, base)
    val gestureView = NativeIslandMembers.field(gestureAnimator, base)
    val updaterView = NativeIslandMembers.field(updater, base)
    val window = load(root + "window.DynamicIslandWindowView")
    val touchView = NativeIslandMembers.field(touch, window)
    val windowCoordinator = resolve(window, "getEventCoordinator")
    private val eventFields = IslandEventFields(state)
    val smallConstructor = small.getDeclaredConstructor()
    val hiddenConstructor = hidden.getDeclaredConstructor()
    val bigConstructor = big.getDeclaredConstructor()
    val resultConstructor = result.getDeclaredConstructor(*Array(9) { Int::class.javaPrimitiveType!! })
    val minimumBig = resolve(helper, "getBigIslandMinWidth")

    init {
        fun add(type: Class<*>, vararg names: String) {
            names.forEach { members[type to it] = resolve(type, it) }
        }
        NativeIslandContract.methods.forEach { (name, names) ->
            add(load(name), *names.toTypedArray())
        }
        add(anim, "onStateChange")
        add(resolver, "resolve")
        add(gestureAnimator, "onPressSmallIsland", "onPressBigIsland", "resetPressSmallIsland", "resetPressBigIsland")
    }

    @Suppress("UNCHECKED_CAST")
    fun queue(owner: Any): MutableList<Any> = hiddenList.get(owner) as MutableList<Any>
    fun current(owner: Any, slot: String): Any? = call(call(owner, slot)!!, "getCurrent")
    fun nextState(view: Any, target: Class<*>): Any {
        val next = when (target) {
            small -> smallConstructor.newInstance()
            big -> bigConstructor.newInstance()
            else -> hiddenConstructor.newInstance()
        }
        val previous = call(view, "getState")
        return previous?.let { call(it, "changeToState", next) } ?: next
    }
    fun lendToHidden(view: Any) {
        val previous = call(view, "getState")
        val hiddenState = nextState(view, hidden)
        if (previous != null) eventFields.transfer(previous, hiddenState)
        call(view, "setState", hiddenState)
    }
    /** Preserve native event metadata on a render-only state copy. */
    fun renderBig(source: Any?): Any = bigConstructor.newInstance().also { target ->
        if (source != null) {
            method(state, "changeToState").invoke(source, target)
            eventFields.transfer(source, target)
        }
    }
    fun transition(view: Any, target: Class<*>) = call(view, "setState", nextState(view, target))
}
