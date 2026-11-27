package com.takekazex.hypertweak.hook.base.dex

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.rules.camera.preferenceGates
import com.takekazex.hypertweak.hook.rules.camera.preferenceFields
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

class DexOperandIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = "pref_feature_key"
    private fun gate(name: String) = ImmutableMethodReference("Lfixture/Gates;", name, emptyList(), "Z")
    private fun invoke(name: String) = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, gate(name))
    private fun key() = ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference(key))
    private fun method(name: String, instructions: List<Instruction>) = ImmutableMethod(
        "Lfixture/Screen;", name, emptyList(), "V", 9, emptySet(), emptySet(),
        ImmutableMethodImplementation(4, instructions, emptyList(), emptyList()),
    )
    private fun index(vararg methods: ImmutableMethod): DexOperandIndex {
        val file = temporary.newFile("${System.nanoTime()}.dex")
        val type = ImmutableClassDef("Lfixture/Screen;", 1, "Ljava/lang/Object;", emptyList(),
            null, emptySet(), emptyList(), methods.toList())
        DexPool.writeTo(file.absolutePath, ImmutableDexFile(Opcodes.getDefault(), listOf(type)))
        return DexOperandIndex.fixture(file.absolutePath)
    }
    private fun guarded(name: String, gate: String) = method(name, listOf(
        invoke(gate), ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
        ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4), key(), ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))

    @Test fun `callers match the exact descriptor, not just the member name`() {
        fun call(owner: String, params: List<String>) = ImmutableInstruction35c(Opcode.INVOKE_STATIC, params.size, 0, 0, 0, 0, 0,
            ImmutableMethodReference(owner, "target", params, "Z"))
        val ret = ImmutableInstruction10x(Opcode.RETURN_VOID)
        val dex = index(
            method("exact", listOf(call("Lfixture/Gates;", emptyList()), ret)),
            method("otherOwner", listOf(call("Lfixture/Other;", emptyList()), ret)),
            method("otherSignature", listOf(call("Lfixture/Gates;", listOf("I")), ret)),
            method("exactAgain", listOf(call("Lfixture/Gates;", listOf("I")), call("Lfixture/Gates;", emptyList()), ret)),
        )
        val target = "Lfixture/Gates;->target()Z"
        assertEquals(setOf("exact", "exactAgain"), dex.callers(listOf(target)).map { it.name }.toSet())
        assertEquals(setOf("exact", "otherSignature", "exactAgain"),
            dex.callers(listOf(target, "Lfixture/Gates;->target(I)Z")).map { it.name }.toSet())
        assertTrue(dex.callers(emptyList()).isEmpty())
        assertTrue(dex.callers(listOf("Lfixture/Gates;->absent()Z")).isEmpty())
    }

    @Test fun `DEX 039 constants keep the rest of the method aligned`() {
        // OS4 hosts (framework.jar, AICR) ship DEX 039. dexlib2's default API 20 opcodes read
        // const-method-type as a one-unit unknown instruction and lose the call after it.
        // dexlib2 2.5.2 cannot write a proto reference, so write a const-string (also format 21c)
        // and patch its opcode to const-method-type (0xff). The proto index 0x13 matters: the
        // misread decodes it as two-unit const/16, which swallows the call (index 0 is a nop
        // that happens to realign). Bodiless methods supply that many protos.
        val protos = (1..24).map { arity ->
            ImmutableMethod("Lfixture/Screen;", "p$arity", List(arity) { ImmutableMethodParameter("I", emptySet(), null) },
                "V", 0x109, emptySet(), emptySet(), null)
        }
        val file = temporary.newFile("dex039.dex")
        val type = ImmutableClassDef("Lfixture/Screen;", 1, "Ljava/lang/Object;", emptyList(), null,
            emptySet(), emptyList(), listOf(method("typed", listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("marker")),
                invoke("gate"), ImmutableInstruction10x(Opcode.RETURN_VOID),
            ))) + protos)
        DexPool.writeTo(file.absolutePath, ImmutableDexFile(Opcodes.forApi(28), listOf(type)))
        val bytes = file.readBytes()
        val at = (0 until bytes.size - 4).single {
            bytes[it] == 0x1a.toByte() && bytes[it + 1] == 0x01.toByte() && bytes[it + 4] == 0x71.toByte()
        }
        bytes[at] = 0xff.toByte(); bytes[at + 2] = 0x13; bytes[at + 3] = 0
        // Host APKs go through the root-entry stream reader, so package the DEX as one.
        val apk = temporary.newFile("dex039.apk")
        java.util.zip.ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("classes.dex")); zip.write(bytes); zip.closeEntry()
        }
        val dex = DexOperandIndex.fixture(apk.absolutePath)
        assertEquals(listOf("typed"), dex.callers(listOf("Lfixture/Gates;->gate()Z")).map { it.name })
    }

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
    @Test fun `targeted string lookup preserves operand guard discovery and memoizes only the requested key`() {
        val source = index(guarded("page", "capability"))
        val target = source.strings(key).single()
        // Reuse the fixture file through its same public shape, without a native bridge on the host.
        val file = temporary.root.listFiles()!!.single { it.extension == "dex" }
        val requested = arrayListOf<String>()
        val narrow = DexOperandIndex.openWithStringLookup(file.path) { value ->
            requested += value
            listOf(DexOperandIndex.descriptor(target))
        }
        assertEquals("capability", narrow.preferenceGates(key).single().name)
        assertEquals("capability", narrow.preferenceGates(key).single().name)
        assertEquals(listOf(key), requested)
    }

    @Test fun `a failed targeted lookup never falls back to scanning every method body`() {
        index(guarded("page", "capability"))
        val file = temporary.root.listFiles()!!.single { it.extension == "dex" }
        val narrow = DexOperandIndex.openWithStringLookup(file.path) { null }
        assertThrows(IllegalArgumentException::class.java) { narrow.strings(key) }
    }

    @Test fun `a targeted descriptor still has to contain the requested exact string`() {
        val source = index(guarded("page", "capability"))
        val descriptor = DexOperandIndex.descriptor(source.strings(key).single())
        val file = temporary.root.listFiles()!!.single { it.extension == "dex" }
        val narrow = DexOperandIndex.openWithStringLookup(file.path) { listOf(descriptor) }
        assertThrows(IllegalArgumentException::class.java) { narrow.strings("different") }
    }

    @Test fun `APK index includes root multidex and ignores embedded asset DEX`() {
        index(guarded("page", "capability"))
        val primary = temporary.root.listFiles()!!.single { it.extension == "dex" }.readBytes()
        val secondary = temporary.newFile("secondary.dex")
        val method = ImmutableMethod("Lfixture/Secondary;", "second", emptyList(), "V", 9,
            emptySet(), emptySet(), guarded("unused", "other").implementation)
        DexPool.writeTo(secondary.path, ImmutableDexFile(Opcodes.getDefault(), listOf(
            ImmutableClassDef("Lfixture/Secondary;", 1, "Ljava/lang/Object;", emptyList(), null,
                emptySet(), emptyList(), listOf(method)),
        )))
        val apk = temporary.newFile("fixture.apk")
        java.util.zip.ZipOutputStream(apk.outputStream()).use { zip ->
            mapOf("classes.dex" to primary, "classes2.dex" to secondary.readBytes(),
                "assets/embedded.dex" to primary, "res/raw/image.bin" to byteArrayOf(0, 1)).forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val methods = DexOperandIndex.fixture(apk.path).strings(key)
        assertEquals(2, methods.size)
        assertEquals(setOf("page", "second"), methods.map { it.name }.toSet())
    }

    @Test fun `explicit containers read splits and reject duplicate class definitions`() {
        fun source(name: String, owner: String): java.io.File {
            val file = temporary.newFile(name)
            val type = ImmutableClassDef(owner, 1, "Ljava/lang/Object;", emptyList(), null, emptySet(), emptyList(), emptyList())
            DexPool.writeTo(file.path, ImmutableDexFile(Opcodes.getDefault(), listOf(type)))
            return file
        }
        val base = source("base.dex", "Lfixture/Base;")
        val split = source("split.dex", "Lfixture/Split;")
        val combined = DexRuntime.operands(base.path, containers = listOf(base.path, split.path))
        assertNotNull(combined.classDefinition("Lfixture/Base;"))
        assertNotNull(combined.classDefinition("Lfixture/Split;"))
        val baseOnly = DexRuntime.operands(base.path)
        assertNull(baseOnly.classDefinition("Lfixture/Split;"))
        assertNotSame(combined, baseOnly)
        assertSame(baseOnly, DexRuntime.operands(base.path))
        val duplicate = source("duplicate.dex", "Lfixture/Base;")
        assertThrows(IllegalArgumentException::class.java) {
            DexRuntime.operands(base.path, containers = listOf(base.path, duplicate.path)).methods
        }
        assertThrows(IllegalArgumentException::class.java) {
            DexRuntime.operands(base.path, containers = listOf(base.path, java.io.File(temporary.root, "missing.dex").path))
        }
    }

}
