package com.takekazex.hypertweak.hook.base

import io.github.libxposed.api.XposedInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Executable

/**
 * Verifies the presence-deciding switch contract on [BaseHooker].
 *
 * These are the rules that let an untouched feature cost nothing: with [InstallPolicy.WHEN_ENABLED]
 * the hooker is not installed while its switch is off, and turning the switch on installs it from
 * the already-warmed cache instead of triggering a scan.
 */
class InstallPolicyTest {
    private class Host { fun event() {} }

    private class Handle(private val removed: () -> Unit) : XposedInterface.HookHandle {
        override fun getId(): String = "install-policy-test"
        override fun getExecutable(): Executable = Host::class.java.getDeclaredMethod("event")
        override fun unhook() = removed()
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle = error("unused")
    }

    private class Gated(initial: Boolean) : DynamicHooker() {
        var enabled = initial
        var hookCalls = 0
        var unhookCalls = 0
        override val installPolicy = InstallPolicy.WHEN_ENABLED
        override fun isFeatureEnabled(): Boolean = enabled
        override fun onHook() {
            hookCalls++
            adoptManagedHookHandle(Handle { unhookCalls++ })
        }
    }

    private class Always : DynamicHooker() {
        var hookCalls = 0
        override fun onHook() { hookCalls++ }
    }

    private class StaticGated : StaticHooker() {
        var enabled = true
        var hookCalls = 0
        var unhookCalls = 0
        override val installPolicy = InstallPolicy.WHEN_ENABLED
        override fun isFeatureEnabled(): Boolean = enabled
        override fun onHook() {
            hookCalls++
            adoptManagedHookHandle(Handle { unhookCalls++ })
        }
    }

    @Test fun `a gated hooker with its switch off is not installed`() {
        val hooker = Gated(initial = false)
        hooker.updateParentState(true)

        assertEquals("an off switch must not install", 0, hooker.hookCalls)
    }

    @Test fun `a gated hooker with its switch on is installed`() {
        val hooker = Gated(initial = true)
        hooker.updateParentState(true)

        assertEquals(1, hooker.hookCalls)
    }

    @Test fun `turning the switch on installs without any other trigger`() {
        val hooker = Gated(initial = false)
        hooker.updateParentState(true)
        assertEquals(0, hooker.hookCalls)

        hooker.enabled = true
        hooker.refreshFeatureState()

        assertEquals("enabling must install immediately", 1, hooker.hookCalls)
    }

    @Test fun `turning the switch off removes the hook`() {
        val hooker = Gated(initial = true)
        hooker.updateParentState(true)
        assertEquals(1, hooker.hookCalls)

        hooker.enabled = false
        hooker.refreshFeatureState()

        assertEquals("disabling must restore native behavior", 1, hooker.unhookCalls)
        assertEquals("the managed handles must be released", 0, hooker.collectManagedHookHandles().size)
    }

    @Test fun `a repeated refresh with the same switch value does nothing`() {
        val hooker = Gated(initial = true)
        hooker.updateParentState(true)
        hooker.refreshFeatureState()
        hooker.refreshFeatureState()

        assertEquals("a stable switch must not reinstall hooks", 1, hooker.hookCalls)
    }

    @Test fun `an always policy ignores the switch`() {
        val hooker = Always()
        hooker.updateParentState(true)
        hooker.refreshFeatureState()

        assertEquals(1, hooker.hookCalls)
    }

    @Test fun `a gated hooker still honors its parent generation`() {
        val hooker = Gated(initial = true)
        hooker.updateParentState(false)

        assertEquals("a disabled parent must not install", 0, hooker.hookCalls)
    }

    @Test fun `disabling a static gated hooker still removes it`() {
        // Static only means "survives a preference hiccup"; it must not survive the user turning
        // the feature off, or the host would keep the module's behavior forever.
        val hooker = StaticGated()
        hooker.updateParentState(true)
        hooker.enabled = false
        hooker.refreshFeatureState()

        assertEquals(1, hooker.hookCalls)
        assertEquals(1, hooker.unhookCalls)
    }

    @Test fun `re-enabling installs a fresh handle`() {
        val hooker = Gated(initial = true)
        hooker.updateParentState(true)
        hooker.enabled = false
        hooker.refreshFeatureState()
        hooker.enabled = true
        hooker.refreshFeatureState()

        assertEquals(2, hooker.hookCalls)
        assertTrue(hooker.isHooked)
    }

    @Test fun `an ungated hooker that skipped on purpose is not retried on state updates`() {
        // Hookers skip for reasons other than the switch (wrong process, missing target). Those
        // skips must stay skips; re-running onHook on every parent-state update would repeat the
        // resolution work the skip was avoiding.
        var hookCalls = 0
        val hooker = object : DynamicHooker() {
            override fun onHook() { hookCalls++ }
        }
        hooker.updateParentState(true)
        hooker.updateParentState(true)
        hooker.updateParentState(true)

        assertEquals("a deliberate skip must not be retried", 1, hookCalls)
    }
    @Test fun `a feature refresh cannot undo parent disablement`() {
        val hooker = Gated(initial = true)
        hooker.updateParentState(true)
        hooker.updateParentState(false)
        hooker.enabled = false
        hooker.refreshFeatureState()
        hooker.enabled = true
        hooker.refreshFeatureState()
        assertEquals(1, hooker.hookCalls)
        assertEquals(1, hooker.unhookCalls)
        hooker.updateParentState(true)
        assertEquals(2, hooker.hookCalls)
    }

    @Test fun `retirement recomputes gated state in the next generation`() {
        val hooker = Gated(initial = true)
        hooker.updateParentState(true)
        hooker.enabled = false
        hooker.refreshFeatureState()
        hooker.prepareForHotReload()
        hooker.resetAfterHotReloadPrepared()
        hooker.enabled = true
        hooker.updateParentState(true)
        assertEquals(2, hooker.hookCalls)
        assertTrue(hooker.isHooked)
    }

    @Test fun `a route-only hooker is installed once and unsubscribes on disable`() {
        var closed = 0
        var installs = 0
        val hooker = object : DynamicHooker() {
            override fun onHook() {
                installs++
                ownSubscription(AutoCloseable { closed++ })
            }
        }
        hooker.updateParentState(true)
        hooker.retryHookIfNeeded()
        assertEquals(1, installs)
        hooker.updateParentState(false)
        assertEquals(1, closed)
        hooker.updateParentState(true)
        assertEquals(2, installs)
        hooker.prepareForHotReload()
        assertEquals(2, closed)
    }

}
