package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex.Value
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

/**
 * Screen timeout drop-down filter. On OLED panels the preference clamps its maximum to
 * `Integer.MAX_VALUE - 1`, which drops the "never" entry that the resource arrays still carry.
 * The panel flag is a static boolean of the preference class, written only by its initializer
 * from the `oled` display-type comparison, and read by the filter next to the device-admin limit.
 */
internal class NeverScreenTimeoutContract private constructor(
    val filter: MethodReference,
    val oled: FieldReference,
    val entries: MethodReference,
    val values: MethodReference,
    val setEntries: MethodReference,
    val setValues: MethodReference,
) {
    companion object {
        private const val OLED_CAP = 0x7ffffffeL
        private const val MAX_TIME_TO_LOCK =
            "Landroid/app/admin/DevicePolicyManager;->getMaximumTimeToLock(Landroid/content/ComponentName;)J"
        private const val SEQUENCE = "[Ljava/lang/CharSequence;"

        fun resolve(index: DexOperandIndex): NeverScreenTimeoutContract? {
            val matches = index.strings("oled").filter { it.name == "<clinit>" }.mapNotNull { init ->
                val owner = init.definingClass
                val flags = index.code(init).writes.map { it.first }
                    .filter { it.definingClass == owner && it.type == "Z" }
                    .distinctBy { it.name }
                val oled = flags.singleOrNull() ?: return@mapNotNull null
                val filter = index.declared(owner).singleOrNull { method ->
                    method.parameterTypes.isEmpty() && method.returnType == "V" &&
                        index.hasNumber(method, OLED_CAP) &&
                        index.code(method).let { code ->
                            code.reads.any { it.definingClass == owner && it.name == oled.name && it.type == "Z" } &&
                                code.calls.any { DexOperandIndex.descriptor(it.method) == MAX_TIME_TO_LOCK }
                        }
                } ?: return@mapNotNull null
                val calls = index.code(filter).calls.sortedBy { it.address }
                val receiverOnly = { call: DexOperandIndex.CallSite -> call.arguments.firstOrNull() == Value.Argument(-1) }
                // Library accessors called on the preference itself, in their native order:
                // labels are read before values, and replaced before values.
                val getters = calls.filter {
                    receiverOnly(it) && it.method.parameterTypes.isEmpty() && it.method.returnType == SEQUENCE
                }.map { it.method }.distinctBy(DexOperandIndex::descriptor)
                // The setters follow the filter loop, where the decoder no longer tracks the
                // receiver; bind them to the preference class or the getters' library class.
                val owners = getters.map { it.definingClass }.toSet() + owner
                val setters = calls.filter {
                    it.method.definingClass in owners &&
                        it.method.parameterTypes == listOf(SEQUENCE) && it.method.returnType == "V"
                }.map { it.method }.distinctBy(DexOperandIndex::descriptor)
                if (getters.size != 2 || setters.size != 2) return@mapNotNull null
                if (getters.map { it.name } != listOf("getEntries", "getEntryValues") ||
                    setters.map { it.name } != listOf("setEntries", "setEntryValues")) return@mapNotNull null
                NeverScreenTimeoutContract(filter, oled, getters[0], getters[1], setters[0], setters[1])
            }
            return matches.singleOrNull()
        }
    }
}
