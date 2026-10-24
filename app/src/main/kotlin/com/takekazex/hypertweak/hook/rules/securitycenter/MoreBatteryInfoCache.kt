package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexSession
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Persistent feature cache identity. */
internal object MoreBatteryInfoFeature : DexFeature {
    override val id = "securitycenter.more_battery_info"
    override val schema = 1
}

/**
 * Compute/decode for the four battery read methods.
 *
 * Each target is an independent zero-argument static method identified by a literal string, so the
 * whole set reduces to four descriptors. Kept in its own file because the hooker is large and this
 * contract has to stay readable beside the hook that consumes it.
 */
internal object MoreBatteryInfoCache {
    private const val MANUFACTURING = "manufacturing_date"
    private const val FIRST_USAGE = "first_usage_date"
    private const val FULL_CAPACITY = "getBatteryChargeFull"
    private const val DESIGN_CAPACITY = "charge_full_design"

    private const val KEY_MANUFACTURING = "manufacturing"
    private const val KEY_FIRST_USAGE = "first_usage"
    private const val KEY_FULL_CAPACITY = "full_capacity"
    private const val KEY_DESIGN_CAPACITY = "design_capacity"

    fun descriptors(session: DexSession): Map<String, String> = session.dexKit { bridge ->
        fun find(anchor: String, returnType: String): String? = runCatching {
            bridge.findMethod {
                matcher {
                    paramCount(0)
                    addUsingString(anchor, StringMatchType.Equals)
                }
            }.toList()
                .filter { it.isMethod && Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == returnType }
                .map { it.descriptor }
                .distinct()
                .singleOrNull()
        }.getOrNull()

        buildMap {
            find(MANUFACTURING, "java.lang.String")?.let { put(KEY_MANUFACTURING, it) }
            find(FIRST_USAGE, "java.lang.String")?.let { put(KEY_FIRST_USAGE, it) }
            find(FULL_CAPACITY, "int")?.let { put(KEY_FULL_CAPACITY, it) }
            find(DESIGN_CAPACITY, "int")?.let { put(KEY_DESIGN_CAPACITY, it) }
        }
    } ?: emptyMap()

    /**
     * Decodes the stored set.
     *
     * A miss on one target leaves that read unavailable rather than invalidating the rest: the
     * four capacities are independent features and a ROM that drops one must not disable the others.
     */
    fun decode(stored: Map<String, String>, loader: ClassLoader): MoreBatteryInfoHooker.BatteryReadMethods? {
        fun method(key: String, expected: Class<*>): Method? = stored[key]?.let { descriptor ->
            MethodDescriptorCodec.method(descriptor, loader)?.takeIf { candidate ->
                Modifier.isStatic(candidate.modifiers) && candidate.parameterCount == 0 && candidate.returnType == expected
            }
        }
        val result = MoreBatteryInfoHooker.BatteryReadMethods(
            manufacturingDate = method(KEY_MANUFACTURING, String::class.java),
            firstUsageDate = method(KEY_FIRST_USAGE, String::class.java),
            fullCapacity = method(KEY_FULL_CAPACITY, Int::class.javaPrimitiveType!!),
            designCapacity = method(KEY_DESIGN_CAPACITY, Int::class.javaPrimitiveType!!),
        )
        // An entry that stored nothing usable must be a miss so the next run rescans.
        val any = result.manufacturingDate ?: result.firstUsageDate
            ?: result.fullCapacity ?: result.designCapacity
        return if (any == null) null else result
    }
}
