package com.takekazex.hypertweak.hook.rules.systemui.island

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeMediaCoverTargetApkTest {
    @Test fun `summary direct publication and expanded native flip have compatible lifecycle contracts`() {
        val paths = listOf("SYSTEMUI_PLUGIN_APK", "SYSTEMUI_APK").map { System.getenv(it)?.let(::File) }
        assumeTrue(paths.all { it?.isFile == true })
        fun classes(path: File) = DexFileFactory.loadDexContainer(path, Opcodes.getDefault()).let { dex ->
            dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        }
        val plugin = classes(paths[0]!!)
        val system = classes(paths[1]!!)
        fun calls(method: org.jf.dexlib2.iface.Method) = method.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.toList()
        val holder = plugin.getValue("Lmiui/systemui/dynamicisland/module/IslandIconViewHolder;")
        val setter = holder.methods.single { it.name == "setFixIcon" }
        assertEquals(listOf("Lcom/android/systemui/plugins/miui/dynamicisland/DynamicIslandData;"), setter.parameterTypes)
        assertEquals("V", setter.returnType)
        assertTrue(calls(setter).any { it.name == "setImageDrawable" })
        assertFalse(calls(setter).any { it.name.contains("Flip") })
        assertEquals("Landroid/widget/ImageView;", holder.fields.single { it.name == "fixIcon" }.type)
        listOf("onHidden", "onDetach", "getPicInfo").forEach { name ->
            assertEquals(1, holder.methods.count { it.name == name && it.parameterTypes.isEmpty() })
        }
        val root = "Lcom/android/systemui/statusbar/notification/mediacontrol/MiuiMediaAlbumAnimationUtils"
        val utility = system.getValue("$root;")
        assertTrue(utility.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() })
        val listener = system.getValue("$root\$OnFlipListener;")
        println("Native cover utility annotations: ${utility.annotations.map { it.type }}")
        assertEquals(listOf("onFlip"), listener.methods.map { it.name })
        val start = utility.methods.single { it.name == "startFlipAnimation" }
        assertEquals(listOf("Landroid/view/View;", "Z", "Z", listener.type), start.parameterTypes)
        assertTrue(calls(start).any { it.name == "onFlip" }) // host performance-gated fallback
        val card = system.getValue("Lcom/android/systemui/statusbar/notification/mediaisland/MiuiIslandMediaViewBinderImpl;")
        assertTrue(calls(card.methods.single { it.name == "startFlipAnimation" }).any {
            it.definingClass == utility.type && it.name == start.name
        })
        assertTrue(system.getValue("Lmiuix/animation/Folme;").methods.any {
            it.name == "clean" && it.parameterTypes == listOf("Landroid/view/View;")
        })
        assertTrue(system.getValue("Lmiuix/core/util/MiuiBlurUtils;").methods.any {
            it.name == "setSelfBlur" && it.parameterTypes == listOf("Landroid/view/View;", "I", "Ljava/util/ArrayList;")
        })
    }
}
