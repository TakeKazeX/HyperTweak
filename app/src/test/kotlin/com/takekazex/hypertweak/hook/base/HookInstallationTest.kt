package com.takekazex.hypertweak.hook.base

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import org.junit.Assert.*
import org.junit.Test

class HookInstallationTest {
    private class Host { fun event() {} }
    private class Handle : XposedInterface.HookHandle {
        override fun getId() = "route-test"
        override fun getExecutable(): Executable = Host::class.java.getDeclaredMethod("event")
        override fun unhook() {}
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle = error("unused")
    }

    @Test fun aMissingSetterDoesNotBlockItsPeerAndRetryDoesNotDuplicateTheSuccessfulHook() {
        val failures = mutableListOf<Throwable>()
        val router = object : DynamicHooker() {
            val visibility = generationResource(HookInstallation { failures += it }) { it.reset() }
            val alpha = generationResource(HookInstallation { failures += it }) { it.reset() }
            var visibilityAvailable = false
            var alphaInstalls = 0
            override val isHooked get() = visibility.isInstalled && alpha.isInstalled
            override fun onHook() {
                visibility.install {
                    if (!visibilityAvailable) throw NoSuchMethodException("setVisibility")
                    adoptManagedHookHandle(Handle())
                }
                alpha.install { alphaInstalls++; adoptManagedHookHandle(Handle()) }
            }
        }
        router.updateParentState(true)
        assertFalse(router.isHooked)
        assertTrue(router.alpha.isInstalled)
        assertEquals(1, router.collectManagedHookHandles().size)
        assertTrue(failures.single() is NoSuchMethodException)
        router.visibilityAvailable = true
        router.retryHookIfNeeded()
        assertTrue(router.isHooked)
        assertEquals(2, router.collectManagedHookHandles().size)
        assertEquals(1, router.alphaInstalls)
        router.retryHookIfNeeded()
        assertEquals(1, router.alphaInstalls)
        router.prepareForHotReload()
        assertFalse(router.isHooked)
    }

    @Test fun aFailedReporterCannotAbortIndependentInstallation() {
        val first = HookInstallation { error("logger failed") }
        val second = HookInstallation { throw AssertionError(it) }
        first.install { error("resolver failed") }
        second.install {}
        assertFalse(first.isInstalled)
        assertNotNull(first.lastFailure)
        assertTrue(second.isInstalled)
    }
}
