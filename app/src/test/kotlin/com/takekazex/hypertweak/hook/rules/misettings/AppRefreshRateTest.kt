package com.takekazex.hypertweak.hook.rules.misettings

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.rules.powerkeeper.AppRefreshRateContract
import com.takekazex.hypertweak.settings.contract.AppRefreshRates
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AppRefreshRateTest {
    @Test fun `stored rates skip malformed entries and round-trip`() {
        assertEquals(mapOf("a.b" to 90, "c" to 60), AppRefreshRates.parse("a.b:90;;bad;c:60;d:x;:30;e:-1"))
        assertEquals("a.b:90;c:60", AppRefreshRates.format(mapOf("c" to 60, "a.b" to 90, "" to 30)))
        assertEquals(emptyMap<String, Int>(), AppRefreshRates.parse(null))
    }

    private fun index(env: String): DexOperandIndex {
        val apk = System.getenv(env)
        assumeTrue("$env is not set", !apk.isNullOrBlank() && File(apk).isFile)
        return DexOperandIndex.fixture(requireNotNull(apk))
    }

    @Test fun `MiSettings resolves the custom high refresh app row`() {
        val contract = requireNotNull(AppRefreshRateRowsContract.resolve(index("MISETTINGS_APK")))
        assertEquals("Lab/n;->a(Landroidx/recyclerview/widget/p0;Ljava/lang/Object;I)V", DexOperandIndex.descriptor(contract.bind))
        assertEquals("Lzd/b;->onClick(Landroid/view/View;)V", DexOperandIndex.descriptor(contract.click))
        assertEquals(listOf("a", "d", "e", "a", "c", "e"),
            listOf(contract.item, contract.button, contract.row, contract.pkg, contract.label, contract.checked).map { it.name })
    }

    @Test fun `MiSettings resolves the bundled Miuix drop-down adapters`() {
        val contract = requireNotNull(MiuixDropDownContract.resolve(index("MISETTINGS_APK")))
        assertEquals("Lri/d;-><init>(Landroid/content/Context;ILandroid/widget/ArrayAdapter;Lri/b;)V", DexOperandIndex.descriptor(contract.wrapper))
        assertEquals("miuix_appcompat_simple_spinner_layout_integrated", contract.layout.name)
        assertEquals(listOf("b", "a"), listOf(contract.content, contract.entries).map { it.name })
        assertEquals("Loi/b;", contract.entries.definingClass)
    }

    @Test fun `PowerKeeper resolves the foreground per-app map lookup`() {
        val contract = requireNotNull(AppRefreshRateContract.resolve(index("POWERKEEPER_APK")))
        assertEquals(
            "Lcom/miui/powerkeeper/statemachine/DisplayFrameSetting;->onForegroundChanged(Lmiui/process/ForegroundInfo;)V",
            DexOperandIndex.descriptor(contract.foreground),
        )
        assertEquals(listOf("mTopApps", "mCustomApps", "mForegroundPackageName"),
            listOf(contract.active, contract.custom, contract.foregroundPackage).map { it.name })
    }
}
