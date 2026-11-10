package com.takekazex.hypertweak.hook.rules.securitycenter.battery

import android.os.Handler
import android.os.Message
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Finds the charge-protection page's refresh handler from the fragment's own shape.
 *
 * The obfuscated nested name is not stable (`$c` in 13.2.x, `$d` in 13.8.0, where `$c` became a
 * Runnable). The fragment holds exactly one instance field whose type is a Handler subclass that
 * declares `handleMessage(Message)`; anything else is rejected rather than guessed.
 */
internal object ChargeProtectHandlerContract {
    fun resolve(fragmentClass: Class<*>): Method? = fragmentClass.declaredFields.asSequence()
        .filterNot { Modifier.isStatic(it.modifiers) }
        .map { it.type }
        .filter { it != Handler::class.java && Handler::class.java.isAssignableFrom(it) }
        .distinct()
        .mapNotNull { type -> runCatching { type.getDeclaredMethod("handleMessage", Message::class.java) }.getOrNull() }
        .toList()
        .singleOrNull()
}
