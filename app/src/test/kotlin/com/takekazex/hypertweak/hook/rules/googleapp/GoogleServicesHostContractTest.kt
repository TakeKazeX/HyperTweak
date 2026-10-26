package com.takekazex.hypertweak.hook.rules.googleapp

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Real, hashed host artifacts; these tests establish contracts, never visible UI acceptance. */
class GoogleServicesHostContractTest {
    private fun artifact(variable: String, hash: String): File {
        val path = System.getenv(variable)
        assumeTrue("Set $variable to the verified target artifact", !path.isNullOrBlank())
        val file = File(path!!)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(131072)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        assertEquals(hash, digest.digest().joinToString("") { "%02x".format(it) })
        return file
    }

    @Test fun googleConstructorFlagsHaveUniqueAssignmentsAndAnExistingFragmentOwnsThePeer() {
        val apk = artifact("GOOGLE_APP_APK", "693c70e37b329840e93fe1b1683a251fea773f511adb4aa01b396193b8936ae2")
        val index = DexOperandIndex(listOf(apk))
        val constructor = index.declared("Ldxue;").single { it.name == "<init>" }
        val parameters = constructor.parameterTypes.map { it.toString() }
        val boundary = parameters.indexOf("Ljava/util/concurrent/Executor;")
        val indices = (boundary - 1 downTo 0).takeWhile { parameters[it] == "Z" }.toSet()
        assertEquals(listOf("Ldxue;->v:Z", "Ldxue;->w:Z", "Ldxue;->x:Z", "Ldxue;->N:Z"),
            AskScreenModelContract.fields(index, MethodDescriptorCodec.descriptor(constructor), indices))
        val peers = parameters.filter { it.startsWith("L") }.flatMap { type ->
            index.classDefinition(type)?.fields?.filter { it.type == "Ldxue;" }?.toList().orEmpty()
        }.map { MethodDescriptorCodec.descriptor(it) }.distinct()
        assertEquals(listOf("Ldxtt;->c:Ldxue;"), peers)
        val cached = index.declared("Ldtbp;").single { it.name == "<init>" }
        assertTrue(index.code(cached).writes.any { (field, value) ->
            field.name == "c" && value is DexOperandIndex.Value.Call && value.method.returnType == "Z"
        })
        assertNull(AskScreenModelContract.fields(index, MethodDescriptorCodec.descriptor(constructor), emptySet()))
        assertNull(AskScreenModelContract.fields(index, MethodDescriptorCodec.descriptor(constructor), setOf(0)))
    }

    @Test fun featureQueriesUseTheNativeMapAndSettingsRebuildsTheNativeAdapter() {
        val services = artifact("GOOGLE_SERVICES_JAR", "bc1b608423c3869e3a93f69c22fba7f486ffb439cc1a0e4ad279fd0b9b2efd05")
        val index = DexOperandIndex(listOf(services))
        val has = index.code("Lcom/android/server/pm/PackageManagerService;->hasSystemFeature(Ljava/lang/String;I)Z")
        assertTrue(has.reads.any { it.name == "mAvailableFeatures" })
        val list = index.code("Lcom/android/server/pm/PackageManagerService\$IPackageManagerImpl;->getSystemAvailableFeatures()Landroid/content/pm/ParceledListSlice;")
        assertTrue(list.calls.any { it.method.definingClass == "Landroid/content/pm/ParceledListSlice;" && it.method.name == "<init>" })
        val settings = artifact("GOOGLE_SETTINGS_APK", "2ad1f138457c5f60b045cf41e18babc7e8bcc36c123526f7ddc6ca15b906d655")
        val settingsIndex = DexOperandIndex(listOf(settings))
        val rebuild = settingsIndex.code("Lcom/android/settings/MiuiSettings;->updateHeader()V")
        assertTrue(rebuild.calls.any { it.method.name == "buildAdapter" })
        val headers = settingsIndex.code("Lcom/android/settings/BasePreferenceFragment;->buildHeaders()V")
        assertTrue(headers.calls.any { it.method.name == "loadHeadersFromResource" })
        assertTrue(headers.calls.any { it.method.name == "updateHeaderList" })
        assertNotNull(settingsIndex.definition("Lcom/android/settings/applications/credentials/CredentialManagerPreferenceController\$CombiPreference;->onBindViewHolder(Landroidx/preference/PreferenceViewHolder;)V"))
        assertTrue(settingsIndex.declared("Lcom/android/settings/MiuiSettings;").any { it.name == "AddGoogleSettingsHeaders" && it.parameterTypes.map { it.toString() } == listOf("Ljava/util/List;") })
    }

    @Test fun powerkeeperAndGreezeKeepTheCurrentBooleanNetworkContracts() {
        val power = artifact("GOOGLE_POWERKEEPER_APK", "164f2b6271eef11afaf5c97984011f01329173099accf69bada3b815a26cbd44")
        val index = DexOperandIndex(listOf(power))
        assertNotNull(index.definition("Lcom/miui/powerkeeper/utils/GmsObserver;->updateFrameworkGmsNetStatus(Z)Z"))
        val vendor = artifact("GOOGLE_MIUI_SERVICES_JAR", "f1eb7926d86ce4a4f41ab35ffcb90d9d52d89ce8a3e42ba4d737691c1e000693")
        val vendorIndex = DexOperandIndex(listOf(vendor))
        val network = vendorIndex.code("Lcom/miui/server/greeze/power/AwareResourceControl;->isNetworkDisableAble(Ljava/lang/String;)Z")
        assertTrue(network.reads.any { it.name == "mNoNetworkBlackUids" })
    }
}
