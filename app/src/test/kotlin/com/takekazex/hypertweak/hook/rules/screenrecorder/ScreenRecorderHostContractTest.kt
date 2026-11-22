package com.takekazex.hypertweak.hook.rules.screenrecorder

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.bootstrap.HookStep
import com.takekazex.hypertweak.hook.bootstrap.PackageHookPlan
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups
import com.takekazex.hypertweak.settings.contract.ScreenRecorderSettingsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ScreenRecorderHostContractTest {
    private fun resolver(): ScreenRecorderResolver {
        val apk = System.getenv("SCREEN_RECORDER_APK")?.let(::File)
        assumeTrue("Set SCREEN_RECORDER_APK to verify both contracts against the recorder APK", apk?.isFile == true)
        return ScreenRecorderResolver(DexOperandIndex.fixture(requireNotNull(apk).path))
    }

    @Test
    fun routesRecorderHooksAndSettingsToTheControllerProcess() {
        val pkg = ScreenRecorderSettingsKeys.PACKAGE
        val names = PackageHookPlan.steps(pkg, pkg).filterIsInstance<HookStep.Install<*>>().map { it.target.simpleName }
        assertEquals(listOf("ScreenRecorderHooker"), names)
        assertTrue(PackageHookPlan.steps(pkg, "$pkg:core").isEmpty())
        assertEquals(setOf(RuntimeSettingsGroups.SCREEN_RECORDER), RuntimeSettingsGroups.forProcess(pkg))
    }

    @Test
    fun resolvesSaveAndFolderConsumersFromHost() {
        val access = resolver().directory()
        assertTrue(access.getValue("save").endsWith("()V"))
        assertTrue(access.getValue("directory").endsWith("(Landroid/content/Context;)Ljava/io/File;"))
        assertTrue(access.getValue("exists").endsWith("(Landroid/content/Context;)Z"))
        assertTrue(access.getValue("documentUri").endsWith("()Landroid/net/Uri;"))
    }

    @Test
    fun resolvesSeparateFrameEntriesAndValuesAndTheirRecordingConsumer() {
        val access = resolver().frames()
        assertNotEquals(access.getValue("labelsSetter"), access.getValue("valuesSetter"))
        assertTrue(access.getValue("values").endsWith(":Ljava/util/ArrayList;"))
        assertTrue(access.getValue("readFrame").endsWith("()I"))
        assertTrue(access.getValue("prepare").endsWith("()V"))
    }
}
