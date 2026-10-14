package com.takekazex.hypertweak.hook.base

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ManagedHookHandleTest {
    private class Owner : StaticHooker()
    private class Target { fun update() = Unit }
    private class RawHandle(private val target: Executable) : XposedInterface.HookHandle {
        var valid = true
        var replacements = 0
        override fun getExecutable() = target
        override fun getId() = "test-update"
        override fun unhook() { valid = false }
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
            check(valid) { "Cannot replace an invalid hook handle" }
            valid = false
            replacements++
            return RawHandle(target)
        }
    }
    private val target = Target::class.java.getDeclaredMethod("update")
    private val callback = Proxy.newProxyInstance(XposedInterface.Hooker::class.java.classLoader,
        arrayOf(XposedInterface.Hooker::class.java)) { _, _, _ -> null } as XposedInterface.Hooker

    @Test fun replacementRegistrationKeepsExactlyOneCurrentHandle() {
        val owner = Owner()
        var current = owner.ownManagedHandle(RawHandle(target))
        repeat(5) {
            current = owner.ownManagedHandle(current.replaceHook(callback))
            assertEquals(1, owner.collectManagedHookHandles().size)
            assertSame(current, owner.collectManagedHookHandles().single())
        }
        current.unhook()
        assertTrue(owner.collectManagedHookHandles().isEmpty())
    }

    @Test fun callerUnhookRemovesTheSameHandleFromTheLifecycleLedger() {
        val owner = Owner()
        val raw = RawHandle(target)
        val returned = owner.ownManagedHandle(raw)
        assertNotSame(raw, returned)
        assertSame(returned, owner.ownManagedHandle(returned))
        returned.unhook()
        assertFalse(raw.valid)
        assertTrue(owner.collectManagedHookHandles().isEmpty())
    }

    @Test fun liveReattachmentRetiresBeforeRegisteringAndNeverReplacesInvalidHandles() {
        val owner = Owner()
        val raws = ArrayList<RawHandle>()
        repeat(5) {
            owner.collectManagedHookHandles().forEach { it.unhook() }
            owner.resetAfterHotReloadPrepared()
            val raw = RawHandle(target).also(raws::add)
            owner.ownManagedHandle(raw)
            assertEquals(1, owner.collectManagedHookHandles().size)
            assertTrue(raw.valid)
            assertTrue(raws.dropLast(1).none { it.valid })
        }
        assertTrue(raws.all { it.replacements == 0 })
    }
}
