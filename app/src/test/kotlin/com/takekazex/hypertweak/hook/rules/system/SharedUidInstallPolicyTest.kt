package com.takekazex.hypertweak.hook.rules.system

import org.junit.Assert.*
import org.junit.Test

class SharedUidInstallPolicyTest {
    private val ordinary = SharedUidInstallPolicy.Target(10_123, false, false, false, false)
    private val plugin = SharedUidInstallPolicy.Target(1_000, true, true, false, true)

    @Test fun ordinarySharedUidKeepsItsOriginalBoundary() {
        for (systemEnabled in listOf(false, true)) {
            assertTrue(SharedUidInstallPolicy.allows(-8, systemEnabled, ordinary))
            assertFalse(SharedUidInstallPolicy.allows(-7, systemEnabled, ordinary))
            assertFalse(SharedUidInstallPolicy.allows(-8, systemEnabled, ordinary.copy(factoryPackage = true)))
        }
    }

    @Test fun systemPluginUpdateAndMembershipRequireTheExtraOption() {
        for (error in listOf(-7, -8)) {
            assertFalse(SharedUidInstallPolicy.allows(error, false, plugin))
            assertTrue(SharedUidInstallPolicy.allows(error, true, plugin))
        }
    }

    @Test fun systemIdentityCannotSlipThroughWhenOnlyTheParentIsEnabled() {
        val targets = listOf(
            ordinary.copy(appId = 1_001),
            ordinary.copy(packageSystem = true),
            ordinary.copy(groupSystem = true),
            ordinary.copy(privileged = true),
        )
        for (target in targets) {
            assertFalse(SharedUidInstallPolicy.allows(-8, false, target))
            assertTrue(SharedUidInstallPolicy.allows(-8, true, target))
        }
    }

    @Test fun unknownErrorsAndNonPackageUidsKeepNativeFailures() {
        for (target in listOf(ordinary, plugin)) {
            for (error in listOf(0, -1, -2, -5, -24, -103, -112, Int.MIN_VALUE)) {
                assertFalse(SharedUidInstallPolicy.allows(error, true, target))
            }
        }
        for (uid in listOf(-1, 20_000, 90_000, 100_001, Int.MAX_VALUE)) {
            for (error in listOf(-7, -8)) {
                assertFalse(SharedUidInstallPolicy.allows(error, true, plugin.copy(appId = uid)))
            }
        }
        assertTrue(SharedUidInstallPolicy.allows(-8, false, ordinary.copy(appId = 10_000)))
        assertTrue(SharedUidInstallPolicy.allows(-8, false, ordinary.copy(appId = 19_999)))
    }
}
