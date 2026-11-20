package com.takekazex.hypertweak.hook.rules.systemui.freeform

import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex.Value
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec

internal object MotionCuesResolver : DexFeature {
    override val id = "systemui.motion_cues_freeform"
    override val schema = 1

    fun resolve(index: DexOperandIndex): Map<String, String> =
        index.strings("Skipping attempt to create overlay. One already exists.")
            .mapNotNull { method ->
                if (method.parameterTypes.isNotEmpty() || method.returnType != "V" ||
                    method.accessFlags and 8 != 0
                ) return@mapNotNull null
                val code = index.code(method)
                val add = code.calls.singleOrNull {
                    DexOperandIndex.descriptor(it.method) ==
                        "Landroid/view/WindowManager;->addView(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V"
                } ?: return@mapNotNull null
                val view = add.arguments[1] as? Value.Allocation ?: return@mapNotNull null
                val params = add.arguments[2] as? Value.Allocation ?: return@mapNotNull null
                if (index.classDefinition(view.type)?.superclass != "Landroid/view/View;") return@mapNotNull null
                val layoutParams = "Landroid/view/WindowManager\$LayoutParams;"
                if (params.type != layoutParams) return@mapNotNull null
                val expectedLayout = listOf(params) + listOf(-1L, -1L, 2020L, 0x38L, -2L).map { Value.Number(it) }
                if (code.calls.none {
                        it.address < add.address && it.arguments == expectedLayout &&
                            DexOperandIndex.descriptor(it.method) == "$layoutParams-><init>(IIIII)V"
                    } || code.calls.none {
                        it.address < add.address && it.arguments == listOf(params) &&
                            DexOperandIndex.descriptor(it.method) == "$layoutParams->setTrustedOverlay()V"
                    }
                ) return@mapNotNull null
                val field = code.writes.singleOrNull { (field, value) ->
                    value == view && field.type == view.type && field.definingClass == method.definingClass
                }?.first ?: return@mapNotNull null
                val fieldKey = MethodDescriptorCodec.descriptor(field)
                if (code.reads.none { MethodDescriptorCodec.descriptor(it) == fieldKey }) return@mapNotNull null
                val create = DexOperandIndex.descriptor(method)
                val start = caller(index, "Starting motion bubbles overlay.", create)
                val reconfigure = caller(index, "Recreating overlay due to orientation/layout change.", create)
                mapOf("create" to create, "overlay" to fieldKey, "start" to start, "reconfigure" to reconfigure)
            }.single()

    private fun caller(index: DexOperandIndex, log: String, target: String): String =
        DexOperandIndex.descriptor(index.strings(log).single { method ->
            index.code(method).calls.any { DexOperandIndex.descriptor(it.method) == target }
        })
}
