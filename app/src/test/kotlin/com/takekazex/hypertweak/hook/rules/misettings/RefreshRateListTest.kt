package com.takekazex.hypertweak.hook.rules.misettings

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RefreshRateListTest {
    @Test fun `panel rates are added only up to the native maximum`() {
        assertArrayEquals(intArrayOf(120, 60, 90), RefreshRateListHooker.merge(intArrayOf(120, 60), listOf(120, 90, 60)))
        assertArrayEquals(intArrayOf(120, 60), RefreshRateListHooker.merge(intArrayOf(120, 60), listOf(144, 120, 60)))
        assertArrayEquals(intArrayOf(), RefreshRateListHooker.merge(intArrayOf(), listOf(90)))
    }

    @Test fun `MiSettings resolves the fps list helper and the drop-down page`() {
        val apk = System.getenv("MISETTINGS_APK")
        assumeTrue(!apk.isNullOrBlank() && File(apk).isFile)
        val contract = requireNotNull(RefreshRateListContract.resolve(DexOperandIndex.fixture(requireNotNull(apk))))
        assertEquals("[I", contract.rates.returnType)
        assertEquals(
            "Lcom/xiaomi/misettings/display/RefreshRate/NewRefreshRateFragment;->onCreatePreferences(Landroid/os/Bundle;Ljava/lang/String;)V",
            DexOperandIndex.descriptor(contract.page),
        )
    }
}
