package com.takekazex.hypertweak.hook.rules.systemui.notification

import com.takekazex.hypertweak.hook.base.cachedDeclaredField
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Never mutate an immutable model already published in StateFlow; its equality gates emission. */
internal class NotificationModelCopy(private val type: Class<*>, private val flag: Field) {
    private val fields = generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }.flatMap { it.declaredFields.asSequence() }.toList().filterNot { Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
    private val unsafe = sun.misc.Unsafe::class.java.cachedDeclaredField("theUnsafe", accessible = true).let {
        it.get(null) as sun.misc.Unsafe
    }
    fun withFlag(model: Any, value: Boolean): Any {
        if (flag.getBoolean(model) == value) return model
        val copy = unsafe.allocateInstance(type)
        fields.forEach { it.set(copy, it.get(model)) }
        flag.setBoolean(copy, value)
        return copy
    }
}
