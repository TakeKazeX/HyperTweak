package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.base.DexKitManager
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Semantic entry points, shared with the real-DEX regression. No ROM/version table. */
internal object NativeIslandAnchors {
    val strings = mapOf(
        "transaction" to listOf("handleEventStart: ", "handleEventStop: "),
        "publication" to listOf("StateHandler stop->updateState, listSize="),
        "contentMeasure" to listOf("calculateBigIslandWidth error "),
        "phoneMeasure" to listOf("calculateBigIslandWidth measuredBigIslandWithSmallWidth:"),
        "animation" to listOf("resolved transitions: ", "view?.bigIslandViewWidth == 0"),
        "swipe" to listOf("swipe on expanded state. Only expanded view handles swipe"),
        "touch" to listOf("receiving down event."),
        "transitions" to listOf("unhandled lastState: ", "no transition for "),
        "widthTween" to listOf("FolmeState.to tag with oneTimeConfig"),
    )

    // Platform primitives are stable; host types are derived from the matched call graph.
    fun matches(role: String, parameters: List<String>, result: String): Boolean = when (role) {
        "widthTween" -> parameters.size == 2 && parameters[0] == "java.lang.Object" &&
            parameters[1].startsWith("[L") && result != "void"
        "transaction" -> parameters.size == 2 && result == "void"
        "publication", "contentMeasure" -> parameters.isEmpty() && result == "void"
        "phoneMeasure" -> parameters.size == 1 && result != "void"
        "animation" -> parameters.size == 1 && result == "void"
        "swipe" -> parameters == listOf("float", "float", "boolean", "java.util.ArrayList") && result == "void"
        "touch" -> parameters == listOf("android.view.MotionEvent", "java.lang.String") && result == "java.lang.Boolean"
        "transitions" -> parameters.size == 3 && result == "java.util.List"
        else -> false
    }

    fun <T> select(role: String, candidates: List<T>, parameters: (T) -> List<String>, result: (T) -> String): T =
        candidates.filter { matches(role, parameters(it), result(it)) }.distinct().single()
}

/**
 * DexKit locates semantic methods; their parameter/return relationships locate host owners.
 * Remaining public Kotlin API names are validated by NativeIslandAccess. Renamed semantic
 * entries and private fields are supported, but an unknown public API is never guessed.
 */
