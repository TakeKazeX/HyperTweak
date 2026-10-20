package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import org.jf.dexlib2.iface.reference.FieldReference

/** A DEX-only contract shared by production discovery and target-APK tests. */
internal object UsbInstallDexContract {
    internal data class InputModeCalls(
        val falseInput: CameraDexIndex.CallSite,
        val trueInput: CameraDexIndex.CallSite,
    )

    fun inputModeCalls(
        code: CameraDexIndex.Code,
        inputFieldDescriptor: String,
        activityDescriptor: String,
        inputActivityDescriptor: String,
    ): InputModeCalls? {
        val branch = code.branches.singleOrNull { candidate ->
            val field = candidate.value as? CameraDexIndex.Value.Field
            field != null && fieldDescriptor(field.field) == inputFieldDescriptor && candidate.skipsWhenFalse
        } ?: return null
        val paths = code.edges[branch.start]?.takeIf { it.size == 2 } ?: return null
        val trueCalls = reachableCalls(paths[0], code).filter { call ->
            call.method.definingClass == inputActivityDescriptor && call.method.parameterTypes.map(Any::toString) == listOf("Z") &&
                call.method.returnType == "V"
        }.distinctBy { CameraDexIndex.descriptor(it.method) }
        val falseCalls = reachableCalls(branch.end, code).filter { call ->
            call.method.definingClass == activityDescriptor && call.method.parameterTypes.map(Any::toString) == listOf("Z") &&
                call.method.returnType == "V"
        }.distinctBy { CameraDexIndex.descriptor(it.method) }
        val trueInput = trueCalls.singleOrNull() ?: return null
        val falseInput = falseCalls.singleOrNull() ?: return null
        if (falseInput.address in reachableAddresses(paths[0], code) ||
            trueInput.address in reachableAddresses(branch.end, code)
        ) return null
        if (!edgeDominates(code, branch.start, paths[0], trueInput.address) ||
            !edgeDominates(code, branch.start, branch.end, falseInput.address)
        ) return null
        if (trueInput.arguments.lastOrNull() != CameraDexIndex.Value.Number(1) ||
            falseInput.arguments.lastOrNull() != CameraDexIndex.Value.Number(1)
        ) return null
        return InputModeCalls(falseInput, trueInput)
    }

    /** The response callback must reach the native success method only for a null response. */
    fun nullResponseCallsSuccess(
        code: CameraDexIndex.Code,
        successDescriptor: String,
    ): Boolean {
        val branch = code.branches.singleOrNull { it.value == CameraDexIndex.Value.Argument(0) && !it.skipsWhenFalse }
            ?: return false
        val paths = code.edges[branch.start]?.takeIf { it.size == 2 } ?: return false
        val successCalls = code.calls.filter { CameraDexIndex.descriptor(it.method) == successDescriptor }
        val successAddress = successCalls.singleOrNull()?.address ?: return false
        return successAddress in reachableAddresses(paths[0], code) &&
            successAddress !in reachableAddresses(branch.end, code) &&
            edgeDominates(code, branch.start, paths[0], successAddress)
    }

