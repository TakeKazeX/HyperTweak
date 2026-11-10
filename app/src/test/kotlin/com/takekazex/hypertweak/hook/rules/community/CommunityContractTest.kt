package com.takekazex.hypertweak.hook.rules.community

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups
import com.takekazex.hypertweak.settings.contract.CommunitySettings
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

class CommunityContractTest {
    private fun source(): File {
        val file = System.getenv("COMMUNITY_APK")?.let(::File)
        assumeTrue("Set COMMUNITY_APK to check actual host bytecode", file?.isFile == true)
        return file!!
    }
    @Test fun actualFeedbackGraphResolvesUniquelyDespiteCrashSdkModelGetter() {
        val dex = DexOperandIndex.fixture(source().path)
        assertEquals(2, dex.strings("ro.product.model").size)
        val contract = CommunityResolver(dex).resolve()
        assertTrue(contract.getValue("build").contains("ExtraB;->buildData"))
        assertTrue(contract.getValue("model").endsWith("->model:Ljava/lang/String;"))
        assertTrue(contract.getValue("app").contains("PackInfo;->appName:"))
        println("COMMUNITY_CONTRACT=$contract")
    }
    private fun fixture(duplicate: Boolean = false, missing: String? = null) {
        val input = source()
        val index = DexOperandIndex.fixture(input.path)
        val contract = CommunityResolver(index).resolve()
        val owners = contract.values.map { it.substringBefore("->") }.toSet() +
            index.strings("ro.product.model").map { it.definingClass }
        val mapping = owners.sorted().withIndex().associate { it.value to "Lfixture/community/C${it.index};" }
        fun type(v: String) = mapping[v] ?: v
        val rewrite = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(r: Rewriters) = Rewriter<String>(::type)
            override fun getMethodReferenceRewriter(r: Rewriters) = Rewriter<MethodReference> { m ->
                ImmutableMethodReference(type(m.definingClass), if (m.definingClass in owners && !m.name.startsWith("<")) "renamed_${m.name}" else m.name,
                    m.parameterTypes.map { type(it.toString()) }, type(m.returnType))
            }
            override fun getFieldReferenceRewriter(r: Rewriters) = Rewriter<FieldReference> { f ->
                ImmutableFieldReference(type(f.definingClass), if (f.definingClass in owners) "renamed_${f.name}" else f.name, type(f.type))
            }
        })
        val dir = createTempDirectory("community-contract-").toFile()
        try {
            val dexes = DexFileFactory.loadDexContainer(input, Opcodes.getDefault())
            val classes = dexes.dexEntryNames.flatMap { dexes.getEntry(it)!!.dexFile.classes }.filter { it.type in owners &&
                (missing == null || it.type != contract.getValue(missing).substringBefore("->")) }.toSet()
            val original = object : DexFile {
                override fun getOpcodes() = Opcodes.getDefault()
                override fun getClasses() = classes
            }
            val file = File(dir, "fixture.apk")
            ZipOutputStream(file.outputStream()).use { out ->
                fun write(name: String, dex: DexFile) {
                    val store = MemoryDataStore()
                    DexPool.writeTo(store, dex)
                    out.putNextEntry(ZipEntry(name)); out.write(store.buffer, 0, store.size); out.closeEntry(); store.close()
                }
                write("classes.dex", rewrite.dexFileRewriter.rewrite(original))
                if (duplicate) write("classes2.dex", original)
            }
            if (duplicate || missing != null) assertThrows(RuntimeException::class.java) {
                CommunityResolver(DexOperandIndex.fixture(file.path)).resolve()
            } else {
                val result = CommunityResolver(DexOperandIndex.fixture(file.path)).resolve()
                assertTrue(result.values.all { it.startsWith("Lfixture/community/") })
                assertTrue(result.getValue("build").contains("->renamed_"))
                assertTrue(result.getValue("model").contains("->renamed_"))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun renamedClassesMethodsAndFieldsResolve() = fixture()
    @Test fun duplicateGraphsRejected() = fixture(duplicate = true)
    @Test fun missingBuilderRejected() = fixture(missing = "build")
    @Test fun missingModelConstructorRejected() = fixture(missing = "model")
    @Test fun missingPackageFieldRejected() = fixture(missing = "app")
    class Pack(@JvmField var name: String?)
    class Client(@JvmField var model: String = "native")
    class Payload(@JvmField var apps: ArrayList<Any?>? = null, @JvmField var client: Client? = Client()) {
        fun build() = Unit
    }
    private fun access() = CommunityAccess(Payload::class.java.getDeclaredMethod("build"), emptyList(),
        Payload::class.java.getDeclaredField("apps"), Payload::class.java.getDeclaredField("client"),
        Client::class.java.getDeclaredField("model"), Pack::class.java.getDeclaredField("name"))
    @Test fun filteringRemovesWholeModuleEntriesPreservesOthersAndDoesNotMutateSharedList() {
        val shared = arrayListOf<Any?>(Pack("com.takekazex.hypertweak"), Pack("other"), null,
            Pack("com.sevtinge.hyperceiler"), Pack(null))
        val payload = Payload(shared)
        access().apply(payload, true, "custom")
        assertEquals(5, shared.size)
        assertEquals(3, payload.apps!!.size)
        assertEquals("other", (payload.apps!![0] as Pack).name)
        assertEquals("custom", payload.client!!.model)
        access().apply(payload, true, "custom")
        assertEquals(3, payload.apps!!.size)
    }
    @Test fun disableResetAndNewGenerationNeedNoSavedStateAndNullListDoesNotBlockModel() {
        val access = access()
        val native = Payload(arrayListOf(Pack("com.takekazex.hypertweak")))
        access.apply(native, false, null)
        assertEquals(1, native.apps!!.size)
        assertEquals("native", native.client!!.model)
        val sparse = Payload()
        access.apply(sparse, true, "custom")
        assertNull(sparse.apps)
        assertEquals("custom", sparse.client!!.model)
        access.apply(Payload(client = null), true, "custom")
        // Disable/reset/generation replacement affect the next native build, not a global Build field.
        val next = Payload()
        access.apply(next, false, CommunitySettings.model(false, "custom"))
        assertEquals("native", next.client!!.model)
    }
    @Test fun defaultsAndNullsPreserveHostAndAllPrivateProcessesReceiveSettings() {
        assertNull(CommunitySettings.model(false, "model"))
        assertNull(CommunitySettings.model(true, "  "))
        assertEquals("model", CommunitySettings.model(true, " model "))
        assertFalse(CommunitySettings.blocked(null))
        assertFalse(CommunitySettings.blocked("com.example.other"))
        assertTrue(CommunitySettings.blocked("com.takekazex.hypertweak"))
        assertTrue(CommunitySettings.blocked("com.sevtinge.hyperceiler"))
        for (process in listOf(CommunitySettings.PACKAGE, CommunitySettings.PACKAGE + ":widgetProvider", CommunitySettings.PACKAGE + ":pushservice", CommunitySettings.PACKAGE + ":web", CommunitySettings.PACKAGE + ":milink_ipc")) {
            assertTrue(RuntimeSettingsGroups.COMMUNITY in RuntimeSettingsGroups.forProcess(process))
            CommunitySettings.keys.forEach { assertEquals(setOf(RuntimeSettingsGroups.COMMUNITY), RuntimeSettingsGroups.owners(it)) }
        }
        assertFalse(RuntimeSettingsGroups.COMMUNITY in RuntimeSettingsGroups.forProcess("com.android.systemui"))
    }
}