internal class NativeIslandDiscovery private constructor(val classes: Map<String, Class<*>>, val entries: Map<String, Method>) {
    companion object {
        fun scan(apk: String, loader: ClassLoader): NativeIslandDiscovery =
            DexKitManager.withBridge(apk) { bridge -> resolve { role -> query(bridge, loader, role) } }
                ?: error("Three-island DexKit contract unavailable")

        private fun query(bridge: DexKitBridge, loader: ClassLoader, role: String): List<Method> =
            bridge.findMethod { matcher { usingStrings(*NativeIslandAnchors.strings.getValue(role).toTypedArray()) } }
                .mapNotNull { data ->
                    // DexKit's lazy ClassData traversal for host config arrays is unnecessary.
                    // Resolve the matched descriptor with platform reflection, then shape-check it.
                    val method = if (role == "widthTween") NativeIslandMembers.widthTween(loader, data.descriptor)
                        else data.getMethodInstance(loader)
                    method.takeIf { NativeIslandAnchors.matches(role, it.parameterTypes.map(Class<*>::getName), it.returnType.name) }
                }

        internal fun resolve(query: (String) -> List<Method>): NativeIslandDiscovery {
            fun entry(role: String) = NativeIslandAnchors.select(role, query(role).filter { !Modifier.isStatic(it.modifiers) },
                { it.parameterTypes.map(Class<*>::getName) }, { it.returnType.name }).apply { isAccessible = true }
            val entries = NativeIslandAnchors.strings.keys.associateWith(::entry)
            fun getter(owner: Class<*>, name: String): Class<*> = NativeIslandMembers.named(owner, name, emptyList()).returnType
            val transaction = entries.getValue("transaction")
            val coordinator = transaction.declaringClass
            val content = transaction.parameterTypes[1]
            check(entries.getValue("contentMeasure").declaringClass == content)
            val state = getter(content, "getState")
            val event = transaction.parameterTypes[0]
            val hiddenHandler = getter(coordinator, "getHiddenStateHandler")
            val handler = entries.getValue("publication").declaringClass
            check(handler.isAssignableFrom(hiddenHandler))
            val animation = getter(coordinator, "getAnimationController")
            check(entries.getValue("animation").declaringClass == animation)
            val delegate = NativeIslandMembers.named(animation, "getAnimator", listOf(content.superclass)).returnType
            val gesture = getter(delegate, "getGestureAnimator")
            val updater = delegate.declaredFields.map { it.type }.distinct().single { type ->
                type.declaredMethods.any { it.name == "updateBigIsland" && it.parameterCount == 1 && it.returnType == Void.TYPE } &&
                    type.declaredMethods.any { it.name == "updateContainer" && it.parameterCount == 1 && it.returnType == Void.TYPE }
            }
            val properties = updater.declaredMethods.single { it.name == "updateBigIsland" && it.parameterCount == 1 }.parameterTypes.single()
            val measure = entries.getValue("phoneMeasure")
            val root = "miui.systemui.dynamicisland."
            val classes = linkedMapOf(
                "native.widthTween" to entries.getValue("widthTween").declaringClass,
                root + "event.DynamicIslandEventCoordinator" to coordinator,
                root + "window.content.DynamicIslandContentView" to content,
                root + "window.content.DynamicIslandBaseContentView" to content.superclass,
                root + "window.content.DynamicIslandContentFakeView" to getter(content, "getFakeView"),
                root + "window.DynamicIslandViewModel" to getter(content, "getViewModel"),
                root + "window.DynamicIslandWindowState" to getter(content, "getWindowState"),
                root + "event.DynamicIslandState" to state,
                root + "event.DynamicIslandEvent" to event,
                root + "event.handler.HiddenStateHandler" to hiddenHandler,
                root + "event.handler.StateHandler" to handler,
                root + "window.DynamicIslandWindowView" to getter(coordinator, "getWindowView"),
                root + "anim.DynamicIslandAnimationController" to animation,
                root + "anim.DynamicIslandAnimationDelegate" to delegate,
                root + "anim.ui.animator.IslandCoordinateCalculator" to getter(delegate, "getCoords"),
                root + "window.content.helpers.DynamicIslandContentViewPhoneHelper" to measure.declaringClass,
                root + "model.IslandContentViewCalculationParams" to measure.parameterTypes.single(),
                root + "model.IslandContentViewCalculationResult" to measure.returnType,
                root + "event.SwipeEventCoordinator" to entries.getValue("swipe").declaringClass,
                root + "touch.domain.interactor.DynamicIslandTouchInteractor" to entries.getValue("touch").declaringClass,
                "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData" to getter(content, "getCurrentIslandData"),
                root + "anim.domain.interactor.AnimTransitionResolver" to entries.getValue("transitions").declaringClass,
                root + "anim.ui.animator.IslandGestureAnimator" to gesture,
                root + "anim.ui.animator.IslandPropertyUpdater" to updater,
                root + "anim.model.IslandAnimProperties" to properties,
            )
            // These are the host's public state/event vocabulary, not obfuscated owner paths.
            listOf("SmallIsland", "BigIsland", "Hidden").forEach { name ->
                classes[root + "event.DynamicIslandState\$$name"] = state.declaredClasses.single { it.simpleName == name }
            }
            listOf("SwipeLeft", "SwipeRight").forEach { name ->
                classes[root + "event.DynamicIslandEvent\$$name"] = event.declaredClasses.single { it.simpleName == name }
            }
            check(entries.getValue("transitions").parameterTypes.take(2) == listOf(state, state))
            return NativeIslandDiscovery(classes, entries)
        }
    }
}

internal object NativeIslandMembers {
    fun widthTween(loader: ClassLoader, descriptor: String): Method {
        val owner = descriptor.substringBefore(";->").removePrefix("L").replace('/', '.')
        val name = descriptor.substringAfter(";->").substringBefore('(')
        return loader.loadClass(owner).declaredMethods.single { method ->
            method.name == name && NativeIslandAnchors.matches("widthTween",
                method.parameterTypes.map(Class<*>::getName), method.returnType.name)
        }.apply { isAccessible = true }
    }

    fun named(owner: Class<*>, name: String, parameters: List<Class<*>>): Method =
        generateSequence(owner) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
            .filter { it.name == name && it.parameterTypes.toList() == parameters && !Modifier.isStatic(it.modifiers) }
            .distinctBy { it.parameterTypes.toList() }.single().apply { isAccessible = true }

    fun field(owner: Class<*>, type: Class<*>) = owner.declaredFields
        .filter { it.type == type && !Modifier.isStatic(it.modifiers) }.single().apply { isAccessible = true }
}
