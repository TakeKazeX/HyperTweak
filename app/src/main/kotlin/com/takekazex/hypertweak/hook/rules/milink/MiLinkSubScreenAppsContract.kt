package com.takekazex.hypertweak.hook.rules.milink

import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex

/**
 * MiLink's local app-cast (sub-screen) allow list. The class logging `ML::SubScreenApps` loads two
 * package maps from resources; a static `(String)Z` member test rejects empty names and looks the
 * package up in one of them. Cast-mode UI, the TV cast handler and the sub-screen manager ask it
 * whether the foreground app may be cast on its own.
 */
internal object MiLinkSubScreenAppsContract : DexFeature {
    override val id = "milink.sub_screen_apps"
    override val schema = 1

    private const val TAG = "ML::SubScreenApps"
    private const val IS_EMPTY = "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z"
    private const val STATIC = 0x8

    /** Roles: `member` (the package test) and `callers` (deoptimized so the hook is reached). */
    fun resolve(index: DexOperandIndex): Map<String, String> {
        val owner = index.strings(TAG).map { it.definingClass }.distinct().single()
        val member = index.declared(owner).single { method ->
            val calls = index.code(method).calls
            method.accessFlags and STATIC != 0 && method.returnType == "Z" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                calls.any { DexOperandIndex.descriptor(it.method) == IS_EMPTY } &&
                calls.count { it.method.name == "containsKey" && it.method.returnType == "Z" } >= 1
        }
        val key = DexOperandIndex.descriptor(member)
        val callers = index.callers(listOf(key)).map(DexOperandIndex::descriptor).sorted()
        check(callers.isNotEmpty()) { "sub-screen allow list has no caller" }
        return mapOf("member" to key, "callers" to callers.joinToString("\n"))
    }
}
