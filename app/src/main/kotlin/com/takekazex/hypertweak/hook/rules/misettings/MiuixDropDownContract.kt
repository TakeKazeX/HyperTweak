package com.takekazex.hypertweak.hook.rules.misettings

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

/**
 * The host's bundled Miuix drop-down, as `miuix.preference.DropDownPreference` builds it.
 *
 * The preference creates a double-line content adapter (entries in the field its `getCount`
 * measures) and wraps it in a checkable drop-down adapter `(Context, layout, content, isChecked)`
 * whose layout renders the selected value inside the row. A `miuix.appcompat.widget.Spinner`
 * with that adapter opens the HyperOS drop-down menu.
 */
internal class MiuixDropDownContract private constructor(
    val wrapper: MethodReference,
    val checkedCallback: String,
    val layout: FieldReference,
    val content: FieldReference,
    val entries: FieldReference,
) {
    companion object {
        const val PREFERENCE = "Lmiuix/preference/DropDownPreference;"
        const val SPINNER = "Lmiuix/appcompat/widget/Spinner;"
        private const val ARRAY_ADAPTER = "Landroid/widget/ArrayAdapter;"
        private const val INTERFACE = 0x200

        fun resolve(index: DexOperandIndex): MiuixDropDownContract? {
            val constructors = index.declared(PREFERENCE).filter { it.name == "<init>" }
            val built = constructors.mapNotNull { constructor ->
                val code = index.code(constructor)
                val call = code.calls.singleOrNull { call ->
                    val types = call.method.parameterTypes.map(CharSequence::toString)
                    call.method.name == "<init>" && types.size == 4 && types[0] == "Landroid/content/Context;" &&
                        types[1] == "I" && types[2] == ARRAY_ADAPTER && isCheckedCallback(index, types[3])
                } ?: return@mapNotNull null
                val layout = (call.arguments.getOrNull(2) as? DexOperandIndex.Value.Field)?.field
                    ?.takeIf { it.type == "I" } ?: return@mapNotNull null
                val content = code.writes.map { it.first }
                    .filter { it.definingClass == PREFERENCE && it.type == ARRAY_ADAPTER }
                    .distinctBy(::key).singleOrNull() ?: return@mapNotNull null
                Triple(call.method, layout, content)
            }.distinctBy { DexOperandIndex.descriptor(it.first) }
            val (wrapper, layout, content) = built.singleOrNull() ?: return null
            val contentType = index.declared(SPINNER).mapNotNull { method ->
                method.parameterTypes.singleOrNull()?.toString()?.takeIf {
                    method.returnType == "V" && it != ARRAY_ADAPTER && extendsArrayAdapter(index, it)
                }
            }.distinct().singleOrNull() ?: return null
            val count = index.declared(contentType).singleOrNull { it.name == "getCount" && it.parameterTypes.isEmpty() && it.returnType == "I" }
                ?: return null
            val entries = index.code(count).reads.filter { it.definingClass == contentType && it.type == "[Ljava/lang/CharSequence;" }
                .distinctBy(::key).singleOrNull() ?: return null
            return MiuixDropDownContract(wrapper, wrapper.parameterTypes[3].toString(), layout, content, entries)
        }

        private fun isCheckedCallback(index: DexOperandIndex, type: String): Boolean {
            val definition = index.classDefinition(type) ?: return false
            if (definition.accessFlags and INTERFACE == 0) return false
            val methods = index.declared(type)
            return methods.size == 1 && methods[0].returnType == "Z" && methods[0].parameterTypes.map(CharSequence::toString) == listOf("I")
        }

        private fun extendsArrayAdapter(index: DexOperandIndex, type: String): Boolean {
            var current: String? = type
            repeat(4) {
                val superclass = index.classDefinition(current ?: return false)?.superclass ?: return false
                if (superclass == ARRAY_ADAPTER) return true
                current = superclass
            }
            return false
        }

        private fun key(field: FieldReference) = "${field.definingClass}->${field.name}:${field.type}"
    }
}
