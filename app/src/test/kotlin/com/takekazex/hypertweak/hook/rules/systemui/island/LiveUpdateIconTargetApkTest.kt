package com.takekazex.hypertweak.hook.rules.systemui.island

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveUpdateIconTargetApkTest {
    @Test fun `native promoted icon binding retains complete typed contract`() {
        val paths = listOf("SYSTEMUI_PLUGIN_APK", "SYSTEMUI_APK").map { System.getenv(it)?.let(::File) }
        assumeTrue(paths.all { it?.isFile == true })
        val classes = paths.filterNotNull().flatMap { path ->
            val container = DexFileFactory.loadDexContainer(path, Opcodes.getDefault())
            container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
        }.associateBy { it.type }
        val data = "Lcom/android/systemui/plugins/miui/dynamicisland/DynamicIslandData;"
        val holder = classes.getValue("Lmiui/systemui/dynamicisland/module/IslandIconViewHolder;")
        val base = classes.getValue(requireNotNull(holder.superclass))
        val setIcon = holder.methods.single { it.name == "setIcon" }
        assertEquals(listOf(data), setIcon.parameterTypes.map(CharSequence::toString))
        assertEquals("V", setIcon.returnType)
        val strings = setIcon.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }
        assertTrue(strings.any { "setIcon: key:" in it })
        assertEquals("Landroid/widget/ImageView;", holder.fields.single { it.name == "icon" }.type)
        val info = classes.getValue(holder.methods.single { it.name == "getPicInfo" }.returnType)
        for ((name, type) in listOf("getPic" to "Ljava/lang/String;", "getType" to "Ljava/lang/Integer;", "getEffectSrc" to "Ljava/lang/String;")) {
            assertEquals(type, info.methods.single { it.name == name }.returnType)
        }
        assertEquals(data, base.methods.single { it.name == "getDynamicIslandData" }.returnType)
        assertEquals("Landroid/os/Bundle;", classes.getValue(data).methods.single { it.name == "getExtras" }.returnType)
        val promoted = classes.getValue("Lmiui/systemui/util/liveupdate/PromotedNotificationParamUtils;")
        val bundle = promoted.methods.single { it.name == "getSmallIconBundle" }
        assertTrue(bundle.implementation!!.instructions.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == LiveUpdateIconPolicy.PICTURE
        })
    }
}
