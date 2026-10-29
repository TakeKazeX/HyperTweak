package com.takekazex.hypertweak.hook.bootstrap

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

    @Test fun everyHostAndProcessRetainsBaselineTargetsAndExactOrder() {
        val json = requireNotNull(javaClass.getResourceAsStream("/hook-package-plan-baseline.json"))
            .bufferedReader().use { it.readText() }
        val cases = JSONArray(json)
        assertEquals(68, cases.length())
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = case.getJSONArray("targets")
            assertEquals("${case.getString("package")}/${case.getString("process")}",
                (0 until expected.length()).map { expected.getString(it) },
                names(PackageHookPlan.steps(case.getString("package"), case.getString("process"))))
        }
    }

    @Test fun aivsCoversEveryPackageProcessAndReceivesLiveSettings() {
        for (pkg in com.takekazex.hypertweak.settings.contract.AivsPathPolicy.packages) {
            for (process in listOf(pkg, "$pkg:remote", "$pkg:core")) {
                assertTrue(names(PackageHookPlan.steps(pkg, process)).contains("AivsDirectoryHooker"))
                assertTrue(com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups.forProcess(process)
                    .contains(com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups.PASSIVE_POLICIES))
            }
            assertTrue(com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups.owns(pkg,
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

    @Test fun systemServerRetainsItsFullBootOrderAndConditionalImeTail() {
        val json = requireNotNull(javaClass.getResourceAsStream("/hook-system-server-baseline.json"))
            .bufferedReader().use { it.readText() }
        val baseline = JSONArray(json)
        val expected = (0 until baseline.length()).map { baseline.getString(it) }
        assertEquals(22, expected.size)
        assertEquals(expected, names(SystemServerHookPlan.steps(false)))
        assertEquals(expected + "AospImeSystemHooker", names(SystemServerHookPlan.steps(true)))
        val observed = mutableListOf<String>()
        SystemServerHookPlan.install({ assertEquals(expected, observed); true }) {
            observed += it.target.simpleName
        }
        assertEquals(expected + "AospImeSystemHooker", observed)
    }
}
