package com.takekazex.hypertweak.hook.rules.systemui.island

/** A scheduling lease is not a host transition: preserve the pending notification request. */
internal class IslandEventFields(state: Class<*>) {
    private val fields = listOf(
        "Expanded" to Boolean::class.javaPrimitiveType!!,
        "TempShow" to Boolean::class.javaPrimitiveType!!,
        "UpdateKey" to String::class.java,
        "UpdateOrder" to Boolean::class.javaObjectType,
        "DeleteKey" to String::class.java,
        "DeleteNoAnimation" to Boolean::class.javaPrimitiveType!!,
    ).map { (name, type) ->
        val getter = NativeIslandMembers.named(state, "get$name", emptyList())
        check(getter.returnType == type)
        getter to NativeIslandMembers.named(state, "set$name", listOf(type))
    }

    fun transfer(source: Any, target: Any) {
        fields.forEach { (get, set) -> set.invoke(target, get.invoke(source)) }
    }
}
