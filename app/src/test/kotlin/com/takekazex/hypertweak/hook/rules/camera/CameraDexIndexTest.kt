package com.takekazex.hypertweak.hook.rules.camera

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.*
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CameraDexIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = "pref_feature_key"
    private fun gate(name: String) = ImmutableMethodReference("Lfixture/Gates;", name, emptyList(), "Z")
    private fun invoke(name: String) = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, gate(name))
    private fun key() = ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference(key))
    private fun method(name: String, instructions: List<Instruction>) = ImmutableMethod(
        "Lfixture/Screen;", name, emptyList(), "V", 9, emptySet(), emptySet(),
        ImmutableMethodImplementation(4, instructions, emptyList(), emptyList()),
    )
    private fun index(vararg methods: ImmutableMethod): CameraDexIndex {
        val file = temporary.newFile("${System.nanoTime()}.dex")
        val type = ImmutableClassDef("Lfixture/Screen;", 1, "Ljava/lang/Object;", emptyList(),
            null, emptySet(), emptyList(), methods.toList())
        DexPool.writeTo(file.absolutePath, ImmutableDexFile(Opcodes.getDefault(), listOf(type)))
        return CameraDexIndex.open(file.absolutePath)
    }
    private fun guarded(name: String, gate: String) = method(name, listOf(
        invoke(gate), ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
        ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4), key(), ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))

    @Test fun `renaming a capability preserves resolution through its consumer`() {
        val first = index(guarded("page", "arbitraryOne"))
        val second = index(guarded("anotherPage", "unrelatedNewSpelling"))
        assertEquals("arbitraryOne", first.preferenceGates(key).single().name)
        assertEquals("unrelatedNewSpelling", second.preferenceGates(key).single().name)
    }

    @Test fun `same key with different guarded capabilities remains ambiguous`() {
        val dex = index(guarded("one", "firstCapability"), guarded("two", "secondCapability"))
        assertEquals(2, dex.preferenceGates(key).size)
    }

    @Test fun `a key outside a condition does not inherit the previous gate`() {
        val dex = index(method("page", listOf(
            invoke("unrelated"), ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 2), key(), ImmutableInstruction10x(Opcode.RETURN_VOID),
        )))
        assertTrue(dex.preferenceGates(key).isEmpty())
    }

    @Test fun `opposite polarity cannot become a force-true capability`() {
        val dex = index(method("page", listOf(
            invoke("disableFeature"), ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction21t(Opcode.IF_NEZ, 0, 4), key(), ImmutableInstruction10x(Opcode.RETURN_VOID),
        )))
        assertTrue(dex.preferenceGates(key).isEmpty())
    }

    @Test fun `conflicting reaching definitions at a join are unknown`() {
        val dex = index(method("page", listOf(
            invoke("selectBranch"), ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), // 0..4
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 7), // 4 -> 11
            invoke("leftGate"), ImmutableInstruction11x(Opcode.MOVE_RESULT, 1), // 6..10
            ImmutableInstruction10t(Opcode.GOTO, 5), // 10 -> 15
            invoke("rightGate"), ImmutableInstruction11x(Opcode.MOVE_RESULT, 1), // 11..15
            ImmutableInstruction21t(Opcode.IF_EQZ, 1, 4), // 15 -> 19
            key(), ImmutableInstruction10x(Opcode.RETURN_VOID),
        )))
        assertTrue(dex.preferenceGates(key).isEmpty())
    }

    @Test fun `a branch must dominate the key and cannot be bypassed by another edge`() {
        val dex = index(method("page", listOf(
            invoke("bypass"), ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 8), // 4 -> key at 12
            invoke("notRequired"), ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
            ImmutableInstruction21t(Opcode.IF_EQZ, 1, 4), // 10 -> return at 14
            key(), ImmutableInstruction10x(Opcode.RETURN_VOID),
        )))
        assertTrue(dex.preferenceGates(key).isEmpty())
    }

    @Test fun `field guards retain the precise field rather than a same-type neighbour`() {
        val expected = ImmutableFieldReference("Lfixture/Flags;", "renamedCapability", "Z")
        val dex = index(method("page", listOf(
            ImmutableInstruction21c(Opcode.SGET_BOOLEAN, 0, expected),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4), key(), ImmutableInstruction10x(Opcode.RETURN_VOID),
        )))
        assertEquals(expected.toString(), dex.preferenceFields(key).single().toString())
    }
}
