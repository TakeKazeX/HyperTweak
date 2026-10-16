package com.takekazex.hypertweak.hook.rules.systemui.island

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class IslandTimeFontTargetApkTest {
    @Test fun `native timer slots and condensed renderer retain complete typed contract`() {
        val paths = listOf("SYSTEMUI_PLUGIN_APK", "SYSTEMUI_APK").map { System.getenv(it)?.let(::File) }
        assumeTrue(paths.all { it?.isFile == true })
        val classes = paths.filterNotNull().flatMap { path ->
            val container = DexFileFactory.loadDexContainer(path, Opcodes.getDefault())
            container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
        }.associateBy { it.type }
        val prefix = "Lmiui/systemui/dynamicisland/"
        val data = "Lcom/android/systemui/plugins/miui/dynamicisland/DynamicIslandData;"
        val template = "${prefix}model/IslandTemplate;"
        val info = "${prefix}model/TextInfo;"
        val base = classes.getValue("${prefix}module/BaseIslandModuleViewHolder;")
        val font = base.methods.single { it.name == "setFont" }
        assertEquals(listOf(info, "Landroid/widget/TextView;"), font.parameterTypes.map(CharSequence::toString))
        val strings = font.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }
        assertTrue(strings.containsAll(listOf(IslandCondensedFontSource.FAMILY, IslandCondensedFontSource.AXES)))
        val model = classes.getValue(info)
        assertTrue(model.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() })
        assertEquals(listOf("Ljava/lang/Boolean;"), model.methods.single { it.name == "setNarrowFont" }.parameterTypes.map(CharSequence::toString))
        assertEquals(data, base.methods.single { it.name == "getDynamicIslandData" }.returnType)
        assertEquals("Landroid/os/Bundle;", classes.getValue(data).methods.single { it.name == "getExtras" }.returnType)
        val content = classes.getValue("${prefix}window/content/DynamicIslandBaseContentView;")
        assertTrue(content.methods.single { it.name == "getPkgName" }.implementation!!.instructions.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "miui.pkg.name"
        })
        fun isTextView(type: String): Boolean = type in setOf("Landroid/widget/TextView;", "Landroid/widget/Chronometer;") ||
            classes[type]?.superclass?.let(::isTextView) == true
        for ((name, fields) in listOf(
            "IslandSameWidthDigitViewHolder" to listOf("sameWidthDigit", "title"),
            "IslandFixedWidthDigitViewHolder" to listOf("fixedWidthDigit"),
            "IslandIconFixedWidthTextHolder" to listOf("title"),
            "IslandTextViewHolder" to listOf("title", "frontTitle", "content"),
            "IslandRightTextViewHolder" to listOf("title", "frontTitle", "content"),
            "IslandTextOverIconHolder" to listOf("title")
        )) {
            val owner = classes.getValue("${prefix}module/$name;")
            assertEquals(base.type, owner.superclass)
            val bind = owner.methods.single { it.name == "bind" }
            assertEquals(listOf(template, data), bind.parameterTypes.map(CharSequence::toString))
            assertEquals("V", bind.returnType)
            fields.forEach { assertTrue("$name.$it", isTextView(owner.fields.single { field -> field.name == it }.type)) }
            val partial = owner.methods.single { it.name == "updatePartial" }
            assertTrue("$name partial updates must rebind", partial.implementation!!.instructions.any {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref?.name == "bind" && ref.definingClass == owner.type
            })
        }
        // Existing holders must be reachable without a constructor/bind event after reload.
        val coordinator = classes.getValue("${prefix}event/DynamicIslandEventCoordinator;")
        val windowType = coordinator.methods.single { it.name == "getWindowView" }.returnType
        val window = classes.getValue(windowType)
        assertEquals("Ljava/util/List;", window.methods.single { it.name == "getContentViews" }.returnType)
        val controllerType = content.methods.single { it.name == "getController" }.returnType
        val controller = classes.getValue(controllerType)
        val factoryType = "${prefix}template/IslandTemplateFactory;"
        assertEquals(factoryType, controller.methods.single { it.name == "getIslandTemplateFactory" }.returnType)
        val factory = classes.getValue(factoryType)
        val builderType = "${prefix}template/IslandTemplateBuilder;"
        for (field in listOf("bigIslandBuilderMap", "smallIslandBuilderMap", "fakeBigIslandBuilderMap", "fakeSmallIslandBuilderMap")) {
            val entry = factory.fields.single { it.name == field }
            assertEquals("Ljava/util/Map;", entry.type)
            val signature = entry.annotations.single { it.type == "Ldalvik/annotation/Signature;" }.elements
                .single { it.name == "value" }.value.toString()
            assertTrue(signature, signature.contains(builderType))
        }
        val adapterType = "${prefix}module/IslandModuleViewHolderAdapter;"
        assertEquals(1, classes.getValue(builderType).fields.count { it.type == adapterType })
        assertEquals("Ljava/util/Map;", classes.getValue(adapterType).fields.single { it.name == "holders" }.type)
        for ((widget, span) in listOf("HyperChronometer" to "HyperChronometerEffectSpan", "TimerTextEffectView" to "TimerTextEffectSpan")) {
            val setter = classes.getValue("Lmiuix/colorful/texteffect/$widget;").methods.single {
                it.name == "setText" && it.parameterTypes.map(CharSequence::toString) ==
                    listOf("Ljava/lang/CharSequence;", "Landroid/widget/TextView\$BufferType;")
            }
            assertTrue("same-text refresh must clear cached $span", setter.implementation!!.instructions.any {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref?.name == "clear" && ref.definingClass == "Lmiuix/colorful/texteffect/$span;"
            })
        }
        // Width calculations reset the chronometer font before measuring it. The hook must
        // remain at setFixedFont as well as bind, otherwise later layout silently widens digits.
        val width = classes.getValue("${prefix}module/IslandSameWidthDigitViewHolder;")
            .methods.single { it.name == "updateWidth" }
        val calls = width.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.map { it.name }
        assertTrue(calls.indexOf("setFixedFont") in 0 until calls.indexOf("getTextViewWidth"))
    }
}
