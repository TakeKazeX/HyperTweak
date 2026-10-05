package com.takekazex.hypertweak.hook.rules.securitycenter

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercise the semantic transaction against a real APK, rather than renamed fixture methods. */
class PowerSaveTargetApkTest {
    @Test fun `native provider exposes one synchronous power-save transaction and tile protocol`() {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue(apk?.isFile == true)
        val container = DexFileFactory.loadDexContainer(apk!!, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
        val methods = classes.flatMap { it.methods }
        fun strings(method: Method) = (method.implementation?.instructions ?: emptyList()).mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.toSet()
        fun calls(method: Method) = (method.implementation?.instructions ?: emptyList()).mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }
        val parameters = PowerSaveTransitionContract.parameterTypes.map {
            when (it) { "boolean" -> "Z"; else -> "L${it.replace('.', '/')};" }
        }
        val transition = methods.filter {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == parameters &&
                strings(it).containsAll(PowerSaveTransitionContract.markers)
        }.single()
        assertTrue(transition.accessFlags and 8 == 0)
        assertTrue(calls(transition).any {
            it.definingClass == "Landroid/provider/Settings\$System;" && it.name == "putInt"
        })
        val provider = classes.single { it.type == transition.definingClass }
        val entry = provider.methods.single { it.name == "call" }
        assertTrue(strings(entry).containsAll(listOf("changePowerMode", "com.android.systemui")))
        // The first Boolean controls the branch: both enter/leave exist in the same transaction.
        // Host source review additionally verifies true -> handler entry, false -> restoration.
        assertEquals("Z", transition.parameterTypes.first())
    }
}
