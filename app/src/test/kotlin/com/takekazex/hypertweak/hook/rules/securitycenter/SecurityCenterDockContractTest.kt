package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
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

class SecurityCenterDockContractTest {
    @Test fun executingProcessMatchesCurrentManifestInsteadOfAssumingTheMainProcess() {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        val aapt = System.getenv("SECURITY_CENTER_AAPT2")?.let(::File)
        assumeTrue(apk?.isFile == true && aapt?.isFile == true)
        val process = ProcessBuilder(aapt!!.path, "dump", "xmltree", apk!!.path, "--file", "AndroidManifest.xml").start()
        val manifest = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())
        val service = manifest.split("E: service (line=").single { it.contains("\"com.miui.gamebooster.service.DockWindowManagerService\"") }
        val declared = service.lineSequence().first { it.contains("android:process") }.substringAfter("=\"").substringBefore('"')
        assertEquals(SecurityCenterDockHost.PROCESS, if (declared.startsWith(':')) SecurityCenterDockHost.PACKAGE + declared else declared)
    }
    @Test fun structuralResolutionAlsoAcceptsThePreviousArtifactWithoutUsingItsIdentityAsATarget() {
        val apk = System.getenv("SECURITY_CENTER_PREVIOUS_APK")?.let(::File)
        assumeTrue(apk?.isFile == true)
        val targets = SecurityCenterDockResolver(CameraDexIndex.open(apk!!.path)).resolve()
        assertTrue(targets.containsKey("m.holderUpdate"))
        assertTrue(targets.containsKey("m.editState0"))
    }
    @Test fun installedApkResolvesCompleteConnectedContract() {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue("Set SECURITY_CENTER_APK to run current-image verification", apk?.isFile == true)
        val result = SecurityCenterDockResolver(CameraDexIndex.open(apk!!.path)).resolve()
        assertTrue(result.getValue("m.append").endsWith("(Landroid/content/Context;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/util/List;I)V"))
        assertNotEquals(result.getValue("f.apps"), result.getValue("f.pinned"))
        assertTrue(result.getValue("callers").lines().size >= 3)
        println("DOCK_CONTRACT=$result")
    }
    @Test fun predictionBudgetSpansBothSourcesAndRejectsCloneOverflow() {
        for (limit in listOf(0, 8, 15)) {
            val budget = DockPredictionBudget(limit)
            // Only two native pinned models resolved; a persisted count of 20 must not add extra predictions.
            val output = mutableListOf("pin1", "pin2")
            for (source in listOf(0..9, 10..29)) {
                for (pkg in source) {
                    if (!budget.permits(output.size)) break
                    for (user in listOf(0, 999, 10)) {
                        if (budget.permits(output.size)) output.add("$pkg:$user")
                    }
                }
            }
            assertEquals(2 + limit, output.size)
        }
        val emptyFirstSource = DockPredictionBudget(8)
        assertTrue(emptyFirstSource.permits(2))
        assertFalse(emptyFirstSource.permits(10))
        val nextLoad = DockPredictionBudget(15)
        assertTrue(nextLoad.permits(2))
    }
    @Test fun capacityGrowsWithoutAllocatingAnUnboundedPlaceholderList() {
        val enabled = SecurityCenterDockSettings(unlimitedPinned = true, predictions = 15)
        assertEquals(16, enabled.slots(3))
        assertEquals(101, enabled.slots(100))
        assertTrue(DockEditCapacity.slots(17, true, pending = 3, incoming = 1) > 20)
        assertEquals(100, SecurityCenterDockSettings(predictions = 15).slots(100))
        assertEquals(0, SecurityCenterDockSettings(predictions = 0).budget)
        assertEquals(0, SecurityCenterDockSettings(unlimitedPinned = true, predictions = 0).budget)
    }
    @Test fun threeControlsAreIndependentAndPinnedModelsNeverDisappearAtTheDisplayBoundary() {
        for (pinned in listOf(false, true)) for (unlimited in listOf(false, true)) for (limit in listOf(0, 8, 15)) {
            val settings = SecurityCenterDockSettings(pinned, unlimited, limit)
            val items = List(12) { "fixed$it" } + "divider" + List(15) { "prediction$it" }
            val shown = settings.display(items) {
                when { it == "divider" -> DockItemKind.DIVIDER
                    it.startsWith("prediction") -> DockItemKind.PREDICTION
                    else -> DockItemKind.PINNED }
            }
            assertEquals(12, shown.count { it.startsWith("fixed") })
            assertTrue("divider" in shown)
            assertEquals(if (unlimited) limit else minOf(4, limit), shown.count { it.startsWith("prediction") })
            assertEquals(limit, settings.budget)
            assertEquals(if (pinned) "PINNABLE" else "UNPINNABLE", settings.editState("UNPINNABLE", "UNPINNABLE", "PINNABLE"))
            for (native in listOf("PINNED", "NONE", "PINNABLE")) assertEquals(native, settings.editState(native, "UNPINNABLE", "PINNABLE"))
        }
        val items = List(20) { DockItemKind.PINNED } + List(15) { DockItemKind.PREDICTION }
        assertEquals(20, SecurityCenterDockSettings(true, false, 15).display(items) { it }.size)
        assertEquals(35, SecurityCenterDockSettings(true, true, 15).display(items) { it }.size)
    }
    @Test fun editorContractIncludesBothModelsAndTheRendererThatConsumesTheirState() {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue(apk?.isFile == true)
        val targets = SecurityCenterDockResolver(CameraDexIndex.open(apk!!.path)).resolve()
        assertNotEquals(targets.getValue("m.editState0"), targets.getValue("m.editState1"))
        assertEquals(targets.getValue("f.pinnable").substringBefore("->"), targets.getValue("f.unpinnable").substringBefore("->"))
        assertEquals(targets.getValue("m.holderUpdate").substringBefore("->"), targets.getValue("f.holderModel").substringBefore("->"))
        assertTrue(targets.getValue("m.editorTransition").endsWith("(Ljava/lang/Object;)Ljava/lang/Object;"))
    }
    @Test fun initialEditorSlotsIgnorePredictionsAndKeepPinsWhenTheSwitchIsDisabled() {
        assertEquals(17, DockEditCapacity.slots(16, true))
        assertEquals(16, DockEditCapacity.slots(5, true))
        assertEquals(24, DockEditCapacity.slots(24, false))
    }
    @Test fun continuousAndBurstAddsReserveRealDestinationsWithoutClosingOrReopening() {
        for (batchSize in listOf(1, 4, 20)) {
            val items = MutableList(DockEditCapacity.slots(16, true)) { if (it < 16) "pin$it" else "slot" }
            var additions = 0
            repeat(12) {
                val pending = mutableListOf<Pair<Int, String>>()
                repeat(batchSize) {
                    val nativeIndex = items.count { it != "slot" } + pending.size
                    val limit = DockEditCapacity.reserve(items, pending.size, 1, { it != "slot" }) { items.add("slot") }
                    assertTrue(nativeIndex < limit)
                    assertTrue("native List.set must have a real destination", nativeIndex < items.size)
                    assertEquals("slot", items[nativeIndex])
                    pending += nativeIndex to "new${additions++}"
                }
                // Native animation completion replaces the recorded indices. No list was rebuilt.
                pending.forEach { (index, item) -> items[index] = item }
                assertEquals(16 + additions, items.count { it != "slot" })
                assertEquals(1, items.count { it == "slot" })
                assertEquals((0 until 16).map { "pin$it" }, items.take(16))
            }
        }
    }
    @Test fun editRefreshPreservesUnsavedRemovalOrderAndPendingAnimationIndices() {
        val edited = mutableListOf("pinC", "pinA", "slot") // pinB was removed but not persisted yet.
        val original = edited.take(2)
        val destination = 2
        DockEditCapacity.reserve(edited, 1, 0, { it != "slot" }) { edited.add("slot") }
        assertEquals(original, edited.take(2))
        edited[destination] = "unconfirmedAdd"
        assertFalse("pinB" in edited)
        assertEquals(listOf("pinC", "pinA", "unconfirmedAdd"), edited.filter { it != "slot" })
    }
    @Test fun capacityContractFindsTheNativeSetAndNotifyingAppendOnTheSameAdapter() {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue(apk?.isFile == true)
        val index = CameraDexIndex.open(apk!!.path)
        val targets = SecurityCenterDockResolver(index).resolve()
        val replace = index.code(targets.getValue("m.replaceSlot"))
        assertTrue(replace.calls.any { it.method.definingClass == "Ljava/util/List;" && it.method.name == "set" })
        val append = index.code(targets.getValue("m.appendSlot"))
        assertTrue(append.calls.any { it.method.name == "notifyItemInserted" })
        assertEquals(targets.getValue("m.replaceSlot").substringBefore("->"), targets.getValue("m.appendSlot").substringBefore("->"))
        assertTrue(targets.getValue("f.layoutEditing").endsWith(":Z"))
    }
    private fun fixture(duplicate: Boolean = false, missing: Boolean = false, missingEditor: Boolean = false, check: (Map<String, String>) -> Unit) {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue("Set SECURITY_CENTER_APK for mutation checks", apk?.isFile == true)
        val contract = SecurityCenterDockResolver(CameraDexIndex.open(apk!!.path)).resolve()
        val members = contract.filterKeys { it.startsWith("m.") || it.startsWith("f.") }.values + contract.getValue("callers").lines()
        val owners = (members.map { it.substringBefore("->") } + contract.getValue("model") + contract.getValue("divider") + contract.getValue("placeholder") + contract.getValue("editorOwners").lines()).distinct()
        val types = owners.mapIndexed { index, type -> type to "Lfixture/dock/Owner$index;" }.toMap()
        val names = members.distinct().mapIndexed { index, ref -> ref to "member$index" }.toMap()
        fun type(value: String) = types[value] ?: value
        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters) = Rewriter<String>(::type)
            override fun getMethodReferenceRewriter(rewriters: Rewriters) = Rewriter<MethodReference> { ref ->
                ImmutableMethodReference(type(ref.definingClass), names[CameraDexIndex.descriptor(ref)] ?: ref.name,
                    ref.parameterTypes.map { type(it.toString()) }, type(ref.returnType))
            }
            override fun getFieldReferenceRewriter(rewriters: Rewriters) = Rewriter<FieldReference> { ref ->
                ImmutableFieldReference(type(ref.definingClass), names["${ref.definingClass}->${ref.name}:${ref.type}"] ?: ref.name, type(ref.type))
            }
        })
        val folder = createTempDirectory("security-dock-contract-").toFile()
        try {
            val fixture = File(folder, "fixture.apk")
            val source = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            ZipOutputStream(fixture.outputStream()).use { output ->
                source.dexEntryNames.forEach { name ->
                    val original = source.getEntry(name)!!.dexFile
                    // Keep the entire connected owner graph; unrelated APK classes add no mutation coverage.
                    val graph = object : DexFile {
                        override fun getOpcodes() = original.opcodes
                        override fun getClasses() = original.classes.filter { it.type in owners }.toSet()
                    }
                    val selected = rewriter.dexFileRewriter.rewrite(graph).classes.filter {
                        (!missing || it.type != type(contract.getValue("m.fill").substringBefore("->"))) &&
                            (!missingEditor || it.type != type(contract.getValue("f.pinnable").substringBefore("->")))
                    }.toMutableSet()
                    val dex = object : DexFile {
                        override fun getOpcodes() = original.opcodes
                        override fun getClasses() = selected
                    }
                    val store = MemoryDataStore()
                    DexPool.writeTo(store, dex)
                    output.putNextEntry(ZipEntry(name.substringAfterLast('/')))
                    output.write(store.buffer, 0, store.size)
                    output.closeEntry()
                    store.close()
                }
                if (duplicate) {
                    val selected = source.dexEntryNames.flatMap { source.getEntry(it)!!.dexFile.classes }.filter { it.type in owners }.toSet()
                    val dex = object : DexFile {
                        override fun getOpcodes() = Opcodes.getDefault()
                        override fun getClasses() = selected
                    }
                    val store = MemoryDataStore()
                    DexPool.writeTo(store, dex)
                    output.putNextEntry(ZipEntry("classes999.dex"))
                    output.write(store.buffer, 0, store.size)
                    output.closeEntry()
                    store.close()
                }
            }
            if (duplicate || missing || missingEditor) {
                assertThrows(RuntimeException::class.java) { SecurityCenterDockResolver(CameraDexIndex.open(fixture.path)).resolve() }
            } else check(SecurityCenterDockResolver(CameraDexIndex.open(fixture.path)).resolve())
        } finally { folder.deleteRecursively() }
    }
    @Test fun renamedClassesMethodsAndFieldsStillResolve() = fixture { result ->
        assertTrue(result.getValue("m.append").startsWith("Lfixture/dock/"))
        assertTrue(result.getValue("f.pinned").contains("->member"))
    }
    @Test fun ambiguousGraphFailsClosed() = fixture(duplicate = true) {}
    @Test fun missingEditorGraphFailsClosed() = fixture(missingEditor = true) {}
    @Test fun missingCapacityGraphFailsClosed() = fixture(missing = true) {}

}
