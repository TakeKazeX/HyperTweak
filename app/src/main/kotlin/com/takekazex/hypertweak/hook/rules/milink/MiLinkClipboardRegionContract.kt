package com.takekazex.hypertweak.hook.rules.milink

import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex

/**
 * Universal clipboard receiver in MiLink's cross-device service. A message from a peer that is not
 * a PC/Mac is dropped unless a static `(Context, String)Z` check says the peer reports the same
 * region as this device; the drop is logged right after that check fails.
 */
internal object MiLinkClipboardRegionContract : DexFeature {
    override val id = "milink.clipboard_region"
    override val schema = 1

    const val REJECT = "peer device is not same region with this device, ignore this msg"
    private const val CONTEXT = "Landroid/content/Context;"
    private const val STRING = "Ljava/lang/String;"
    private const val TEXT_EQUALS = "Landroid/text/TextUtils;->equals(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z"
    private const val STATIC = 0x8

    /** Roles: `receiver` (the message handler, deoptimized) and `check` (the region comparison). */
    fun resolve(index: DexOperandIndex): Map<String, String> {
        val receiver = index.strings(REJECT).single { it.returnType == "V" }
        val code = index.code(receiver)
        val reject = code.strings.single { it.value == REJECT }.address
        val region = code.calls
            .filter { site -> site.address < reject && site.method.returnType == "Z" &&
                site.method.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, STRING) }
            .maxBy { it.address }.method
        val definition = requireNotNull(index.definition(region)) { "region check is not in this APK" }
        check(definition.accessFlags and STATIC != 0) { "region check is not static" }
        // Local and peer regions are read through one helper and compared as text.
        val calls = index.code(region).calls
        check(calls.any { DexOperandIndex.descriptor(it.method) == TEXT_EQUALS }) { "region check does not compare text" }
        val regionReads = calls.filter { site -> site.method.returnType == STRING &&
            site.method.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, STRING) }
        check(regionReads.size == 2 && regionReads.map { DexOperandIndex.descriptor(it.method) }.distinct().size == 1) {
            "region check does not read the local and peer region through one helper"
        }
        return mapOf(
            "receiver" to DexOperandIndex.descriptor(receiver),
            "check" to DexOperandIndex.descriptor(region),
        )
    }
}
