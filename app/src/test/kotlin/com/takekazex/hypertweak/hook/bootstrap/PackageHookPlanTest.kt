package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups
import com.takekazex.hypertweak.settings.contract.PhraseSettingsKeys
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PackageHookPlanTest {
    private fun names(steps: List<HookStep>) = steps.map {
        when (it) {
            is HookStep.Install<*> -> it.target.simpleName
            HookStep.Icons -> "@icons"
        }
    }

    @Test fun knownRoutesKeepTheirBaselineHooksInOrder() {
        val json = requireNotNull(javaClass.getResourceAsStream("/hook-package-plan-baseline.json"))
            .bufferedReader().use { it.readText() }
        val cases = JSONArray(json)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val pkg = case.getString("package")
            val process = case.getString("process")
            val targets = case.getJSONArray("targets")
            val expected = (0 until targets.length()).map { targets.getString(it) }
            val actual = names(PackageHookPlan.steps(pkg, process))
            val existing = if (expected.isEmpty()) actual else actual.filter { it in expected }
            assertEquals("$pkg/$process", expected, existing)
        }
    }

    @Test fun copyDirectFloatingWindowsInstallInBothActionProcesses() {
        val pkg = "com.xiaomi.aicr"
        for (process in listOf("$pkg:cognitionService", "$pkg:actionprovider")) {
            assertTrue(names(PackageHookPlan.steps(pkg, process)).contains("AicrCopyDirectFreeformHooker"))
        }
        assertFalse(names(PackageHookPlan.steps(pkg, pkg)).contains("AicrCopyDirectFreeformHooker"))
    }

    @Test fun linkTurboAppsInstallInTheSettingsMainProcess() {
        val settings = "com.android.settings"
        assertTrue(names(PackageHookPlan.steps(settings, settings)).contains("LinkTurboAppsHooker"))
        assertFalse(names(PackageHookPlan.steps(settings, "$settings:remote")).contains("LinkTurboAppsHooker"))
        assertTrue(RuntimeSettingsGroups.owns(settings, com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys.KEY_LINK_TURBO_ALL_APPS))
    }

    @Test fun neverScreenTimeoutInstallsInTheSettingsMainProcess() {
        val settings = "com.android.settings"
        assertTrue(names(PackageHookPlan.steps(settings, settings)).contains("NeverScreenTimeoutHooker"))
        assertFalse(names(PackageHookPlan.steps(settings, "$settings:remote")).contains("NeverScreenTimeoutHooker"))
        assertTrue(RuntimeSettingsGroups.owns(settings, com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys.KEY_ALLOW_NEVER_SCREEN_TIMEOUT))
    }

    @Test fun refreshRateListInstallsInTheMiSettingsMainProcess() {
        val misettings = com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys.PACKAGE
        assertTrue(names(PackageHookPlan.steps(misettings, misettings)).contains("RefreshRateListHooker"))
        assertTrue(PackageHookPlan.steps(misettings, "$misettings:remote").isEmpty())
        assertTrue(RuntimeSettingsGroups.owns(misettings, com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys.KEY_MORE_REFRESH_RATES))
    }

    @Test fun perAppRefreshRatesInstallInMiSettingsAndPowerKeeperMainProcesses() {
        val keys = com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys
        assertTrue(names(PackageHookPlan.steps(keys.PACKAGE, keys.PACKAGE)).contains("AppRefreshRateRowsHooker"))
        assertTrue(names(PackageHookPlan.steps(keys.POWERKEEPER_PACKAGE, keys.POWERKEEPER_PACKAGE)).contains("AppRefreshRateHooker"))
        assertFalse(names(PackageHookPlan.steps(keys.POWERKEEPER_PACKAGE, "${keys.POWERKEEPER_PACKAGE}:remote")).contains("AppRefreshRateHooker"))
        assertTrue(RuntimeSettingsGroups.owns(keys.PACKAGE, keys.KEY_PER_APP_REFRESH_RATES))
        assertTrue(RuntimeSettingsGroups.owns(keys.POWERKEEPER_PACKAGE, keys.KEY_PER_APP_REFRESH_RATES))
    }

    @Test fun appLockPinScrambleInstallsInTheSecurityCenterMainProcess() {
        val center = "com.miui.securitycenter"
        assertTrue(names(PackageHookPlan.steps(center, center)).contains("AppLockPinScrambleHooker"))
        assertFalse(names(PackageHookPlan.steps(center, "$center.remote")).contains("AppLockPinScrambleHooker"))
        assertTrue(RuntimeSettingsGroups.owns(center, com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys.KEY_SECURITY_CENTER_APP_LOCK_SCRAMBLE_PIN))
    }

    @Test fun phraseLimitsInstallInTheProviderAndActivityProcess() {
        val pkg = PhraseSettingsKeys.PACKAGE
        assertEquals(listOf("PhraseLimitHooker"), names(PackageHookPlan.steps(pkg, pkg)))
        assertTrue(PackageHookPlan.steps(pkg, "$pkg:remote").isEmpty())
        assertTrue(RuntimeSettingsGroups.owns(pkg, PhraseSettingsKeys.KEY_UNLOCK_LIMITS))
        assertFalse(RuntimeSettingsGroups.owns("com.android.systemui", PhraseSettingsKeys.KEY_UNLOCK_LIMITS))
    }

    @Test fun aivsCoversEveryPackageProcessAndReceivesLiveSettings() {
        for (pkg in com.takekazex.hypertweak.settings.contract.AivsPathPolicy.packages) {
            for (process in listOf(pkg, "$pkg:remote", "$pkg:core")) {
                assertTrue(names(PackageHookPlan.steps(pkg, process)).contains("AivsDirectoryHooker"))
                assertTrue(com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups.forProcess(process)
                    .contains(com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups.PASSIVE_POLICIES))
            }
            assertTrue(com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups.owns(pkg,
                com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys.KEY_AIVS_REDIRECT))
        }
    }

    @Test fun userSelectedImePrecedesHostHooksAndUnknownHostsStayEmpty() {
        assertTrue(PackageHookPlan.steps("unknown", "unknown").isEmpty())
        assertEquals(listOf("AospImeHooker"), names(PackageHookPlan.steps("unknown", "unknown", true)))
        val plain = names(PackageHookPlan.steps("com.android.systemui", "com.android.systemui"))
        assertEquals(listOf("AospImeHooker") + plain,
            names(PackageHookPlan.steps("com.android.systemui", "com.android.systemui", true)))
        assertEquals(1, plain.count { it == "@icons" })
    }

    @Test fun systemServerChecksImeAfterBootHooksAndInstallsItOnlyWhenEnabled() {
        val boot = names(SystemServerHookPlan.steps(false))
        val ime = "AospImeSystemHooker"
        assertEquals("SystemServerReadinessHooker", boot.first())
        assertFalse(ime in boot)

        for (enabled in listOf(false, true)) {
            val observed = mutableListOf<String>()
            SystemServerHookPlan.install({
                assertEquals(boot, observed)
                enabled
            }) {
                observed += it.target.simpleName
            }
            assertEquals(if (enabled) boot + ime else boot, observed)
        }
    }
}
