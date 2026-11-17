package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.DockShortcutSelection
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterDockHost
import com.takekazex.hypertweak.hook.rules.securitycenter.sidebar.SecurityCenterDockShortcutsResolver
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SecurityCenterDockShortcutsContractTest {
    private data class Item(val id: String, val enabled: Boolean, val manifest: Boolean, val rank: Int)

    @Test fun launcherOrderingPutsManifestFirstSortsByRankAndKeepsEveryEnabledShortcut() {
        val items = listOf(
            Item("qr", true, false, 2), Item("pay", true, false, 0), Item("scan", true, false, 1),
            Item("off", false, true, 0), Item("static", true, true, 5), Item("extra", true, false, 3),
        )
        val picked = DockShortcutSelection.select(items, Item::enabled, Item::manifest, Item::rank).map(Item::id)
        assertEquals(listOf("static", "pay", "scan", "qr", "extra"), picked)
        assertTrue(DockShortcutSelection.select(emptyList<Item>(), Item::enabled, Item::manifest, Item::rank).isEmpty())
    }

    @Test fun switchesRouteLiveToTheSidebarProcessOnlyAndSurviveUpgradeAndReset() {
        for (key in listOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_APP_SHORTCUTS,
            PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_APP_SHORTCUTS_FREEFORM)) routes(key)
    }

    private fun routes(key: String) {
        val groups = setOf(RuntimeSettingsGroups.SECURITY_DOCK_SHORTCUTS)
        assertEquals(groups, RuntimeSettingsGroups.affected(key))
        // A separate group keeps toggling it from rebuilding the native dock models.
        assertFalse(RuntimeSettingsGroups.SECURITY_DOCK in RuntimeSettingsGroups.affected(key))
        assertTrue(groups.single() in RuntimeSettingsGroups.forProcess(SecurityCenterDockHost.PROCESS))
        assertFalse(RuntimeSettingsGroups.owns("com.android.systemui", key))
        assertNull(RuntimeSettingsSnapshot.read(2, mapOf(key to 1), groups))
        val ledger = RuntimeSettingsLedger().also { it.restore(arrayOf<Any>(1L, hashMapOf("icon_duo_enabled" to true))) }
        for ((offset, value) in listOf(true, false, true).withIndex()) {
            assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(offset + 2L, mapOf(key to value), groups)!!))
            assertEquals(value, RuntimeSettingsLedger().also { it.restore(ledger.save()) }.value(key))
        }
        assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(10, emptyMap<String, Any>(), groups)!!))
        assertNull(ledger.value(key))
    }

    @Test fun currentHostResolvesMenuDismissAndAppModel() {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue("Set SECURITY_CENTER_APK for host contracts", apk?.isFile == true)
        val result = SecurityCenterDockShortcutsResolver(DexOperandIndex.fixture(apk!!.path)).resolve()
        assertEquals(setOf("show", "dismiss", "collapse", "menu", "appGetter", "uid", "pkg", "callers"), result.keys)
        val owner = result.getValue("show").substringBefore("->")
        assertTrue(result.getValue("dismiss").startsWith("$owner->"))
        assertTrue(result.getValue("collapse").startsWith("$owner->") && result.getValue("collapse").endsWith("(I)V"))
        assertTrue(result.getValue("menu").startsWith("$owner->"))
        assertTrue(result.getValue("uid").endsWith(":I"))
        assertTrue(result.getValue("pkg").endsWith(":Ljava/lang/String;"))
        assertEquals(result.getValue("uid").substringBefore("->"), result.getValue("pkg").substringBefore("->"))
        assertTrue(result.getValue("callers").isNotBlank())
        println("DOCK_SHORTCUTS_CONTRACT=$result")
    }
}
