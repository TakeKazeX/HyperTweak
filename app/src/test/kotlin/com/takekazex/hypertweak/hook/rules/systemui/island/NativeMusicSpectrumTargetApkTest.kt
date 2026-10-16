package com.takekazex.hypertweak.hook.rules.systemui.island

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeMusicSpectrumTargetApkTest {
    @Test fun `native media uses original big area and waveform contracts`() {
        val files = listOf("SYSTEMUI_PLUGIN_APK", "SYSTEMUI_APK").map { System.getenv(it)?.let(::File) }
        assumeTrue(files.all { it?.isFile == true })
        val classes = files.filterNotNull().flatMap { file ->
            val dex = DexFileFactory.loadDexContainer(file, Opcodes.getDefault())
            dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
        }.associateBy { it.type }
        fun getter(owner: ClassDef, name: String) = generateSequence(owner) { classes[it.superclass] }
            .flatMap { it.methods.asSequence() }.first { it.name == name && it.parameterTypes.isEmpty() }
        val content = classes.getValue("Lmiui/systemui/dynamicisland/window/content/DynamicIslandContentView;")
        java.util.zip.ZipFile(files.first()!!).use { apk ->
            val resources = apk.getInputStream(apk.getEntry("resources.arsc")).use { it.readBytes() }
                .toString(Charsets.ISO_8859_1)
            for (name in listOf("island_container_module_icon", "island_container_module_text"))
                assertTrue(resources.contains(name))
        }
        assertEquals("Ljava/lang/Boolean;", getter(content, "isMediaApp").returnType)
        val template = classes.getValue(getter(content, "getTemplate").returnType)
        val area = classes.getValue(getter(template, "getBigIslandArea").returnType)
        val info = classes.getValue(getter(area, "getImageTextInfoLeft").returnType)
        assertEquals(info.type, getter(area, "getImageTextInfoRight").returnType)
        assertEquals("Ljava/lang/Integer;", getter(info, "getType").returnType)
        for (name in listOf("getTextInfo", "getProgressTextInfo", "getFixedWidthDigitInfo", "getSameWidthDigitInfo")) getter(area, name)
        val text = classes.getValue(getter(info, "getTextInfo").returnType)
        for (name in listOf("getTitle", "getContent", "getFrontTitle")) assertEquals("Ljava/lang/String;", getter(text, name).returnType)
        assertEquals("V", getter(content, "updateBigIslandViewWidth").returnType)
        assertEquals("V", getter(content, "clearShadow").returnType)
        assertEquals("V", getter(content, "showShadowNoANim").returnType)
        assertTrue(content.methods.any { it.name == "setShadowAlpha" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("F") && it.returnType == "V" })
        val base = classes.getValue(checkNotNull(content.superclass))
        for (scalar in listOf("I", "F")) assertEquals(1, base.methods.count {
            it.name == "updateShadow" && it.parameterTypes.map(CharSequence::toString) == listOf(base.type, scalar) &&
                it.returnType == "V"
        })
        val fake = classes.getValue("Lmiui/systemui/dynamicisland/window/content/DynamicIslandContentFakeView;")
        assertEquals("V", getter(fake, "clearShadow").returnType)
        getter(info, "getProgressInfo")
        val pic = classes.getValue(getter(info, "getPicInfo").returnType)
        assertEquals("Ljava/lang/String;", getter(pic, "getPic").returnType)
        assertEquals("Ljava/lang/Integer;", getter(pic, "getType").returnType)
        val media = classes.getValue("Lcom/android/systemui/statusbar/notification/mediaisland/MiuiIslandMediaControllerImpl;")
        val strings = media.methods.flatMap { method ->
            method.implementation?.instructions?.mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
            }.orEmpty()
        }.toSet()
        assertTrue(strings.containsAll(setOf("musicWave", "musicPause", "miui_media_album_icon")))
    }
}
