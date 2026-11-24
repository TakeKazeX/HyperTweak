package com.takekazex.hypertweak.hook.rules.misettings

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.reference.MethodReference

/**
 * MiSettings refresh-rate page. A static helper reads `FeatureParser.getIntArray("fpsList")`
 * reflectively; the page sorts that array and turns it into the `customize_fps_ddp` drop-down,
 * replacing the last label with the "maximum refresh rate" text.
 */
internal class RefreshRateListContract private constructor(
    val rates: MethodReference,
    val page: MethodReference,
) {
    companion object {
        private const val DROP_DOWN = "customize_fps_ddp"
        private const val SORT = "Ljava/util/Arrays;->sort([I)V"

        fun resolve(index: DexOperandIndex): RefreshRateListContract? {
            val rates = index.strings("fpsList", "getIntArray", "miui.util.FeatureParser").singleOrNull { method ->
                method.accessFlags and STATIC != 0 && method.parameterTypes.isEmpty() && method.returnType == "[I"
            } ?: return null
            val page = index.strings(DROP_DOWN).singleOrNull { method ->
                method.returnType == "V" && index.code(method).calls.let { calls ->
                    calls.any { DexOperandIndex.descriptor(it.method) == DexOperandIndex.descriptor(rates) } && calls.any { DexOperandIndex.descriptor(it.method) == SORT }
                }
            } ?: return null
            return RefreshRateListContract(rates, page)
        }

        private const val STATIC = 0x8
    }
}
