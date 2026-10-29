package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterSidebarResolver
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.DexFile
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

class SecurityCenterSidebarContractTest {
    @Test fun threeModesRouteLiveSurviveUpgradeAndResetWithoutLegacyState() {
        val key = PlatformSettingsKeys.KEY_SECURITY_CENTER_SIDEBAR_MODE
        val groups = setOf(RuntimeSettingsGroups.SECURITY_SIDEBAR)
        assertEquals(groups, RuntimeSettingsGroups.affected(key))
        assertTrue(groups.single() in RuntimeSettingsGroups.forProcess("com.miui.securitycenter:ui"))
        assertFalse(RuntimeSettingsGroups.owns("com.android.systemui", key))
        for (bad in listOf(-1, 3, true, "2", 2L, 2f)) assertNull(RuntimeSettingsSnapshot.read(2, mapOf(key to bad), groups))
        val ledger = RuntimeSettingsLedger().also { it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true))) }
        for ((offset, mode) in listOf(2, 1, 0, 2).withIndex()) {
            val snapshot = RuntimeSettingsSnapshot.read(offset + 2L, mapOf(key to mode), groups)!!
            assertTrue(ledger.accept(snapshot))
            val replacement = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
            assertEquals(mode, replacement.value(key))
        }
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(10, emptyMap<String, Any>(), groups)!!))
        assertNull(ledger.value(key))
    }

    private fun apk(): File {
        val file = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue("Set SECURITY_CENTER_APK for host contracts", file?.isFile == true)
        return file!!
    }
    @Test fun currentHostConnectsSamplingGeometryDrawingAndTouch() {
        val result = SecurityCenterSidebarResolver(DexOperandIndex.fixture(apk().path)).resolve()
        assertEquals(setOf("view", "touch", "draw"), result.keys)
        assertTrue(result.getValue("touch").startsWith(result.getValue("view") + "->"))
        assertTrue(result.getValue("draw").endsWith("->draw(Landroid/graphics/Canvas;)V"))
        println("SIDEBAR_HANDLE_CONTRACT=$result")
    }

    private fun mutate(kind: String) {
        val apk = apk()
        val index = DexOperandIndex.fixture(apk.path)
        val result = SecurityCenterSidebarResolver(index).resolve()
        val types = linkedSetOf(result.getValue("view"), result.getValue("draw").substringBefore("->"))
        val start = index.strings("startRegionSampling, isSmaplingOnce:").single()
        types += index.code(start).calls.single { it.method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/graphics/Rect;") }.method.definingClass
        var parent = index.classDefinition(result.getValue("view"))?.superclass
        while (parent != null && index.classDefinition(parent) != null && types.add(parent)) parent = index.classDefinition(parent)?.superclass
        val renamed = types.mapIndexed { i, type -> type to "Lfixture/sidebar/T$i;" }.toMap()
        val rewrite = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters) = Rewriter<String> { renamed[it] ?: it }
        })
        val folder = createTempDirectory("sidebar-handle-contract-").toFile()
        try {
            val file = File(folder, "fixture.apk")
            val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.filter { it.type in types }
            ZipOutputStream(file.outputStream()).use { zip ->
                fun write(name: String, defs: Set<org.jf.dexlib2.iface.ClassDef>, rename: Boolean) {
                    val dex = object : DexFile {
                        override fun getOpcodes() = Opcodes.getDefault()
                        override fun getClasses() = defs
                    }
                    val memory = MemoryDataStore()
                    try {
                        DexPool.writeTo(memory, if (rename) rewrite.dexFileRewriter.rewrite(dex) else dex)
                        zip.putNextEntry(ZipEntry(name)); zip.write(memory.buffer, 0, memory.size); zip.closeEntry()
                    } finally { memory.close() }
                }
                write("classes.dex", classes.filter { kind != "missing" || it.type != result.getValue("draw").substringBefore("->") }.toSet(), true)
                if (kind == "duplicate") write("classes2.dex", classes.toSet(), false)
            }
            if (kind == "renamed") {
                val resolved = SecurityCenterSidebarResolver(DexOperandIndex.fixture(file.path)).resolve()
                assertTrue(resolved.getValue("view").startsWith("Lfixture/sidebar/"))
                assertTrue(resolved.getValue("draw").startsWith("Lfixture/sidebar/"))
            } else assertThrows(RuntimeException::class.java) { SecurityCenterSidebarResolver(DexOperandIndex.fixture(file.path)).resolve() }
        } finally { folder.deleteRecursively() }
    }
    @Test fun renamedOwnersResolveFromConnectedBehavior() = mutate("renamed")
    @Test fun ambiguousSamplingGraphFailsClosed() = mutate("duplicate")
    @Test fun missingDrawableFailsClosed() = mutate("missing")
}
