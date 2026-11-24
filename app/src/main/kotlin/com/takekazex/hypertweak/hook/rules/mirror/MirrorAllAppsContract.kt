package com.takekazex.hypertweak.hook.rules.mirror

import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference

/**
 * Mirror's own "all apps" switch for app relay. The relay allow-list predicates
 * (`isSupportSubScreen` / `isSupportSubScreenOld`) log "all app support" when a static boolean
 * getter is true. That getter reads one static field, which the config initializer fills from the
 * `support_all_app_sub_screen` preference reader when `MainService` starts.
 */
internal object MirrorAllAppsContract : DexFeature {
    override val id = "mirror.all_apps"
    override val schema = 1

    const val ALL_APPS = "isSupportSubScreen: all app support"
    const val ALL_APPS_OLD = "isSupportSubScreenOld: all app support"
    const val PREFERENCE = "support_all_app_sub_screen"
    private const val STATIC = 0x8

    /** Roles: `flag` (the static boolean) and `init` (the host initializer that recomputes it). */
    fun resolve(index: DexOperandIndex): Map<String, String> {
        val getter = getter(index, ALL_APPS)
        check(DexOperandIndex.descriptor(getter(index, ALL_APPS_OLD)) == DexOperandIndex.descriptor(getter)) {
            "allow-list predicates use different switches"
        }
        val getterCode = index.code(getter)
        check(getterCode.calls.isEmpty() && getterCode.writes.isEmpty()) { "switch getter is not a plain read" }
        val flag = getterCode.reads.single()
        check(flag.type == "Z") { "switch is not a boolean" }

        val reader = index.strings(PREFERENCE).single { method ->
            method.accessFlags and STATIC != 0 && method.returnType == "Z" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "Z")
        }
        val init = index.declared(flag.definingClass).single { method ->
            method.accessFlags and STATIC != 0 && method.returnType == "V" && method.parameterTypes.isEmpty() &&
                index.code(method).writes.any { (field, value) -> same(field, flag) && fromCall(value, reader) }
        }
        return mapOf(
            "flag" to MethodDescriptorCodec.descriptor(flag),
            "init" to DexOperandIndex.descriptor(init),
        )
    }

    private fun getter(index: DexOperandIndex, anchor: String): MethodReference {
        val predicate = index.strings(anchor).single { method ->
            method.accessFlags and STATIC != 0 && method.returnType == "Z" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
        }
        val getter = index.code(predicate).guards(anchor)
            .filterIsInstance<DexOperandIndex.Value.Call>()
            .map { it.method }
            .single { it.returnType == "Z" && it.parameterTypes.isEmpty() }
        val definition = requireNotNull(index.definition(getter)) { "switch getter is not in this APK" }
        check(definition.accessFlags and STATIC != 0) { "switch getter is not static" }
        return getter
    }

    private fun same(a: FieldReference, b: FieldReference) =
        MethodDescriptorCodec.descriptor(a) == MethodDescriptorCodec.descriptor(b)

    private fun fromCall(value: DexOperandIndex.Value?, method: MethodReference) =
        value is DexOperandIndex.Value.Call && DexOperandIndex.descriptor(value.method) == DexOperandIndex.descriptor(method)
}
