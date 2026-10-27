package com.takekazex.hypertweak.hook.rules.securitycenter

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Checks the living-page discovery path against a supplied, independently identified APK. */
class SecurityCenterPageHostContractTest {
    @Test fun fragmentListAccessorReadsTheSameStoreThatAcceptsFragments() {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue("Set SECURITY_CENTER_APK to verify the host page contract", apk?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(apk!!, Opcodes.getDefault())
        val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        val manager = classes.getValue("Landroidx/fragment/app/FragmentManager;")
        val candidates = manager.methods.filter {
            it.accessFlags and 1 != 0 && it.accessFlags and 8 == 0 &&
                it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;"
        }
        val accessor = candidates.singleOrNull { it.name == "getFragments" } ?: candidates.single()
        fun refs(method: Method) = (method.implementation?.instructions ?: emptyList()).mapNotNull {
            (it as? ReferenceInstruction)?.reference
        }
        val storeCall = refs(accessor).filterIsInstance<MethodReference>()
            .single { it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;" }
        val store = classes.getValue(storeCall.definingClass)
        val listReader = store.methods.single { it.name == storeCall.name && it.parameterTypes.isEmpty() }
        val fields = refs(listReader).filterIsInstance<FieldReference>()
            .filter { it.definingClass == store.type && it.type == "Ljava/util/ArrayList;" }
            .map { it.name }.toSet()
        assertEquals(1, fields.size)
        assertTrue(store.methods.any { method ->
            method.parameterTypes.map(CharSequence::toString) == listOf("Landroidx/fragment/app/Fragment;") &&
                refs(method).filterIsInstance<FieldReference>().any { it.name in fields && it.definingClass == store.type }
        })
        listOf("Lcom/miui/permcenter/privacycenter/PrivacyFragment;",
            "Lcom/miui/dock/settings/DockSettingsFragment;",
            "Lcom/miui/gamebooster/beauty/BeautyManageFragment;",
            "Lcom/miui/powercenter/nightcharge/ChargeProtectFragment;").forEach {
            assertNotNull("missing host page $it", classes[it])
        }
    }
}
