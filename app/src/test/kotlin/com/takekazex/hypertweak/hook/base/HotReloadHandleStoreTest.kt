package com.takekazex.hypertweak.hook.base

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import org.junit.Assert.*
import org.junit.Test

class HotReloadHandleStoreTest {
    private class Targets {
        fun background(a: Int, b: Int, c: Int, d: Int, e: Int) = Unit
        fun background(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int) = Unit
    }
    private class OtherTargets { fun background() = Unit }
    private class Handle(private val target: Executable, private val key: String) : XposedInterface.HookHandle {
        override fun getExecutable() = target
        override fun getId() = key
        override fun unhook() = Unit
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle = this
    }
    private fun target(count: Int) = Targets::class.java.declaredMethods.single {
        it.name == "background" && it.parameterCount == count
    }

    @Test fun changedBackgroundOverloadIsRegisteredFreshAndOldTargetRemainsForRetirement() {
        val old = Handle(target(5), "wallpaper")
        val store = HotReloadHandleStore(listOf(old))
        assertNull(store.firstForTarget("wallpaper", target(8)))
        assertEquals(listOf(old), store.remainingHandles())
        assertEquals(1, store.remainingCount)
    }
    @Test fun unchangedExecutableCanReplaceItsCallback() {
        val old = Handle(target(8), "wallpaper")
        val store = HotReloadHandleStore(listOf(old))
        assertSame(old, store.firstForTarget("wallpaper", target(8)))
        store.markHandled(old)
        assertTrue(store.remainingHandles().isEmpty())
    }
    @Test fun duplicateIdsMatchTheExecutableAndDoNotConsumeAnotherOverload() {
        val five = Handle(target(5), "wallpaper")
        val eight = Handle(target(8), "wallpaper")
        val store = HotReloadHandleStore(listOf(five, eight))
        assertSame(eight, store.firstForTarget("wallpaper", target(8)))
        store.markHandled(eight)
        assertSame(five, store.firstForTarget("wallpaper", target(5)))
        assertEquals(listOf(five), store.remainingHandles())
    }
    @Test fun movingTheFeatureToAnotherOwnerDoesNotReuseTheOldMethod() {
        val old = Handle(target(8), "wallpaper")
        val store = HotReloadHandleStore(listOf(old))
        assertNull(store.firstForTarget("wallpaper", OtherTargets::class.java.getDeclaredMethod("background")))
        assertNull(store.firstForTarget("wallpaper", Targets::class.java.declaredConstructors.single()))
        assertSame(old, store.remainingHandles().single())
    }
}
