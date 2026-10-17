package com.takekazex.hypertweak.hook.base

import io.github.libxposed.api.XposedInterface
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Executable

class ManagedHookReloadTest {
    private class Host { var extended = false; fun event() {} }
    private class Handle : XposedInterface.HookHandle {
        var removed = false
        override fun getId(): String = "test-host"
        override fun getExecutable(): Executable = Host::class.java.getDeclaredMethod("event")
        override fun unhook() { removed = true }
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle = error("unused")
    }
    private class Participant(private val enabled: Boolean) : DynamicHooker() {
        var host: Host? = null
        var retired = false
        val handle = Handle()
        override fun onHook() { check(!retired); adoptManagedHookHandle(handle) }
        override fun saveHotReloadState(): Any? = host
        override fun onPrepareHotReload() { retired = true; host?.extended = false; host = null }
        override fun restoreHotReloadState(state: Any?) { host = state as? Host; host?.extended = enabled }
    }
    @Test fun repeatedReplacementRetiresEveryOldHandleAndRecoversExistingHostOnFreshInstances() {
        val host = Host()
        var current = Participant(true).also {
            it.updateParentState(true)
            it.restoreHotReloadState(host)
        }
        listOf(false, true, false, true).forEach { enabled ->
            val old = current
            val next = Participant(enabled)
            var recovery = false
            ManagedHookReload.run(listOf(old to next), recover = {
                assertSame(host, next.host)
                assertEquals(enabled, host.extended)
                recovery = true
            })
            assertTrue(recovery)
            assertTrue(old.retired)
            assertTrue(old.handle.removed)
            assertNull(old.host)
            assertEquals(0, old.collectManagedHookHandles().size)
            assertEquals(1, next.collectManagedHookHandles().size)
            current = next
        }
    }
}
