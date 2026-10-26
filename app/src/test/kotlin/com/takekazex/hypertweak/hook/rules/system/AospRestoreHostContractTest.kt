package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AospRestoreHostContractTest {
    private fun dex(variable: String): DexOperandIndex {
        val path = System.getenv(variable)
        assumeTrue("Set $variable to the verified host artifact", path != null)
        return DexOperandIndex.fixture(path!!)
    }

    @Test fun vendorSwitchRestoresInstalledStateBeforePublishingBothRequiredPackages() {
        val dex = dex("AOSP_MIUI_SERVICES")
        val owner = "Lcom/android/server/pm/PackageManagerServiceImpl;"
        val code = dex.code("$owner->switchPackageInstaller()V")
        assertTrue(code.calls.any { it.method.name == "isCTS" && it.method.definingClass == owner })
        val restore = code.calls.indexOfFirst { it.method.name == "installExistingPackageAsUser" }
        val select = code.calls.indexOfFirst { it.method.name == "updateDefaultPkgInstallerLocked" }
        assertTrue(restore >= 0 && select > restore)
        assertTrue(code.reads.any { it.name == "mLock" })
        assertEquals(setOf("mRequiredInstallerPackage", "mRequiredUninstallerPackage"),
            code.writes.map { it.first.name }.toSet())
        val choose = dex.code("$owner->updateDefaultPkgInstallerLocked()Z")
        assertTrue(choose.calls.any { it.method.name == "setInstalled" })
        assertTrue(choose.calls.any { it.method.name == "remove" })
        assertTrue(choose.calls.any { it.method.name == "put" })
        assertTrue(choose.writes.any { it.first.name == "mCurrentPackageInstaller" })
    }

    @Test fun singletonRecoveryDoesNotNeedStateSavedByAnOlderModule() {
        val dex = dex("AOSP_SERVICES")
        val stub = "Lcom/android/server/pm/PackageManagerServiceStub;"
        val method = dex.declared(stub).single { it.name == "get" && it.parameterTypes.isEmpty() }
        assertEquals(stub, method.returnType)
        assertTrue(method.accessFlags and 8 != 0)
        assertTrue(dex.code(method).reads.any { it.name == "sInstance" && it.type == stub })
    }

    @Test fun targetedPluginReloadDisconnectsExistingInstancesThenQueriesAgain() {
        val dex = dex("SYSTEMUI_APK")
        val manager = "Lcom/android/systemui/shared/plugins/PluginActionManager;"
        assertEquals("Ljava/lang/String;", dex.classDefinition(manager)!!.fields.single { it.name == "action" }.type)
        assertTrue(dex.code("$manager->loadAll()V").calls.any { it.method.name == "execute" })
        val worker = dex.code("Lcom/android/systemui/shared/plugins/PluginActionManager\$loadAll\$1;->run()V")
        assertTrue(worker.calls.any { it.method.name == "clear" })
        assertTrue(worker.calls.any { it.method.name == "handleQueryPlugins" })
        assertTrue(worker.calls.any { it.method.name == "execute" })
        val sysUi = "Lcom/android/systemui/dagger/DaggerReferenceGlobalRootComponent\$ReferenceSysUIComponentImpl;"
        val root = dex.classDefinition(sysUi)!!.fields.single { it.name == "referenceGlobalRootComponentImpl" }.type
        assertTrue(dex.classDefinition(root)!!.fields.any { it.name == "providesPluginManagerImplProvider" })
        assertTrue(dex.classDefinition("Lcom/android/systemui/shared/plugins/PluginManagerImpl;")!!.fields.any {
            it.name == "pluginMap" && it.type == "Ljava/util/Map;"
        })
    }

    @Test fun securityEntriesHaveLifecycleAndMenuCallbacksInTheExecutingApk() {
        val dex = dex("SECURITY_CENTER_APK")
        val fragment = dex.declared("Lcom/miui/appmanager/fragment/ApplicationsDetailsFragment;")
        assertTrue(fragment.any { it.name == "onResume" && it.parameterTypes.isEmpty() })
        assertTrue(fragment.any { it.name == "onCreatePreferences" && it.parameterTypes.map(CharSequence::toString) ==
            listOf("Landroid/os/Bundle;", "Ljava/lang/String;") })
        assertTrue(dex.declared("Lmiuix/appcompat/app/AppCompatActivity;").any {
            it.name == "onOptionsMenuViewAdded" && it.parameterTypes.map(CharSequence::toString) ==
                listOf("Landroid/view/Menu;", "Landroid/view/Menu;")
        })
    }
}
