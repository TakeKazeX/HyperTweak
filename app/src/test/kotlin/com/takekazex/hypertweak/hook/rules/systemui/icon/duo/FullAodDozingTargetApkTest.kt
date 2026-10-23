package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class FullAodDozingTargetApkTest {
    @Test fun `manager lookup and dozing flag match the installed host callback`() {
        val apk = System.getenv("SYSTEM_UI_APK")?.let(::File)
        assumeTrue("Set SYSTEM_UI_APK for current host verification", apk?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(apk!!, Opcodes.getDefault())
        val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        fun descriptor(name: String) = "L${name.replace('.', '/')};"
        val manager = classes.getValue(descriptor(FullAodDozingSource.MANAGER))
        assertTrue(descriptor(FullAodDozingSource.API) in manager.interfaces)
        val flag = manager.fields.single { it.name == "isDozing" }
        assertEquals("Z", flag.type)
        assertTrue(flag.accessFlags and 1 != 0)
        assertEquals(0, flag.accessFlags and 8)
        val registry = classes.getValue(descriptor(FullAodDozingSource.REGISTRY))
        val lookup = registry.methods.single { it.name == "getImpl" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Class;") }
        assertEquals("Ljava/lang/Object;", lookup.returnType)
        assertTrue(lookup.accessFlags and 8 != 0)
        val callback = classes.values.flatMap { it.methods }.filter { it.name == "onDozingChanged" && it.parameterTypes.map(CharSequence::toString) == listOf("Z") }
            .single { method -> method.implementation?.instructions?.any {
                val ref = (it as? ReferenceInstruction)?.reference as? FieldReference
                it.opcode == Opcode.IPUT_BOOLEAN && ref?.definingClass == manager.type && ref.name == flag.name
            } == true }
        // The same native callback obtains its other full-AOD dependency from this registry.
        assertTrue(callback.implementation!!.instructions.any {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == registry.type && ref.name == lookup.name
        })
    }
}
