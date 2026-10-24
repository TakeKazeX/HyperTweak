package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexControlFlow
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.jf.dexlib2.iface.reference.FieldReference

/** A DEX-only contract shared by production discovery and target-APK tests. */
internal object UsbInstallDexContract {
    internal data class InputModeCalls(
        val falseInput: DexOperandIndex.CallSite,
        val trueInput: DexOperandIndex.CallSite,
    )

    fun inputModeCalls(
        code: DexOperandIndex.Code,
        inputFieldDescriptor: String,
        activityDescriptor: String,
        inputActivityDescriptor: String,
    ): InputModeCalls? {
        val branch = code.branches.singleOrNull { candidate ->
            val field = candidate.value as? DexOperandIndex.Value.Field
            field != null && fieldDescriptor(field.field) == inputFieldDescriptor && candidate.skipsWhenFalse
        } ?: return null
        val paths = code.edges[branch.start]?.takeIf { it.size == 2 } ?: return null
        val trueCalls = reachableCalls(paths[0], code).filter { call ->
            call.method.definingClass == inputActivityDescriptor && call.method.parameterTypes.map(Any::toString) == listOf("Z") &&
                call.method.returnType == "V"
        }.distinctBy { DexOperandIndex.descriptor(it.method) }
        val falseCalls = reachableCalls(branch.end, code).filter { call ->
            call.method.definingClass == activityDescriptor && call.method.parameterTypes.map(Any::toString) == listOf("Z") &&
                call.method.returnType == "V"
        }.distinctBy { DexOperandIndex.descriptor(it.method) }
        val trueInput = trueCalls.singleOrNull() ?: return null
        val falseInput = falseCalls.singleOrNull() ?: return null
        if (falseInput.address in reachableAddresses(paths[0], code) ||
            trueInput.address in reachableAddresses(branch.end, code)
        ) return null
        if (!edgeDominates(code, branch.start, paths[0], trueInput.address) ||
            !edgeDominates(code, branch.start, branch.end, falseInput.address)
        ) return null
        if (trueInput.arguments.lastOrNull() != DexOperandIndex.Value.Number(1) ||
            falseInput.arguments.lastOrNull() != DexOperandIndex.Value.Number(1)
        ) return null
        return InputModeCalls(falseInput, trueInput)
    }

    /** The response callback must reach the native success method only for a null response. */
    fun nullResponseCallsSuccess(
        code: DexOperandIndex.Code,
        successDescriptor: String,
    ): Boolean {
        val branch = code.branches.singleOrNull { it.value == DexOperandIndex.Value.Argument(0) && !it.skipsWhenFalse }
            ?: return false
        val paths = code.edges[branch.start]?.takeIf { it.size == 2 } ?: return false
        val successCalls = code.calls.filter { DexOperandIndex.descriptor(it.method) == successDescriptor }
        val successAddress = successCalls.singleOrNull()?.address ?: return false
        return successAddress in reachableAddresses(paths[0], code) &&
            successAddress !in reachableAddresses(branch.end, code) &&
            edgeDominates(code, branch.start, paths[0], successAddress)
    }

    /** Ensure the network entry is called only on TextUtils.isEmpty(account) == false. */
    fun entryOnlyAfterAccount(
        code: DexOperandIndex.Code,
        inputFieldDescriptor: String,
        accountGetterDescriptor: String,
        entryDescriptor: String,
    ): Boolean {
        val inputBranch = code.branches.singleOrNull { candidate ->
            val field = candidate.value as? DexOperandIndex.Value.Field
            field != null && fieldDescriptor(field.field) == inputFieldDescriptor && !candidate.skipsWhenFalse
        } ?: return false
        val inputPaths = code.edges[inputBranch.start]?.takeIf { it.size == 2 } ?: return false
        val accountGetter = code.calls.singleOrNull { DexOperandIndex.descriptor(it.method) == accountGetterDescriptor }
            ?: return false
        val isEmpty = code.calls.filter { call ->
            call.method.definingClass == "Landroid/text/TextUtils;" && call.method.name == "isEmpty" &&
                call.method.parameterTypes.map(Any::toString) == listOf("Ljava/lang/CharSequence;") &&
                call.method.returnType == "Z" && call.arguments.singleOrNull() ==
                DexOperandIndex.Value.Call(accountGetter.method, accountGetter.arguments)
        }.singleOrNull() ?: return false
        val branch = code.branches.singleOrNull { candidate ->
            val value = candidate.value as? DexOperandIndex.Value.Call
            value != null && DexOperandIndex.descriptor(value.method) == DexOperandIndex.descriptor(isEmpty.method) &&
                candidate.skipsWhenFalse
        } ?: return false
        val paths = code.edges[branch.start]?.takeIf { it.size == 2 } ?: return false
        val entry = code.calls.singleOrNull { DexOperandIndex.descriptor(it.method) == entryDescriptor } ?: return false
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
    fun writesInputFlag(code: DexOperandIndex.Code, inputFieldDescriptor: String): Boolean =
        code.writes.filter { (field, _) -> fieldDescriptor(field) == inputFieldDescriptor }.singleOrNull()?.second
            .let { value ->
                val call = value as? DexOperandIndex.Value.Call ?: return false
                DexOperandIndex.descriptor(call.method) ==
                    "Landroid/content/Intent;->getBooleanExtra(Ljava/lang/String;Z)Z" &&
                    call.arguments.takeLast(2) == listOf(DexOperandIndex.Value.Text("is_input"), DexOperandIndex.Value.Number(0))
            }

    private fun reachableAddresses(start: Int, code: DexOperandIndex.Code): Set<Int> =
        DexControlFlow.reachable(code.edges, setOf(start))
    private fun edgeDominates(code: DexOperandIndex.Code, branchAddress: Int, permittedEdge: Int, targetAddress: Int): Boolean =
        DexControlFlow.edgeDominates(code.edges, code.roots, branchAddress, permittedEdge, targetAddress)

    private fun reachableCalls(start: Int, code: DexOperandIndex.Code): List<DexOperandIndex.CallSite> =
        reachableAddresses(start, code).flatMap { address -> code.calls.filter { it.address == address } }

    private fun fieldDescriptor(field: FieldReference): String = MethodDescriptorCodec.descriptor(field)
}
