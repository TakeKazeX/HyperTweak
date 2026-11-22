package com.takekazex.hypertweak.hook.rules.systemui.island

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeMultiSessionMediaTargetApkTest {
    @Test fun `native publisher constructs each payload before consulting the plugin`() {
        val paths = listOf("SYSTEMUI_APK", "SYSTEMUI_PLUGIN_APK").map { System.getenv(it)?.let(::File) }
        assumeTrue(paths.all { it?.isFile == true })
        val types = paths.filterNotNull().flatMap { file ->
            val dex = DexFileFactory.loadDexContainer(file, Opcodes.getDefault())
            dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
        }.associateBy { it.type }
        val sys = "Lcom/android/systemui/"
        val mediaIsland = sys + "statusbar/notification/mediaisland/"
        val publisher = mediaIsland + "MiuiIslandMediaControllerImpl;"
        val holder = mediaIsland + "MiuiIslandMediaViewHolder;"
        val data = sys + "plugins/miui/dynamicisland/DynamicIslandData;"
        val content = sys + "plugins/miui/dynamicisland/DynamicIslandContent;"
        val media = sys + "media/controls/shared/model/MediaData;"
        val write = types.getValue(publisher).methods.single {
            it.name == "addDynamicIslandView" && it.parameterTypes == listOf(holder, holder, media)
        }
        val refs = write.references()
        val fieldsBeforePlugin = refs.takeWhile {
            it !is MethodReference || it.returnType != content
        }
        assertTrue(fieldsBeforePlugin.filterIsInstance<FieldReference>().any {
            it.definingClass == media && it.name == "notificationKey"
        })
        val payloadMethods = fieldsBeforePlugin.filterIsInstance<MethodReference>()
            .filter { it.definingClass == data }.map { it.name }
        assertTrue(payloadMethods.containsAll(listOf("<init>", "setTickerData", "setView", "setFakeView", "setKey")))
        val add = refs.filterIsInstance<MethodReference>().filter { it.definingClass == content }
        assertTrue(add.any { it.name == "addDynamicIslandView" && it.parameterTypes == listOf(data, "Z") })
        assertTrue(add.any { it.name == "updateDynamicIslandView" && it.parameterTypes == listOf(data, "Z") })
        assertTrue(fieldsBeforePlugin.filterIsInstance<StringReference>().map { it.string }
            .containsAll(listOf("bigIslandArea", "smallIslandArea")))
        assertFalse(fieldsBeforePlugin.filterIsInstance<MethodReference>().any { it.definingClass == content })

        val window = "Lmiui/systemui/dynamicisland/window/DynamicIslandWindowView;"
        val windowMethods = types.getValue(window).methods.toList()
        val admission = windowMethods.single { it.name == "addDynamicIslandDataSuspend" }
            .references().filterIsInstance<MethodReference>().filter { it.definingClass == window }
            .map { it.name }
        assertTrue(admission.indexOf("getViewFromList") in 0 until admission.indexOf("hasPendingMedia"))
        assertTrue(admission.indexOf("hasPendingMedia") in 0 until admission.indexOf("handleContentViewAddOperate"))
        val reuse = windowMethods.single {
            it.name == "hasPendingMedia" && it.parameterTypes == listOf(data) && it.returnType == "Z"
        }.references()
        assertTrue(reuse.filterIsInstance<FieldReference>().any {
            it.definingClass == window && it.name == "pendingMediaView"
        })
        assertTrue(reuse.filterIsInstance<MethodReference>().any {
            it.name == "setCurrentIslandData" && it.parameterTypes == listOf(data)
        })
    }

    private fun Method.references() = checkNotNull(implementation).instructions
        .filterIsInstance<ReferenceInstruction>().map { it.reference }
}
