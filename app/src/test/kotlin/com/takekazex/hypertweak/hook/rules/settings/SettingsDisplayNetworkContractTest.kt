package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class SettingsDisplayNetworkContractTest {
    companion object {
        private var index: DexOperandIndex? = null

        @JvmStatic @BeforeClass fun open() {
            val apk = System.getenv("SETTINGS_APK")
            if (!apk.isNullOrBlank() && File(apk).isFile) index = DexOperandIndex.fixture(apk)
        }
    }

    private fun index(): DexOperandIndex {
        assumeTrue("SETTINGS_APK is not set", index != null)
        return requireNotNull(index)
    }

    @Test fun `network acceleration gate resolves to the page task and the default package list`() {
        val contract = requireNotNull(LinkTurboAppsContract.resolve(index()))
        assertEquals(
            "Lcom/android/settings/wifi/linkturbo/LinkTurboAppDataTrafficTaskWithProgress;->doInBackground([Ljava/lang/Void;)Ljava/util/List;",
            DexOperandIndex.descriptor(contract.task),
        )
        assertEquals(
            "Lcom/android/settings/wifi/linkturbo/LinkTurboClient;->getLinkTurboDefaultPn()Ljava/util/List;",
            DexOperandIndex.descriptor(contract.defaults),
        )
    }

    @Test fun `screen timeout filter resolves the OLED flag and the preference entry accessors`() {
        val contract = requireNotNull(NeverScreenTimeoutContract.resolve(index()))
        assertEquals(
            "Lcom/android/settings/KeyguardTimeoutDropDownPreference;->disableUnusableTimeouts()V",
            DexOperandIndex.descriptor(contract.filter),
        )
        assertEquals("IS_OLED", contract.oled.name)
        assertEquals(listOf("getEntries", "getEntryValues", "setEntries", "setEntryValues"),
            listOf(contract.entries, contract.values, contract.setEntries, contract.setValues).map { it.name })
    }
}
