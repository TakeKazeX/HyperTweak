package com.takekazex.hypertweak.hook.rules.systemui

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Revalidate the declared/public distinction moved from instance caches to HostAccess. */
class MonetQueryHostContractTest {
    private fun classes(): Map<String, ClassDef> {
        val file = System.getenv("SYSTEMUI_APK")?.let(::File)
        assumeTrue("Set SYSTEMUI_APK to the verified installed target", file?.isFile == true)
        val dex = DexFileFactory.loadDexContainer(file!!, Opcodes.getDefault())
        return dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    }

    @Test fun controllerMetadataAndActualUserTrackerHaveTheRequiredContracts() {
        val classes = classes()
        val controller = classes.getValue("Lcom/android/systemui/theme/ThemeOverlayController;")
        val fields = controller.fields.associateBy { it.name }
        assertEquals("Landroid/util/SparseArray;", fields.getValue("mCurrentColors").type)
        assertEquals("Landroid/content/Context;", fields.getValue("mContext").type)
        assertEquals("Landroid/app/WallpaperManager;", fields.getValue("mWallpaperManager").type)
        assertEquals("Ljava/util/concurrent/Executor;", fields.getValue("mMainExecutor").type)
        assertTrue(fields.getValue("mUserTracker").type.contains("UserTracker"))
        val tracker = classes.getValue("Lcom/android/systemui/settings/UserTrackerImpl;")
        val userId = tracker.methods.single { it.name == "getUserId" && it.parameterTypes.isEmpty() }
        assertEquals("I", userId.returnType)
        assertEquals("public instance getter", 1, userId.accessFlags and (1 or 8))
        val handle = controller.methods.single { it.name == "handleWallpaperColors" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/app/WallpaperColors;", "I", "I") }
        assertEquals("V", handle.returnType)
    }

    @Test fun fallbackManagerAndLazyDelegateAreResolvedWithFullSignatures() {
        val classes = classes()
        val manager = classes.getValue("Lcom/miui/systemui/interfacesmanager/InterfacesImplManager;")
        val get = manager.methods.single { it.name == "getImpl" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Class;") }
        assertEquals("static accessor", 8, get.accessFlags and 8)
        val delegate = classes.getValue("Lcom/miui/systemui/theme/ThemeOverlayControllerDelegate;")
        assertEquals("Ldagger/Lazy;", delegate.fields.single { it.name == "themeOverlayController" }.type)
    }
}
