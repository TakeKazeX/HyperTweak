package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterDockHost
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterPopupResolver
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SidebarPopupPolicy
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.rewriter.*
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.dexlib2.writer.io.MemoryDataStore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class SecurityCenterPopupContractTest {
    @Test fun onlyDolbyTimingMessagesAreSuppressedAndSwitchesRemainIndependent() {
        for (dolby in listOf(false, true)) for (game in listOf(false, true)) {
            val policy = SidebarPopupPolicy(dolby, game)
            assertEquals(dolby, policy.blocksTiming(123, 123))
            assertFalse(policy.blocksTiming(124, 123))
            assertFalse(policy.blocksTiming(null, 123))
            assertFalse(policy.blocksTiming("123", 123))
            assertEquals(game, policy.game)
        }
        assertFalse(SidebarPopupPolicy().blocksTiming(123, 123))
    }

    @Test fun liveRoutingMigratesFromAbsentFeatureAndSupportsDisableAndReset() {
        val dolby = PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_DOLBY_POPUP
        val game = PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_GAME_POPUP
        val groups = setOf(RuntimeSettingsGroups.SECURITY_POPUPS)
        for (key in listOf(dolby, game)) {
            assertEquals(groups, RuntimeSettingsGroups.affected(key))
            assertNull(RuntimeSettingsSnapshot.read(1, mapOf(key to 1), groups))
        }
        assertTrue(RuntimeSettingsGroups.SECURITY_POPUPS in RuntimeSettingsGroups.forProcess(SecurityCenterDockHost.PROCESS))
        assertFalse(RuntimeSettingsGroups.SECURITY_POPUPS in RuntimeSettingsGroups.forProcess("com.miui.misound"))
        val ledger = RuntimeSettingsLedger().also {
            it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true)))
        }
        val enabled = RuntimeSettingsSnapshot.read(2, mapOf(dolby to true, game to true), groups)!!
        assertTrue(ledger.accept(enabled))
        val replacement = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        assertEquals(true, replacement.value(dolby))
        assertEquals(true, replacement.value(game))
        val disabled = RuntimeSettingsSnapshot.read(3, mapOf(dolby to false, game to true), groups)!!
        assertEquals(groups, replacement.changedGroups(disabled))
        assertTrue(replacement.accept(disabled))
        assertEquals(false, replacement.value(dolby))
        assertEquals(true, replacement.value(game))
        assertTrue(replacement.accept(RuntimeSettingsSnapshot.read(4, emptyMap<String, Any>(), groups)!!))
        assertNull(replacement.value(dolby))
        assertNull(replacement.value(game))
    }

    @Test fun currentApkResolvesConnectedDisplayAndCleanupBoundaries() {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue("Set SECURITY_CENTER_APK for target verification", apk?.isFile == true)
        val result = SecurityCenterPopupResolver(DexOperandIndex.fixture(apk!!.path)).resolve()
        assertEquals(13, result.keys.count { it.startsWith("m.") })
        assertTrue(result.getValue("m.timing").endsWith("(IIILjava/lang/String;Ljava/lang/String;Ljava/util/Map;)V"))
        assertTrue(result.getValue("m.launch").contains("Ljava/util/List;"))
        assertNotEquals(result.getValue("m.launch"), result.getValue("m.launchRender"))
        assertNotEquals(result.getValue("m.wild"), result.getValue("m.wildRender"))
        assertNotEquals(result.getValue("dolbyText"), result.getValue("dolbyLayout"))
        println("SIDEBAR_POPUP_CONTRACT=$result")
    }

    private fun fixture(duplicate: Boolean = false, missing: Boolean = false) {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue("Set SECURITY_CENTER_APK for mutation checks", apk?.isFile == true)
        val index = DexOperandIndex.fixture(apk!!.path)
        val contract = SecurityCenterPopupResolver(index).resolve()
        val members = contract.filterKeys { it.startsWith("m.") || it in setOf("singleton", "launchView") }.values
        val game = contract.getValue("m.wild").substringBefore("->")
        val runnable = index.code(contract.getValue("m.wild")).calls.single {
            it.method.name == "<init>" && it.method.parameterTypes.map(CharSequence::toString) == listOf(game)
        }.method.definingClass
        val windowRunnable = index.code(contract.getValue("m.window")).calls.single {
            it.method.name == "<init>" && it.method.parameterTypes.map(CharSequence::toString) == listOf(game, "Z")
        }.method.definingClass
        val owners = (members.map { it.substringBefore("->") } + runnable + windowRunnable + contract.getValue("launchView").substringAfter(':')).toSet()
        // The exported service name is a manifest contract; all obfuscated owners may change.
        val types = owners.filter { it != "Lcom/miui/gamebooster/service/DockWindowManagerService;" }
            .mapIndexed { i, type -> type to "Lfixture/popups/Owner$i;" }.toMap()
        val names = members.mapIndexed { i, member -> member to "member$i" }.toMap()
        fun type(value: String) = types[value] ?: value
        val rewrite = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters) = Rewriter<String>(::type)
            override fun getMethodReferenceRewriter(rewriters: Rewriters) = Rewriter<MethodReference> { ref ->
                ImmutableMethodReference(type(ref.definingClass), names[DexOperandIndex.descriptor(ref)] ?: ref.name,
                    ref.parameterTypes.map { type(it.toString()) }, type(ref.returnType))
            }
            override fun getFieldReferenceRewriter(rewriters: Rewriters) = Rewriter<FieldReference> { ref ->
                ImmutableFieldReference(type(ref.definingClass), names["${ref.definingClass}->${ref.name}:${ref.type}"] ?: ref.name, type(ref.type))
            }
        })
        val folder = createTempDirectory("sidebar-popup-contract-").toFile()
        try {
            val fixture = File(folder, "fixture.apk")
            val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            ZipOutputStream(fixture.outputStream()).use { output ->
                fun write(name: String, dex: DexFile) {
                    val store = MemoryDataStore()
                    try {
                        DexPool.writeTo(store, dex)
                        output.putNextEntry(ZipEntry(name))
                        output.write(store.buffer, 0, store.size)
                        output.closeEntry()
                    } finally { store.close() }
                }
                container.dexEntryNames.forEach { name ->
                    val original = container.getEntry(name)!!.dexFile
                    val graph = object : DexFile {
                        override fun getOpcodes() = original.opcodes
                        override fun getClasses() = original.classes.filter { it.type in owners && (!missing || it.type != runnable) }.toSet()
                    }
                    write(name.substringAfterLast('/'), rewrite.dexFileRewriter.rewrite(graph))
                }
                if (duplicate) {
                    // A second semantically matching manager must not be chosen by source order.
                    val graph = object : DexFile {
                        override fun getOpcodes() = Opcodes.getDefault()
                        override fun getClasses() = container.dexEntryNames.flatMap {
                            container.getEntry(it)!!.dexFile.classes
                        }.filter { it.type in owners }.toSet()
                    }
                    write("classes999.dex", graph)
                }
            }
            if (duplicate || missing) {
                assertThrows(RuntimeException::class.java) { SecurityCenterPopupResolver(DexOperandIndex.fixture(fixture.path)).resolve() }
            } else {
                val result = SecurityCenterPopupResolver(DexOperandIndex.fixture(fixture.path)).resolve()
                assertTrue(result.getValue("m.timing").startsWith("Lfixture/popups/"))
                assertTrue(result.getValue("m.wild").contains("->member"))
                assertTrue(result.getValue("singleton").contains("->member"))
                assertEquals(contract.getValue("dolbyText"), result.getValue("dolbyText"))
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun renamedClassesMethodsAndFieldsStillResolve() = fixture()
    @Test fun ambiguousDisplayGraphFailsClosed() = fixture(duplicate = true)
    @Test fun missingQueuedPublicationGraphFailsClosed() = fixture(missing = true)
}