    /** Ensure the network entry is called only on TextUtils.isEmpty(account) == false. */
    fun entryOnlyAfterAccount(
        code: CameraDexIndex.Code,
        inputFieldDescriptor: String,
        accountGetterDescriptor: String,
        entryDescriptor: String,
    ): Boolean {
        val inputBranch = code.branches.singleOrNull { candidate ->
            val field = candidate.value as? CameraDexIndex.Value.Field
            field != null && fieldDescriptor(field.field) == inputFieldDescriptor && !candidate.skipsWhenFalse
        } ?: return false
        val inputPaths = code.edges[inputBranch.start]?.takeIf { it.size == 2 } ?: return false
        val accountGetter = code.calls.singleOrNull { CameraDexIndex.descriptor(it.method) == accountGetterDescriptor }
            ?: return false
        val isEmpty = code.calls.filter { call ->
            call.method.definingClass == "Landroid/text/TextUtils;" && call.method.name == "isEmpty" &&
                call.method.parameterTypes.map(Any::toString) == listOf("Ljava/lang/CharSequence;") &&
                call.method.returnType == "Z" && call.arguments.singleOrNull() ==
                CameraDexIndex.Value.Call(accountGetter.method, accountGetter.arguments)
        }.singleOrNull() ?: return false
        val branch = code.branches.singleOrNull { candidate ->
            val value = candidate.value as? CameraDexIndex.Value.Call
            value != null && CameraDexIndex.descriptor(value.method) == CameraDexIndex.descriptor(isEmpty.method) &&
                candidate.skipsWhenFalse
        } ?: return false
        val paths = code.edges[branch.start]?.takeIf { it.size == 2 } ?: return false
        val entry = code.calls.singleOrNull { CameraDexIndex.descriptor(it.method) == entryDescriptor } ?: return false
        val falseInputPath = inputPaths[0]
        val trueInputPath = inputBranch.end
        val falseInputReachable = reachableAddresses(falseInputPath, code)
        if (branch.start !in falseInputReachable || entry.address !in reachableAddresses(trueInputPath, code)) return false
        // Keep every original entry/exception root. Only constrain the known input flag to false;
        // starting traversal after that branch would overlook a new shortcut before it.
        val falseModeCode = code.copy(edges = code.edges + (inputBranch.start to listOf(falseInputPath)))
        return entry.address in reachableAddresses(paths[1], code) &&
            entry.address !in reachableAddresses(paths[0], code) &&
            edgeDominates(falseModeCode, branch.start, paths[1], entry.address)
    }

    /** The host stores the is_input extra into the exact boolean field read by its success path. */
    fun writesInputFlag(code: CameraDexIndex.Code, inputFieldDescriptor: String): Boolean =
        code.writes.filter { (field, _) -> fieldDescriptor(field) == inputFieldDescriptor }.singleOrNull()?.second
            .let { value ->
                val call = value as? CameraDexIndex.Value.Call ?: return false
                CameraDexIndex.descriptor(call.method) ==
                    "Landroid/content/Intent;->getBooleanExtra(Ljava/lang/String;Z)Z" &&
                    call.arguments.takeLast(2) == listOf(CameraDexIndex.Value.Text("is_input"), CameraDexIndex.Value.Number(0))
            }

    private fun reachableAddresses(start: Int, code: CameraDexIndex.Code): Set<Int> {
        val pending = java.util.ArrayDeque<Int>()
        val seen = LinkedHashSet<Int>()
        pending.add(start)
        while (pending.isNotEmpty()) {
            val address = pending.removeFirst()
            if (!seen.add(address)) continue
            code.edges[address].orEmpty().forEach(pending::add)
        }
        return seen
    }

    /** True only when every route from every DEX root to target uses the selected branch edge. */
    private fun edgeDominates(
        code: CameraDexIndex.Code,
        branchAddress: Int,
        permittedEdge: Int,
        targetAddress: Int,
    ): Boolean = edgeDominatesFrom(code, code.roots, branchAddress, permittedEdge, targetAddress)

    private fun edgeDominatesFrom(
        code: CameraDexIndex.Code,
        roots: Set<Int>,
        branchAddress: Int,
        permittedEdge: Int,
        targetAddress: Int,
    ): Boolean {
        val pending = java.util.ArrayDeque<Int>()
        val seen = HashSet<Int>()
        roots.forEach(pending::add)
        while (pending.isNotEmpty()) {
            val address = pending.removeFirst()
            if (address == targetAddress) return false
            if (!seen.add(address)) continue
            code.edges[address].orEmpty().filterNot { address == branchAddress && it == permittedEdge }
                .forEach(pending::add)
        }
        return true
    }

    private fun reachableCalls(start: Int, code: CameraDexIndex.Code): List<CameraDexIndex.CallSite> =
        reachableAddresses(start, code).flatMap { address -> code.calls.filter { it.address == address } }

    private fun fieldDescriptor(field: FieldReference): String = "${field.definingClass}->${field.name}:${field.type}"
}
