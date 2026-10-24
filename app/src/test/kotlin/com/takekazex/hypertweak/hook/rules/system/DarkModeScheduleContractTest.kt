package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.rewriter.*
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class DarkModeScheduleContractTest {
    private fun source(): File {
        val file = System.getenv("SERVICES_JAR")?.let(::File)
        assumeTrue("Set SERVICES_JAR for actual framework contract checks", file?.isFile == true)
        return file!!
    }
    @Test fun currentServicesResolvesBothAutomaticRoutesAndNativePublication() {
        val contract = DarkModeScheduleResolver(DexOperandIndex.fixture(source().path)).resolve()
        assertTrue(contract.getValue("predicate").endsWith("()Z"))
        assertTrue(contract.getValue("wait").endsWith(":Z"))
        assertTrue(contract.getValue("update").endsWith("(II)V"))
        assertTrue(contract.getValue("callers").lines().size >= 6)
        println("DARK_MODE_CONTRACT=$contract")
    }
    private fun fixture(duplicate: Boolean = false, missing: Boolean = false) {
        val source = source()
        val index = DexOperandIndex.fixture(source.path)
        val contract = DarkModeScheduleResolver(index).resolve()
        val owner = contract.getValue("predicate").substringBefore("->")
        // Include connected inner service objects and the abstract property holder; retain external
        // framework API identities while renaming every private host method, field and owner.
        val owners = index.methods.map { it.definingClass }.filter { it == owner || it.startsWith(owner.dropLast(1) + "$") }.toSet()
        val names = owners.withIndex().associate { it.value to "Lfixture/dark/Host${it.index};" }
        fun type(value: String) = names[value] ?: value
        val methodNames = index.methods.filter { it.definingClass in owners && it.accessFlags and 2 != 0 && it.name != "<init>" }
            .withIndex().associate { DexOperandIndex.descriptor(it.value) to "private${it.index}" }
        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters) = Rewriter<String>(::type)
            override fun getMethodReferenceRewriter(rewriters: Rewriters) = Rewriter<MethodReference> { ref ->
                ImmutableMethodReference(type(ref.definingClass), methodNames[DexOperandIndex.descriptor(ref)] ?: ref.name,
                    ref.parameterTypes.map { type(it.toString()) }, type(ref.returnType))
            }
            override fun getFieldReferenceRewriter(rewriters: Rewriters) = Rewriter<FieldReference> { ref ->
                ImmutableFieldReference(type(ref.definingClass), if (ref.definingClass in owners) "renamed_${ref.name}" else ref.name, type(ref.type))
            }
        })
        val folder = createTempDirectory("dark-mode-contract-").toFile()
        try {
            val fixture = File(folder, "fixture.jar")
            val dexes = DexFileFactory.loadDexContainer(source, Opcodes.getDefault())
            val selected = dexes.dexEntryNames.flatMap { dexes.getEntry(it)!!.dexFile.classes }.filter { it.type in owners }.toSet()
            fun graph(renamed: Boolean): DexFile {
                val input = object : DexFile {
                    override fun getOpcodes() = Opcodes.getDefault()
                    override fun getClasses() = selected
                }
                val rewritten = if (renamed) rewriter.dexFileRewriter.rewrite(input) else input
                return object : DexFile {
                    override fun getOpcodes() = input.opcodes
                    override fun getClasses() = rewritten.classes.filter { !missing || it.type != type(owner.dropLast(1) + "\$IntProperty;") }.toSet()
                }
            }
            ZipOutputStream(fixture.outputStream()).use { out ->
                fun write(name: String, dex: DexFile) {
                    val store = MemoryDataStore()
                    DexPool.writeTo(store, dex)
                    out.putNextEntry(ZipEntry(name)); out.write(store.buffer, 0, store.size); out.closeEntry(); store.close()
                }
                write("classes.dex", graph(true))
                if (duplicate) write("classes2.dex", graph(false))
            }
            if (duplicate || missing) assertThrows(RuntimeException::class.java) {
                DarkModeScheduleResolver(DexOperandIndex.fixture(fixture.path)).resolve()
            } else {
                val result = DarkModeScheduleResolver(DexOperandIndex.fixture(fixture.path)).resolve()
                assertTrue(result.getValue("predicate").startsWith("Lfixture/dark/"))
                assertTrue(result.getValue("predicate").contains("->private"))
                assertTrue(result.getValue("wait").contains("->renamed_"))
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun renamedHostStillResolves() = fixture()
    @Test fun duplicateDecisionGraphIsRejected() = fixture(duplicate = true)
    @Test fun missingNightPropertyContractIsRejected() = fixture(missing = true)
}
