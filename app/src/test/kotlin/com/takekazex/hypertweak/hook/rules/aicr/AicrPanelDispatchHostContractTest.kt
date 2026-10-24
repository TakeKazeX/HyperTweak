package com.takekazex.hypertweak.hook.rules.aicr

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Verifies the real EventManager route, rather than merely finding an unused service parser. */
class AicrPanelDispatchHostContractTest {
    @Test fun installedPanelDispatchSerializesTheTypedRequestBeforeReportingTheEvent() {
        val path = System.getenv("AICR_APK")
        assumeTrue("Set AICR_APK to verify the actual installed host", path != null)
        val container = DexFileFactory.loadDexContainer(File(path!!), Opcodes.getDefault())
        val request = "Lcom/xiaomi/aicr/decisionframework/model/tool/ToolRequest;"
        val matches = container.dexEntryNames.asSequence().flatMap { entry ->
            container.getEntry(entry)!!.dexFile.classes.asSequence()
        }.flatMap { it.methods.asSequence() }.filter { method ->
            method.returnType == "Ljava/lang/Object;" && method.parameterTypes.map(CharSequence::toString) ==
                listOf(request, "Lkotlin/coroutines/Continuation;")
        }.filter { method ->
            val strings = method.implementation?.instructions?.filterIsInstance<ReferenceInstruction>()
                ?.mapNotNull { (it.reference as? StringReference)?.string }.orEmpty()
            AicrDefaultBrowserCache.PANEL_DISPATCH_MARKER in strings && "EventEntity reported" in strings
        }.toList()
        assertEquals("The dispatcher must resolve uniquely without obfuscated names", 1, matches.size)
        val calls = matches.single().implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }
        val read = calls.indexOfFirst { it.definingClass == request && it.name == "getInput" }
        val serialized = calls.indexOfFirst { it.definingClass == "Ljava/lang/Object;" && it.name == "toString" }
        val dispatched = calls.indexOfFirst {
            it.definingClass == "Lcom/xiaomi/aicr/hce/core/EventManager;" && it.name == "reportEvent"
        }
        assertTrue(read >= 0 && serialized > read && dispatched > serialized)
        assertTrue(calls.any { it.definingClass.endsWith("/EventManager\$EventEntity;") && it.name == "<init>" })
    }
    @Test fun nativeBrowserAllowlistHasOneRendererModelAndPersistentStateLoader() {
        val path = System.getenv("AICR_APK")
        assumeTrue("Set AICR_APK to verify the native settings host", path != null)
        val container = DexFileFactory.loadDexContainer(File(path!!), Opcodes.getDefault())
        val classes = container.dexEntryNames.asSequence().flatMap {
            container.getEntry(it)!!.dexFile.classes.asSequence()
        }.associateBy { it.type }
        val renderers = classes.values.asSequence().flatMap { it.methods.asSequence() }.filter { method ->
            method.returnType == "V" && method.parameterTypes.size == 2 &&
                method.parameterTypes[1].toString() == "Ljava/util/List;" &&
                method.implementation?.instructions?.filterIsInstance<ReferenceInstruction>()?.any {
                    (it.reference as? StringReference)?.string == "renderAppList: shown="
                } == true
        }.toList()
        assertEquals(1, renderers.size)
        val renderer = renderers.single()
        val fragment = classes.getValue(renderer.parameterTypes[0].toString())
        assertTrue(fragment.fields.any { it.name == "appPrefByKey" && it.type == "Ljava/util/Map;" })
        val models = renderer.implementation!!.instructions.filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }.mapNotNull { classes[it.definingClass] }.distinct()
            .filter { model -> model.fields.count { it.name in setOf("name", "packageName") && it.type == "Ljava/lang/String;" } == 2 }
        assertEquals(1, models.size)
        assertEquals(1, models.single().methods.count { it.name == "<init>" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;") })
        val viewModels = fragment.methods.filter { it.parameterTypes.isEmpty() }.mapNotNull { classes[it.returnType] }
            .filter { vm -> vm.fields.any { it.name == "_appSwitchStates" } && vm.fields.any { it.name == "_supportedApps" } }
        assertEquals(1, viewModels.size)
        val vm = viewModels.single()
        assertEquals(1, vm.fields.count { it.type == "Landroid/content/Context;" })
        assertEquals(1, vm.methods.count { it.returnType == "Ljava/lang/Object;" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/util/List;",
                "Lkotlin/coroutines/jvm/internal/ContinuationImpl;") })
        val preference = classes.getValue("Landroidx/preference/Preference;")
        assertEquals(1, preference.methods.count { it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/graphics/drawable/Drawable;") })
    }

    @Test fun collapsedBubbleBindsPlainTextWithoutConsumingTheNativeHighlightRanges() {
        val path = System.getenv("AICR_APK")
        assumeTrue("Set AICR_APK to inspect the native renderer", path != null)
        val container = DexFileFactory.loadDexContainer(File(path!!), Opcodes.getDefault())
        val matches = container.dexEntryNames.asSequence().flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .flatMap { it.methods.asSequence() }.filter { method ->
                method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) ==
                    listOf("Lcom/xiaomi/ai/bubble/core/model/CueData;")
            }.filter { method ->
                val strings = method.implementation?.instructions?.filterIsInstance<ReferenceInstruction>()
                    ?.mapNotNull { (it.reference as? StringReference)?.string }.orEmpty()
                "schedule_create" in strings && "document_extract" in strings
            }.toList()
        assertEquals(1, matches.size)
        val references = matches.single().implementation!!.instructions.filterIsInstance<ReferenceInstruction>().map { it.reference }
        val calls = references.filterIsInstance<MethodReference>()
        assertTrue(calls.any { it.name == "cleanTextAndHighlights" })
        assertTrue(calls.any { it.definingClass == "Landroid/widget/TextView;" && it.name == "setText" })
        assertFalse("Baseline changed: re-evaluate whether a repair is still needed",
            references.filterIsInstance<org.jf.dexlib2.iface.reference.FieldReference>().any { it.name == "highlights" })
        assertFalse(calls.any { it.name == "setSpan" })
    }

}
