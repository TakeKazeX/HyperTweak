package com.takekazex.hypertweak.hook.rules.systemui.island.host

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** R8 can remove MemberClasses metadata while preserving a callback in a method prototype. */
internal object NativeMediaFlipEntry {
    fun resolve(owner: Class<*>): Method {
        val candidates = owner.declaredMethods.filter { method ->
            val parameters = method.parameterTypes
            method.name == "startFlipAnimation" && !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE && parameters.size == 4 &&
                parameters[0].name == "android.view.View" &&
                parameters[1] == Boolean::class.javaPrimitiveType &&
                parameters[2] == Boolean::class.javaPrimitiveType &&
                parameters[3].isInterface && parameters[3].declaredMethods.singleOrNull()?.let {
                    it.name == "onFlip" && it.parameterCount == 0 && it.returnType == Void.TYPE
                } == true
        }
        check(candidates.size == 1) {
            "Native cover flip entry: expected one (View, boolean, boolean, onFlip callback), found ${candidates.size} in ${owner.name}"
        }
        return candidates.single().apply { isAccessible = true }
    }
}
