package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class HookSettingTest {
    @Test fun callbacksReadPublishedValuesWithoutCallingStorage() {
        val registry = HookSettingRegistry()
        var reads = 0
        var stored = 1
        val snapshot by registry.capture(0) { reads++; stored }
        repeat(1000) { assertEquals(1, snapshot) }
        assertEquals(1, reads)
        stored = 2
        assertEquals(1, snapshot)
        registry.refresh()
        assertEquals(2, snapshot)
        assertEquals(2, reads)
    }

    @Test fun aFailedReaderRetainsItsLastValueAndDoesNotBlockOtherReaders() {
        val registry = HookSettingRegistry()
        var fail = false
        var stored = true
        val first by registry.capture(false) { check(!fail); stored }
        val second by registry.capture(stored) { stored }
        fail = true
        stored = false
        var failures = 0
        registry.refresh { failures++ }
        assertTrue(first)
        assertFalse(second)
        assertEquals(1, failures)
        fail = false
        registry.refresh()
        assertFalse(first)
    }

    @Test fun registrationAfterAnAcceptedUpdateCapturesCurrentValues() {
        val registry = HookSettingRegistry()
        var stored = "old"
        val first by registry.capture(stored) { stored }
        stored = "new"
        registry.refresh()
        val plugin by registry.capture(stored) { stored }
        assertEquals(first, plugin)
        stored = "default"
        registry.refresh()
        assertEquals("default", plugin)
        assertEquals(plugin, first)
    }
    @Test fun aFailedInitialReadUsesTheDeclaredDefaultAndRecoversOnRefresh() {
        var available = false
        var failures = 0
        val registry = HookSettingRegistry { failures++ }
        val snapshot by registry.capture(20) { check(available); 35 }
        assertEquals(20, snapshot)
        assertEquals(1, failures)
        registry.refresh()
        assertEquals(20, snapshot)
        available = true
        registry.refresh()
        assertEquals(35, snapshot)
    }

    @Test fun storageReadsDoNotHoldTheRegistryLock() {
        val registry = HookSettingRegistry()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val blocked = pool.submit<HookSetting<Int>> {
                registry.capture(0) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); 1 }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val independent = pool.submit<HookSetting<Int>> { registry.capture(0) { 2 } }
            val snapshot by independent.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(2, snapshot)
            release.countDown()
            blocked.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun anUnavailableLoggerDoesNotPoisonInitialCapture() {
        val registry = HookSettingRegistry { error("logger unavailable") }
        val snapshot by registry.capture(false) { error("backend unavailable") }
        assertFalse(snapshot)
    }

}
