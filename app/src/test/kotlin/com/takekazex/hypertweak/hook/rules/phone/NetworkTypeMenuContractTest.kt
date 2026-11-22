package com.takekazex.hypertweak.hook.rules.phone

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NetworkTypeMenuContractTest {
    @Test fun `TeleService resolves the UI delegate instead of the shared modem policy`() {
        val apk = System.getenv("TELESERVICE_APK")
        assumeTrue(!apk.isNullOrBlank() && File(apk).isFile)
        val contract = NetworkTypeMenuContract.resolve(DexOperandIndex.fixture(requireNotNull(apk)))
        assertNotNull(contract)
        requireNotNull(contract)
        assertEquals("Lcom/android/phone/settings/CommonOptions;->create()V", DexOperandIndex.descriptor(contract.bindPage))
        assertEquals(
            "Lcom/android/phone/TelephonyDelegateImpl;->isRemoveNetworkModeSettings(Lcom/android/internal/telephony/Phone;)Z",
            DexOperandIndex.descriptor(contract.gate),
        )
        assertEquals("prepareData", contract.search.name)
    }
}
