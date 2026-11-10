package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterGameBoosterResolver
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction11n
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.rewriter.*
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.dexlib2.writer.io.MemoryDataStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class SecurityCenterGameBoosterContractTest {
    private fun apk(): File {
        val file = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue("Set SECURITY_CENTER_APK for target verification", file?.isFile == true)
        return file!!
    }
    @Test fun resolvesActualGatesAndKeepsNoUsingPredicateOutOfHooks() {
        val resolver = SecurityCenterGameBoosterResolver(DexOperandIndex.fixture(apk().path))
        val gun = resolver.gunsight()
        val macro = resolver.macro()
        assertEquals("Lb9/a;->l(Ljava/lang/String;)Z", gun["m.gunsight"])
        assertEquals("Lda/b;->h(Landroid/content/Context;Ljava/lang/String;Z)Z", macro["m.macro"])
        assertEquals("Lda/b;->f(Z)Z", macro["m.setting"])
        assertEquals(setOf("m.macro", "m.setting"), macro.keys.filter { it.startsWith("m.") }.toSet())
        assertEquals("Lda/b;->d(Landroid/content/Context;Ljava/lang/String;)Z", macro["e.noUsing"])
        assertTrue(macro.getValue("callers").contains(macro.getValue("e.start")))
        assertEquals("Lu9/b;->e()Z", macro["model.invalid"])
        assertEquals(gun["model.invalid"], macro["model.invalid"])
        assertEquals(3, macro.getValue("model.contexts").lines().size)
        assertTrue(macro.getValue("model.contexts").contains(macro.getValue("e.start")))
        println("GAMEBOOSTER_CONTRACT gunsight=$gun macro=$macro")
    }

    private fun fixture(fault: String = "") {
        val apk = apk()
        val index = DexOperandIndex.fixture(apk.path)
        val resolver = SecurityCenterGameBoosterResolver(index)
        val contracts = resolver.gunsight() + resolver.macro()
        val anchors = arrayOf("gb_game_gunsight", "pref_gb_unsupport_macro_apps", "support_game_gunsight", "support_mi_game_macro")
        val owners = (anchors.flatMap { index.strings(it).map { m -> m.definingClass } } +
            contracts.filterKeys { it.startsWith("m.") || it.startsWith("e.") || it in setOf("model.invalid", "model.contexts") }.values.flatMap { it.lines() }.map { it.substringBefore("->") }).toSet()
        val types = owners.mapIndexed { i, type -> type to "Lfixture/game/Owner$i;" }.toMap()
        fun type(value: String) = types[value] ?: value
        val membership = index.declared(index.strings("pref_gb_unsupport_macro_apps").first().definingClass)
            .single { it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                index.code(it).calls.any { c -> c.method.name == "contains" } &&
                index.code(it).calls.any { c -> index.code(c.method).hasString("pref_gb_unsupport_macro_apps") } }
        val rewrite = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters) = Rewriter<String>(::type)
            override fun getMethodReferenceRewriter(rewriters: Rewriters) = Rewriter<MethodReference> { ref ->
                ImmutableMethodReference(type(ref.definingClass),
                    if (ref.name.startsWith("<") || ref.definingClass !in owners) ref.name else "renamed_${ref.name}",
                    ref.parameterTypes.map { type(it.toString()) }, type(ref.returnType))
            }
            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> {
                val base = super.getInstructionRewriter(rewriters)
                return Rewriter { instruction ->
                    val ref = (instruction as? ReferenceInstruction)?.reference
                    when {
                        fault == "key" && ref is StringReference && ref.string == "gb_game_gunsight" ->
                            ImmutableInstruction21c(instruction.opcode,
                                (instruction as org.jf.dexlib2.iface.instruction.OneRegisterInstruction).registerA,
                                ImmutableStringReference("missing_gunsight_key"))
                        (fault == "edge" || fault == "model") && ref is MethodReference && DexOperandIndex.descriptor(ref) ==
                            (if (fault == "edge") DexOperandIndex.descriptor(membership) else contracts.getValue("model.invalid")) -> {
                            val changed = object : RewriterModule() {
                                override fun getMethodReferenceRewriter(rewriters: Rewriters) = Rewriter<MethodReference> {
                                    ImmutableMethodReference(type(it.definingClass), "missing_edge", it.parameterTypes.map(CharSequence::toString), it.returnType)
                                }
                            }
                            DexRewriter(changed).instructionRewriter.rewrite(instruction)
                        }
                        fault == "flag" && instruction is Instruction11n && instruction.narrowLiteral == 1 ->
                            ImmutableInstruction11n(instruction.opcode, instruction.registerA, 0)
                        else -> base.rewrite(instruction)
                    }
                }
            }
        })
        val folder = createTempDirectory("gamebooster-contract-").toFile()
        try {
            val fixture = File(folder, "fixture.apk")
            val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            ZipOutputStream(fixture.outputStream()).use { output ->
                container.dexEntryNames.forEach { name ->
                    val original = container.getEntry(name)!!.dexFile
                    val graph = object : DexFile {
                        override fun getOpcodes() = original.opcodes
                        override fun getClasses() = original.classes.filter { it.type in owners }.toSet()
                    }
                    val store = MemoryDataStore()
                    try {
                        DexPool.writeTo(store, rewrite.dexFileRewriter.rewrite(graph))
                        output.putNextEntry(ZipEntry(name.substringAfterLast('/')))
                        output.write(store.buffer, 0, store.size)
                        output.closeEntry()
                        if (fault == "duplicate") {
                            val duplicate = MemoryDataStore()
                            try {
                                DexPool.writeTo(duplicate, graph)
                                output.putNextEntry(ZipEntry(name.substringAfterLast('/').replace("classes", "classes99")))
                                output.write(duplicate.buffer, 0, duplicate.size)
                                output.closeEntry()
                            } finally { duplicate.close() }
                        }
                    } finally { store.close() }
                }
            }
            val mutated = SecurityCenterGameBoosterResolver(DexOperandIndex.fixture(fixture.path))
            when (fault) {
                "key" -> { assertThrows(RuntimeException::class.java) { mutated.gunsight() }; mutated.macro() }
                "model" -> { assertThrows(RuntimeException::class.java) { mutated.gunsight() }; assertThrows(RuntimeException::class.java) { mutated.macro() } }
                "duplicate" -> { assertThrows(RuntimeException::class.java) { mutated.gunsight() }; assertThrows(RuntimeException::class.java) { mutated.macro() } }
                "edge", "flag" -> { mutated.gunsight(); assertThrows(RuntimeException::class.java) { mutated.macro() } }
                else -> {
                    assertTrue(mutated.gunsight().getValue("m.gunsight").contains("->renamed_"))
                    assertTrue(mutated.macro().getValue("m.macro").startsWith("Lfixture/game/"))
                }
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun obfuscatedNamesMayChange() = fixture()
    @Test fun missingPreferenceFailsClosedIndependently() = fixture("key")
    @Test fun disconnectedMembershipFailsClosedIndependently() = fixture("edge")
    @Test fun ambiguousGraphsFailClosed() = fixture("duplicate")
    @Test fun disconnectedModelPredicateFailsClosed() = fixture("model")
    @Test fun changedWrapperFlagFailsClosed() = fixture("flag")
}
