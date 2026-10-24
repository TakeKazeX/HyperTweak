package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexSession
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Persistent feature cache identity. */
internal object WarningCountdownFeature : DexFeature {
    override val id = "securitycenter.warning_countdown"
    override val schema = 1
}

/**
 * Compute/decode for the shared confirm method.
 *
 * The contract must stay identical to [WarningCountdownHooker]'s own shape check: a public
 * non-static `(Z)V` that uses both -1 and 0. The decode step re-validates it, so a renamed or
 * reshaped target invalidates the entry instead of hooking the wrong call.
 */
internal object WarningCountdownCache {
    private const val TARGET = "target"
    private const val BASE_FRAGMENT = "com.miui.permcenter.privacymanager.InterceptBaseFragment"

    fun descriptors(session: DexSession): Map<String, String> = session.dexKit { bridge ->
        val match = runCatching {
            bridge.findMethod {
                matcher {
                    declaredClass(BASE_FRAGMENT, StringMatchType.Equals)
                    returnType("void")
                    paramTypes("boolean")
                    usingNumbers(-1, 0)
                    modifiers(Modifier.PUBLIC)
                }
            }.toList()
        }.getOrDefault(emptyList())
        val single = match.singleOrNull() ?: return@dexKit emptyMap()
        mapOf(TARGET to single.descriptor)
    } ?: emptyMap()

    fun decode(stored: Map<String, String>, loader: ClassLoader): Method? {
        val method = stored[TARGET]?.let { MethodDescriptorCodec.method(it, loader) } ?: return null
        return method.takeIf {
            it.declaringClass.name == BASE_FRAGMENT && it.returnType == Void.TYPE &&
                Modifier.isPublic(it.modifiers) && it.parameterCount == 1 &&
                it.parameterTypes[0] == java.lang.Boolean.TYPE &&
                !Modifier.isStatic(it.modifiers)
        }
    }
}
