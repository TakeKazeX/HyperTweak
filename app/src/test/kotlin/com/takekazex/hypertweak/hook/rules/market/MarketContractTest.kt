package com.takekazex.hypertweak.hook.rules.market

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups as Groups
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.MarketSettingsKeys as Keys
import com.takekazex.hypertweak.ui.page.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections

class MarketContractTest {
    @Test fun actualApkDefinesEveryBoundaryAndItsCallers() {
        val apk = System.getenv("MARKET_APK")?.let(::File)
        assumeTrue("Set MARKET_APK to validate installed host bytecode", apk?.isFile == true)
        val dex = DexOperandIndex.fixture(apk!!.path)
        val contract = MarketResolver(dex).resolve()
        println("MISSING=" + MarketTargets.all.filter { "target:${it.descriptor}" !in contract })
        println("NO_CALLERS=" + MarketTargets.all.filter { t -> contract.keys.none { it.startsWith("caller:${t.descriptor}:") } })
        println("INITIALIZERS=" + contract.values.filter { "-><clinit>" in it })
        fun called(target: MarketTarget) = contract.keys.any { it.startsWith("caller:${target.descriptor}:") }
        for (target in MarketTargets.all) assertEquals(target.descriptor, contract["target:${target.descriptor}"])
        // A store update may stop calling one redundant boundary (13.x no longer calls
        // needShowAdsWithSourceFile; isSourceFileShowAdStyle carries that path). Its hook stays
        // harmless, but every policy must still reach the host through an executing caller.
        for ((key, targets) in MarketTargets.all.groupBy { it.key }) {
            assertTrue("No executing caller for policy $key", targets.any(::called))
        }
        assertTrue("Cannot reflect class initializers", contract.values.none { "-><clinit>" in it })
        println("MARKET_CONTRACT targets=${MarketTargets.all.size} entries=${contract.size}")
    }

    @Test fun searchPolicyCopiesImmutableMapAndPreservesNativeValues() {
        val original = Collections.unmodifiableMap(mapOf("adFlag" to 1, "keyword" to "app", "aiSearchSwitch" to true))
        val result = MarketTargets.withoutSuggestionAds(original)!!
        assertEquals(1, original["adFlag"])
        assertEquals(0, result["adFlag"])
        assertEquals("app", result["keyword"])
        assertEquals(true, result["aiSearchSwitch"])
        assertNull(MarketTargets.withoutSuggestionAds(null))
        assertNull(MarketTargets.withoutSuggestionAds("wrong type"))
        assertNull(MarketTargets.withoutSuggestionAds(mapOf("keyword" to "app")))
    }

    @Test fun marketPolicyEnableDisableResetAndOldGenerationRestoreUseDispatcher() {
        assertEquals(Keys.keys, MarketTargets.all.map { it.key }.toSet())
        for (process in listOf(Keys.PACKAGE, "${Keys.PACKAGE}:pushservice", "${Keys.PACKAGE}:guard", "${Keys.PACKAGE}:widgetProvider", "${Keys.PACKAGE}:cloudProfileCollect")) {
            assertTrue(Groups.MARKET in Groups.forProcess(process))
            val values = Keys.keys.associateWith { true }
            assertEquals(values, Groups.subset(Groups.target(process), values + ("unknown" to true)))
            val ledger = RuntimeSettingsLedger()
            assertTrue(ledger.accept(RuntimeSettingsSnapshot.read(1, values, setOf(Groups.MARKET))!!))
            val restored = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
            assertTrue(restored.accept(RuntimeSettingsSnapshot.read(2, Keys.keys.associateWith { false }, setOf(Groups.MARKET))!!))
            Keys.keys.forEach { assertEquals(false, restored.value(it)) }
            assertTrue(restored.accept(RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>(), setOf(Groups.MARKET))!!))
            Keys.keys.forEach { assertNull(restored.value(it)) }
        }
        Keys.keys.forEach { assertNull(RuntimeSettingsSnapshot.read(4, mapOf(it to "true"))) }
    }

    @Test fun appPagesRestoreUnderPurification() {
        for (page in listOf(Route.Market, Route.Community)) {
            val stack = listOf(Route.Main, Route.Purification, page)
            assertEquals(stack, stack.map { routeFromSaveKey(it.saveKey) })
        }
    }
}
