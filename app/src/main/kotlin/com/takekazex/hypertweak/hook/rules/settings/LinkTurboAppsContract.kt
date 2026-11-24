package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex.Value
import org.jf.dexlib2.iface.reference.MethodReference

/**
 * Network acceleration (LinkTurbo) app list. The page task enumerates launcher activities and
 * keeps only packages contained in a HashSet copied from the client's default package list;
 * that list is the hard gate, while uid, internet and per-app switch checks stay native.
 */
internal class LinkTurboAppsContract private constructor(
    val task: MethodReference,
    val defaults: MethodReference,
) {
    companion object {
        private const val LAUNCHER = "android.intent.category.LAUNCHER"
        private const val ASYNC_TASK = "Landroid/os/AsyncTask;"
        private const val QUERY =
            "Landroid/content/pm/PackageManager;->queryIntentActivities(Landroid/content/Intent;I)Ljava/util/List;"
        private const val COPY = "Ljava/util/HashSet;-><init>(Ljava/util/Collection;)V"
        private const val CONTAINS = "Ljava/util/HashSet;->contains(Ljava/lang/Object;)Z"
        private const val ACTIVITY_INFO = "Landroid/content/pm/ActivityInfo;"

        fun resolve(index: DexOperandIndex): LinkTurboAppsContract? {
            val matches = index.strings(LAUNCHER).mapNotNull { task ->
                if (task.returnType != "Ljava/util/List;" || task.parameterTypes != listOf("[Ljava/lang/Void;")) {
                    return@mapNotNull null
                }
                if (index.classDefinition(task.definingClass)?.superclass != ASYNC_TASK) return@mapNotNull null
                val code = index.code(task)
                if (code.calls.none { DexOperandIndex.descriptor(it.method) == QUERY }) return@mapNotNull null
                // Exactly one copied gate set, sourced from a no-argument List getter on a collaborator.
                val copy = code.calls.filter { DexOperandIndex.descriptor(it.method) == COPY }
                    .singleOrNull() ?: return@mapNotNull null
                val source = copy.arguments.getOrNull(1) as? Value.Call ?: return@mapNotNull null
                val defaults = source.method
                if (defaults.parameterTypes.isNotEmpty() || defaults.returnType != "Ljava/util/List;" ||
                    defaults.definingClass == task.definingClass) return@mapNotNull null
                // The native null result aborts the task; keep that contract observable.
                if (code.branches.none { (it.value as? Value.Call)?.method == defaults }) return@mapNotNull null
                // Launcher package names are filtered by membership in that set.
                val gated = code.calls.any { call ->
                    DexOperandIndex.descriptor(call.method) == CONTAINS &&
                        (call.arguments.getOrNull(1) as? Value.Field)?.field?.let {
                            it.definingClass == ACTIVITY_INFO && it.name == "packageName"
                        } == true
                }
                if (!gated || index.definition(defaults) == null) return@mapNotNull null
                LinkTurboAppsContract(task, defaults)
            }
            return matches.singleOrNull()
        }
    }
}
