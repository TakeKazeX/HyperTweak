package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UsbInstallDexContractTest {
    private val activity = "Lcom/miui/permcenter/install/AdbInstallVerifyActivity;"
    private val inputActivity = "Lcom/miui/permcenter/install/AdbInputApplyActivity;"
    private val inputField = "$activity->renamedInputMode:Z"

    private fun method(owner: String, name: String, parameters: List<String>, result: String) =
        ImmutableMethodReference(owner, name, parameters, result)

    private fun code(
        calls: List<DexOperandIndex.CallSite>,
        branches: List<DexOperandIndex.Branch>,
        edges: Map<Int, List<Int>>,
        writes: List<Pair<org.jf.dexlib2.iface.reference.FieldReference, DexOperandIndex.Value?>> = emptyList(),
        roots: Set<Int> = setOf(0),
    ) = DexOperandIndex.Code(calls, emptyList(), branches, emptyList(), writes, emptyList(), edges, roots)

    @Test fun `renamed success methods resolve from the input field and setter graph`() {
        val field = ImmutableFieldReference(activity, "renamedInputMode", "Z")
        val inputSetter = method(inputActivity, "z9", listOf("Z"), "V")
        val usbSetter = method(activity, "q4", listOf("Z"), "V")
        val dexCode = code(
            calls = listOf(
                DexOperandIndex.CallSite(1, inputSetter, listOf(DexOperandIndex.Value.Number(1))),
                DexOperandIndex.CallSite(10, usbSetter, listOf(DexOperandIndex.Value.Argument(-1), DexOperandIndex.Value.Number(1))),
            ),
            branches = listOf(DexOperandIndex.Branch(0, 10, DexOperandIndex.Value.Field(field), skipsWhenFalse = true)),
            edges = mapOf(0 to listOf(1, 10), 1 to emptyList(), 10 to emptyList()),
        )
        val resolved = UsbInstallDexContract.inputModeCalls(dexCode, inputField, activity, inputActivity)
        assertEquals("q4", resolved?.falseInput?.method?.name)
        assertEquals("z9", resolved?.trueInput?.method?.name)
        assertEquals(DexOperandIndex.Value.Number(1), resolved?.falseInput?.arguments?.last())
    }

    @Test fun `missing and ambiguous input branches fail closed`() {
        val field = ImmutableFieldReference(activity, "renamedInputMode", "Z")
        val setter = method(activity, "a1", listOf("Z"), "V")
        val inputSetter = method(inputActivity, "b2", listOf("Z"), "V")
        val branch = DexOperandIndex.Branch(0, 10, DexOperandIndex.Value.Field(field), skipsWhenFalse = true)
        val missing = code(
            listOf(DexOperandIndex.CallSite(1, inputSetter, listOf(DexOperandIndex.Value.Number(1)))),
            listOf(branch), mapOf(0 to listOf(1, 10), 1 to emptyList(), 10 to emptyList()),
        )
        assertNull(UsbInstallDexContract.inputModeCalls(missing, inputField, activity, inputActivity))

        val ambiguous = code(
            listOf(
                DexOperandIndex.CallSite(1, inputSetter, listOf(DexOperandIndex.Value.Number(1))),
                DexOperandIndex.CallSite(10, setter, listOf(DexOperandIndex.Value.Number(1))),
                DexOperandIndex.CallSite(11, method(activity, "c3", listOf("Z"), "V"), listOf(DexOperandIndex.Value.Number(1))),
            ),
            listOf(branch), mapOf(0 to listOf(1, 10), 1 to emptyList(), 10 to listOf(11), 11 to emptyList()),
        )
        assertNull(UsbInstallDexContract.inputModeCalls(ambiguous, inputField, activity, inputActivity))

        val crossed = code(
            listOf(
                DexOperandIndex.CallSite(1, inputSetter, listOf(DexOperandIndex.Value.Number(1))),
                DexOperandIndex.CallSite(10, setter, listOf(DexOperandIndex.Value.Number(1))),
            ),
            listOf(branch), mapOf(0 to listOf(1, 10), 1 to listOf(10), 10 to emptyList()),
        )
        assertNull(UsbInstallDexContract.inputModeCalls(crossed, inputField, activity, inputActivity))
    }

    @Test fun `null callback contract uses argument branch instead of obfuscated names`() {
        val success = method(activity, "renamedSuccess", emptyList(), "V")
        val callbackCode = code(
            listOf(DexOperandIndex.CallSite(1, success, listOf(DexOperandIndex.Value.Argument(-1)))),
            listOf(DexOperandIndex.Branch(0, 10, DexOperandIndex.Value.Argument(0), skipsWhenFalse = false)),
            mapOf(0 to listOf(1, 10), 1 to emptyList(), 10 to emptyList()),
        )
        assertTrue(UsbInstallDexContract.nullResponseCallsSuccess(callbackCode, DexOperandIndex.descriptor(success)))
        val ambiguous = callbackCode.copy(branches = callbackCode.branches + callbackCode.branches.single())
        assertFalse(UsbInstallDexContract.nullResponseCallsSuccess(ambiguous, DexOperandIndex.descriptor(success)))
        val bypassed = callbackCode.copy(
            edges = callbackCode.edges + (20 to listOf(1)),
            roots = setOf(0, 20),
        )
        assertFalse(UsbInstallDexContract.nullResponseCallsSuccess(bypassed, DexOperandIndex.descriptor(success)))
    }

    @Test fun `account entry is reachable only after the nonempty account branch`() {
        val field = ImmutableFieldReference(activity, "renamedInputMode", "Z")
        val getter = method(activity, "accountLookupRenamed", listOf("Landroid/content/Context;"), "Ljava/lang/String;")
        val empty = method("Landroid/text/TextUtils;", "isEmpty", listOf("Ljava/lang/CharSequence;"), "Z")
        val entry = method(activity, "networkDialogRenamed", emptyList(), "V")
        val intentExtra = method("Landroid/content/Intent;", "getBooleanExtra", listOf("Ljava/lang/String;", "Z"), "Z")
        val intentRead = DexOperandIndex.Value.Call(intentExtra, listOf(DexOperandIndex.Value.Argument(0), DexOperandIndex.Value.Text("is_input"), DexOperandIndex.Value.Number(0)))
        val getAccountSite = DexOperandIndex.CallSite(2, getter, listOf(DexOperandIndex.Value.Argument(-1), DexOperandIndex.Value.Argument(0)))
        val emptyArgs = listOf(DexOperandIndex.Value.Call(getter, getAccountSite.arguments))
        val emptySite = DexOperandIndex.CallSite(4, empty, emptyArgs)
        val code = code(
            listOf(getAccountSite, emptySite, DexOperandIndex.CallSite(10, entry, listOf(DexOperandIndex.Value.Argument(-1)))),
            listOf(
                DexOperandIndex.Branch(0, 10, DexOperandIndex.Value.Field(field), skipsWhenFalse = false),
                DexOperandIndex.Branch(6, 9, DexOperandIndex.Value.Call(empty, emptyArgs), skipsWhenFalse = true),
            ),
            mapOf(0 to listOf(1, 10), 1 to listOf(2), 2 to listOf(3), 3 to listOf(4), 4 to listOf(5),
                5 to listOf(6), 6 to listOf(7, 9), 7 to emptyList(), 9 to listOf(10), 10 to emptyList()),
            writes = listOf(field to intentRead),
        )
        assertTrue(UsbInstallDexContract.writesInputFlag(code, inputField))
        assertFalse(UsbInstallDexContract.writesInputFlag(code, "$activity->anotherBoolean:Z"))
        assertTrue(UsbInstallDexContract.entryOnlyAfterAccount(code, inputField, DexOperandIndex.descriptor(getter), DexOperandIndex.descriptor(entry)))
        val unguarded = code.copy(edges = code.edges + (5 to listOf(10)))
        assertFalse(UsbInstallDexContract.entryOnlyAfterAccount(unguarded, inputField, DexOperandIndex.descriptor(getter), DexOperandIndex.descriptor(entry)))
        val bypassed = code.copy(edges = code.edges + (20 to listOf(10)), roots = setOf(0, 20))
        assertFalse(UsbInstallDexContract.entryOnlyAfterAccount(bypassed, inputField, DexOperandIndex.descriptor(getter), DexOperandIndex.descriptor(entry)))
        val shortcutBeforeInputBranch = code.copy(
            calls = code.calls.map { it.copy(address = it.address + 100) },
            branches = code.branches.map { it.copy(start = it.start + 100, end = it.end + 100) },
            edges = code.edges.mapKeys { it.key + 100 }.mapValues { (_, next) -> next.map { it + 100 } } +
                (0 to listOf(100, 110)),
        )
        assertFalse(UsbInstallDexContract.entryOnlyAfterAccount(shortcutBeforeInputBranch, inputField,
            DexOperandIndex.descriptor(getter), DexOperandIndex.descriptor(entry)))
    }

    @Test fun `current Security Center APK satisfies the production DEX contracts`() {
        val apkPath = System.getenv("SECURITY_CENTER_APK")
        assumeTrue("Set SECURITY_CENTER_APK to run host-artifact verification", apkPath != null)
        val apk = java.io.File(apkPath!!)
        assumeTrue(apk.isFile)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals(
            "f3d338484cc5fd3f3fec9e8d9d0b4c6370923fba0f5e0d81975a45e914cbc776",
            digest.digest().joinToString("") { "%02x".format(it) },
        )

        val dex = DexOperandIndex.fixture(apk.absolutePath)
        val successCandidates = dex.declared(activity).flatMap { method ->
            val code = dex.code(method)
            code.reads.filter { it.definingClass == activity && it.type == "Z" }.mapNotNull { field ->
                UsbInstallDexContract.inputModeCalls(
                    code, "${field.definingClass}->${field.name}:${field.type}", activity, inputActivity,
                )?.let { method to it }
            }
        }.distinctBy { DexOperandIndex.descriptor(it.first) }
        assertEquals(1, successCandidates.size)
        val successDescriptor = DexOperandIndex.descriptor(successCandidates.single().first)
        assertEquals(DexOperandIndex.Value.Number(1), successCandidates.single().second.falseInput.arguments.last())

        val callbackCandidates = dex.strings("server result: ").filter { callback ->
            callback.definingClass.startsWith("${activity.removeSuffix(";")}\$") &&
                callback.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;") && callback.returnType == "V" &&
                dex.code(callback).hasString("AdbInstallActivity") &&
                dex.code(callback).calls.any { it.method.name == "finish" && it.method.parameterTypes.isEmpty() }
        }.filter { callback ->
            val calls = dex.code(callback).calls.filter {
                it.method.definingClass == activity && it.method.parameterTypes.isEmpty() && it.method.returnType == "V"
            }
            calls.size == 1 && UsbInstallDexContract.nullResponseCallsSuccess(
                dex.code(callback), DexOperandIndex.descriptor(calls.single().method),
            )
        }.distinctBy(DexOperandIndex::descriptor)
        assertEquals(1, callbackCandidates.size)

        val networkCandidates = dex.declared(activity).filter { method ->
            val code = dex.code(method)
            code.hasString("connectivity") && code.calls.any {
                DexOperandIndex.descriptor(it.method) == "Landroid/net/ConnectivityManager;->getActiveNetworkInfo()Landroid/net/NetworkInfo;"
            } && code.calls.any {
                DexOperandIndex.descriptor(it.method) == "Landroid/net/NetworkInfo;->isConnected()Z"
            } && code.calls.any { it.method.definingClass == "Landroid/os/AsyncTask;" && it.method.name == "executeOnExecutor" }
        }.distinctBy(DexOperandIndex::descriptor)
        assertEquals(1, networkCandidates.size)
        val entryCandidates = dex.declared(activity).filter { entry ->
            entry.parameterTypes.isEmpty() && entry.returnType == "V" && dex.code(entry).calls.any {
                DexOperandIndex.descriptor(it.method) == DexOperandIndex.descriptor(networkCandidates.single())
            }
        }.distinctBy(DexOperandIndex::descriptor)
        assertEquals(1, entryCandidates.size)
        val onCreate = dex.declared(activity).single {
            it.name == "onCreate" && it.parameterTypes.map(Any::toString) == listOf("Landroid/os/Bundle;") && it.returnType == "V"
        }
        val accountGetters = dex.code(onCreate).calls.filter {
            it.method.parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;") &&
                it.method.returnType == "Ljava/lang/String;"
        }.distinctBy { DexOperandIndex.descriptor(it.method) }
        assertEquals(1, accountGetters.size)
        val onCreateCode = dex.code(onCreate)
        val inputFieldRead = dex.code(successCandidates.single().first).reads.filter {
            it.definingClass == activity && it.type == "Z"
        }.map { "${it.definingClass}->${it.name}:${it.type}" }.distinct().single()
        assertTrue("onCreate does not read is_input into $inputFieldRead", UsbInstallDexContract.writesInputFlag(onCreateCode, inputFieldRead))
        val accountEntryPasses = UsbInstallDexContract.entryOnlyAfterAccount(
            onCreateCode, inputFieldRead, DexOperandIndex.descriptor(accountGetters.single().method),
            DexOperandIndex.descriptor(entryCandidates.single()),
        )
        assertTrue("account getter=${accountGetters.single().method} entry=${entryCandidates.single()} " +
            "calls=${onCreateCode.calls} branches=${onCreateCode.branches} edges=${onCreateCode.edges}", accountEntryPasses)
    }
}
