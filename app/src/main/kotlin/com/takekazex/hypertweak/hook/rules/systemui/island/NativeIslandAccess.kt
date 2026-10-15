package com.takekazex.hypertweak.hook.rules.systemui.island

import java.lang.reflect.Method

/** Resolve once, before installation. No member lookup occurs on animation/touch callbacks. */
internal class NativeIslandAccess(load: (String) -> Class<*>) {
    private val root = "miui.systemui.dynamicisland."
    val coordinator = load(root + "event.DynamicIslandEventCoordinator")
    val content = load(root + "window.content.DynamicIslandContentView")
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
    private val members = HashMap<Pair<Class<*>, String>, Method>()
    private fun resolve(owner: Class<*>, name: String): Method {
        val candidates = generateSequence(owner) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
            .filter { it.name == name }.distinctBy { it.parameterTypes.toList() }.toList()
        return candidates.single().apply { isAccessible = true }
    }
    fun method(owner: Class<*>, name: String): Method = members.getValue(owner to name)
    fun call(owner: Any, name: String, vararg args: Any?): Any? {
        val type = when {
            coordinator.isInstance(owner) -> coordinator
            swipe.isInstance(owner) -> swipe
            window.isInstance(owner) -> window
            content.isInstance(owner) -> content
            handler.isInstance(owner) -> handler
            state.isInstance(owner) -> state
            coords.isInstance(owner) -> coords
            anim.isInstance(owner) -> anim
            params.isInstance(owner) -> params
            result.isInstance(owner) -> result
            data.isInstance(owner) -> data
            else -> error("Unresolved island owner ${owner.javaClass.name}")
        }
        return method(type, name).invoke(owner, *args)
    }
    fun number(owner: Any, name: String): Int = (call(owner, name) as Number).toInt()
    val hiddenList = coordinator.getDeclaredField("hiddenList").apply { isAccessible = true }
    val coordsView = coords.getDeclaredField("view").apply { isAccessible = true }
    val touchView = touch.getDeclaredField("windowView").apply { isAccessible = true }
    val window = load(root + "window.DynamicIslandWindowView")
    val windowCoordinator = resolve(window, "getEventCoordinator")
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
    fun transition(view: Any, target: Class<*>) = call(view, "setState", nextState(view, target))
}
