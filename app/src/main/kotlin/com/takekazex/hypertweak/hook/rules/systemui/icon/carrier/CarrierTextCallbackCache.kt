package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexSession
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/** Persistent feature cache identity. */
internal object CarrierTextCallbackFeature : DexFeature {
    override val id = "systemui.carrier_text_callback"
    override val schema = 1
}

/**
 * Compute/decode for the control-center carrier-row callback.
 *
 * Kept beside the hooker but in its own file: the hooker is large and this contract has to stay
 * readable, because it must match [ControlCenterCarrierBlockHooker]'s shape check exactly.
 */
internal object CarrierTextCallbackCache {
    private const val TARGET = "target"

    /**
     * The callback owner is the row class's nested `mCarrierTextCallback` type.
     *
     * This must stay byte-identical to [ControlCenterCarrierBlockHooker]'s own marker. Both are
     * derived from the public row class name, so a ROM rename invalidates both together instead of
     * letting the cache silently match something the hooker never installs on.
     */
    const val ROW_CLASS = "com.android.systemui.controlcenter.shade.ControlCenterCarrierText"
    const val OWNER_MARKER = ROW_CLASS + "\$mCarrierTextCallback"
    const val METHOD_NAME = "onCarrierTextChanged"

    fun descriptors(session: DexSession): Map<String, String> = session.dexKit { bridge ->
        val match = runCatching {
            bridge.findMethod {
                matcher {
                    declaredClass { className(OWNER_MARKER, StringMatchType.Contains) }
                    name(METHOD_NAME)
                    paramCount(3)
                    returnType(Void.TYPE)
                }
            }.toList()
        }.getOrDefault(emptyList())
        val single = match
            .filter { it.className.contains(OWNER_MARKER) && it.methodName == METHOD_NAME && it.paramCount == 3 }
            .map { it.descriptor }
            .distinct()
            .singleOrNull() ?: return@dexKit emptyMap()
        mapOf(TARGET to single)
    } ?: emptyMap()

    fun decode(stored: Map<String, String>, loader: ClassLoader): Method? {
        val target = stored[TARGET]?.let { MethodDescriptorCodec.method(it, loader) } ?: return null
        // The installed hook must be an instance method; a static one would hook the wrong call.
        if (java.lang.reflect.Modifier.isStatic(target.modifiers) ||
            !target.declaringClass.name.contains(OWNER_MARKER) || target.name != METHOD_NAME ||
            target.returnType != Void.TYPE || !target.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType))) return null
        return target
    }
}
