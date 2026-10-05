package com.takekazex.hypertweak.hook.rules.personalassistant

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real target bytecode proof for the selectors used by the runtime resolver. */
class AndroidWidgetPickerApkTest {
    @Test fun `current assistant has unique native entry navigator model geometry and Miuix contracts`() {
        val apk = System.getenv("PERSONAL_ASSISTANT_APK")?.let(::File)
        assumeTrue(apk?.isFile == true)
        val container = DexFileFactory.loadDexContainer(apk!!, Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { name -> container.getEntry(name)!!.dexFile.classes }
        val methods = classes.flatMap { it.methods }
        fun strings(method: Method) = (method.implementation?.instructions ?: emptyList()).mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }
        fun calls(method: Method) = (method.implementation?.instructions ?: emptyList()).mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }
        fun marker(text: String) = methods.filter { method -> strings(method).any { text in it } }.single()
        val producer = marker("moveClassicWidgetEntryToEnd: appended classic widget entry")
        val entry = calls(producer).filter { it.definingClass == producer.definingClass && it.parameterTypes.isEmpty() && it.returnType == "Z" }.single()
        assertTrue(entry.parameterTypes.isEmpty())
        val mainProducer = marker("loadAppList cancelled: ")
        fun fields(method: Method) = (method.implementation?.instructions ?: emptyList()).mapNotNull {
            (it as? ReferenceInstruction)?.reference as? FieldReference
        }.distinctBy { "${it.definingClass}/${it.name}" }
        val owners = fields(mainProducer).map { it.type }.toSet()
        val mainCalls = calls(mainProducer).filter { it.definingClass in owners }
        val mainEntry = (mainCalls + mainCalls.flatMap { call ->
            calls(methods.single { it.definingClass == call.definingClass && it.name == call.name &&
                it.parameterTypes == call.parameterTypes && it.returnType == call.returnType })
        }).filter { call ->
            call.definingClass in owners && call.parameterTypes.isEmpty() && call.returnType == "Z" &&
                methods.single { it.definingClass == call.definingClass && it.name == call.name &&
                    it.parameterTypes.isEmpty() && it.returnType == "Z" }.let { method ->
                    fields(method).size == 1 && fields(method).single().type == "I"
                }
        }.distinctBy { "${it.definingClass}/${it.name}" }.single()
        assertTrue(mainEntry.definingClass in owners)
        val navigator = marker("open_classic_picker")
        assertEquals("V", navigator.returnType)
        assertEquals(1, navigator.parameterTypes.size)
        assertTrue(navigator.accessFlags and 8 != 0)
        val overlay = marker("sOverlayRef: ")
        assertTrue(overlay.accessFlags and 8 != 0)
        assertEquals(overlay.definingClass, overlay.returnType)
        val cellInit = marker("sWidgetCellWidth = ")
        val cell = classes.single { it.type == cellInit.definingClass }
        assertEquals(4, cell.fields.count { it.type == "I" && it.accessFlags and 8 != 0 })
        val chooser = classes.single { cls -> cls.methods.any { method ->
            method.parameterTypes.map(CharSequence::toString) == listOf("[Ljava/lang/CharSequence;", "I", "Landroid/content/DialogInterface\$OnClickListener;")
        } && cls.type.startsWith("Lmiuix/appcompat/app/AlertDialog\$") }
        assertEquals(1, chooser.methods.count { method -> method.name == "<init>" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "I") })
        val item = classes.single { it.type == "Lcom/miui/personalassistant/widget/iteminfo/AppWidgetItemInfo;" }
        assertEquals(1, item.methods.count { it.name == "<init>" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/appwidget/AppWidgetProviderInfo;") })
        val byType = classes.associateBy { it.type }
        fun allFields(type: String) = generateSequence(byType[type]) { byType[it.superclass] }.flatMap { it.fields.asSequence() }.toList()
        assertEquals(1, allFields(entry.definingClass).count { it.type == navigator.parameterTypes.single().toString() })
        assertEquals(1, allFields(overlay.definingClass).count { it.type == "Lcom/miui/personalassistant/core/view/AssistContentView;" })
        assertEquals(1, byType.getValue("Lcom/miui/personalassistant/core/view/AssistContentView;").methods.count {
            it.parameterTypes.isEmpty() && it.returnType == "Lcom/miui/personalassistant/widget/WidgetContainer;"
        })
        val containerMethods = byType.getValue("Lcom/miui/personalassistant/widget/WidgetContainer;").methods.filter {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) ==
                listOf("Landroid/view/View;", "Lcom/miui/personalassistant/widget/entity/ItemInfo;")
        }
        assertEquals("the old signature-only predicate is ambiguous", 2, containerMethods.size)
        val add = containerMethods.filter { NativeWidgetAddContract.isRequiredOperation(it.accessFlags) }.single()
        assertEquals("addWidget", add.name)
        val footer = methods.filter { method -> method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "Landroid/os/Bundle;") &&
            strings(method).contains("mPickerFooter") }.single()
        assertEquals(1, allFields(footer.definingClass).count { it.type == navigator.parameterTypes.single().toString() })
        assertEquals(1, byType.getValue(footer.definingClass).methods.count { it.name == "onStart" && it.parameterTypes.isEmpty() })
        val builderTitle = chooser.methods.filter { it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;") }
            .filter { fields(it).any { f -> f.name == "mTitle" } }
        assertEquals("title setter", 1, builderTitle.size)
        val builderCreate = chooser.methods.filter { it.parameterTypes.isEmpty() && it.returnType == "Lmiuix/appcompat/app/AlertDialog;" }
            .filter { calls(it).none { c -> c.name == "show" && c.parameterTypes.isEmpty() } }
        assertEquals("dialog factory without show", 1, builderCreate.size)
        val configure = methods.filter { method -> method.returnType == "Landroid/content/Intent;" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("I", "Landroid/content/Context;") &&
            strings(method).containsAll(listOf("extra_appwidget_host_id", "extra_appwidget_id")) }.single()
        assertTrue(configure.accessFlags and 8 != 0)
    }
}
