package com.takekazex.hypertweak.hook.rules.googleapp

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec as Codec
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

/** No logging strings at all. Only connected native ownership and rendering can select a target. */
class AskScreenGraphFixtureTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(prefix: String, helperCount: Int, render: Boolean = true, ambiguous: Boolean = false): List<AskScreenGraphContract.Match> {
        val root = "L$prefix/Thumbnail;"; val coordinator = "L$prefix/Policy;"
        val entryOwner = "L$prefix/Invocation;"; val model = "L$prefix/NativeModel;"
        val entry = ImmutableMethodReference(entryOwner, "nativeOmni", emptyList(), "Z")
        val gate = ImmutableMethodReference(coordinator, "allowed", emptyList(), "Z")
        val cached = ImmutableFieldReference(coordinator, "nativeCached", "Z")
        val owner = ImmutableFieldReference(coordinator, "invocation", entryOwner)
        val link = ImmutableFieldReference(model, "policy", coordinator)
        fun bool(name: String) = ImmutableFieldReference(model, name, "Z")
        val hint = bool("nativeHint")
        val decoy = bool("otherHint")
        val accessor = ImmutableMethodReference(model, "view", emptyList(), "Landroid/widget/TextView;")
        fun ref(name: String) = ImmutableMethodReference(model, name, emptyList(), "V")
        fun invoke(target: org.jf.dexlib2.iface.reference.MethodReference) =
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, target)
        fun method(type: String, name: String, result: String, body: List<Instruction>, params: List<ImmutableMethodParameter> = emptyList()) =
            ImmutableMethod(type, name, params, result, 1, emptySet(), emptySet(),
                ImmutableMethodImplementation(4, body, emptyList(), emptyList()))
        fun field(type: String, name: String, value: String) = ImmutableField(type, name, value, 1, null, emptySet(), emptySet())
        val ret = ImmutableInstruction10x(Opcode.RETURN_VOID)
        val methods = ArrayList<ImmutableMethod>()
        methods += method(model, "<init>", "V", listOf(ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 2, 1, hint)) +
            (if (ambiguous) listOf(ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 2, 1, decoy)) else emptyList()) + ret,
            listOf(ImmutableMethodParameter("Z", emptySet(), null)))
        methods += method(model, "submit", "V", listOf(ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 0, 1, hint),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, link), invoke(gate)) +
            (if (ambiguous) listOf(ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 0, 1, decoy)) else emptyList()) + ret)
        methods += method(model, "view", accessor.returnType, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)))
        methods += method(model, "render", "V", listOf(ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 0, 1, hint)) +
            (if (ambiguous) listOf(ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 0, 1, decoy)) else emptyList()) + invoke(ref("helper0")) + ret)
        repeat(helperCount) { level ->
            val body = if (level + 1 < helperCount) listOf(invoke(ref("helper${level + 1}")))
            else listOf(invoke(accessor)) + if (render) listOf(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0,
                    ImmutableMethodReference("Landroid/widget/TextView;", "setHint", listOf("Ljava/lang/CharSequence;"), "V"))) else emptyList()
            methods += method(model, "helper$level", "V", body + ret)
        }
        val gateMethod = method(coordinator, "allowed", "Z", listOf(ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 0, 1, cached),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, owner), invoke(entry), ImmutableInstruction11x(Opcode.RETURN, 0)))
        val rootMethod = method(root, "retain", "Z", listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)))
        fun type(type: String, fields: List<ImmutableField>, members: List<ImmutableMethod>) = ImmutableClassDef(type, 1,
            "Ljava/lang/Object;", emptyList(), null, emptySet(), fields, members)
        val file = temporary.newFile("${System.nanoTime()}.dex")
        DexPool.writeTo(file.path, ImmutableDexFile(Opcodes.getDefault(), listOf(
            type(root, listOf(field(root, "coordinator", coordinator)), listOf(rootMethod)),
            type(coordinator, listOf(field(coordinator, "nativeCached", "Z"), field(coordinator, "invocation", entryOwner)), listOf(gateMethod)),
            type(model, listOf(field(model, "nativeHint", "Z"), field(model, "otherHint", "Z"), field(model, "policy", coordinator)), methods),
        )))
        val index = DexOperandIndex(listOf(file))
        return AskScreenGraphContract.resolve(index, Codec.descriptor(rootMethod),
            callers = { target -> index.methods.filter { index.code(it).calls.any { Codec.descriptor(it.method) == Codec.descriptor(target) } } },
            uses = { target -> AskScreenGraphContract.FieldUses(
                index.methods.filter { index.code(it).reads.any { Codec.descriptor(it) == Codec.descriptor(target) } },
                index.methods.filter { index.code(it).writes.any { Codec.descriptor(it.first) == Codec.descriptor(target) } },
            ) },
            isOmniEntry = { Codec.descriptor(it) == Codec.descriptor(entry) },
        )
    }

    @Test fun arbitraryRenamesAndRendererExtractionDepthPreserveSelectionWithoutDiagnosticStrings() {
        assertEquals("LFirst/NativeModel;->nativeHint:Z", fixture("First", 1).single().hint)
        assertEquals("LRenamedCompletely/NativeModel;->nativeHint:Z", fixture("RenamedCompletely", 5).single().hint)
    }
    @Test fun missingNativeHintRendererIsRejected() { assertTrue(fixture("Missing", 3, render = false).isEmpty()) }
    @Test fun ambiguousConnectedHintFlagsAreNotGuessed() { assertEquals(2, fixture("Ambiguous", 2, ambiguous = true).size) }
}
