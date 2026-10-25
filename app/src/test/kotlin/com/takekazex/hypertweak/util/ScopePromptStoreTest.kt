package com.takekazex.hypertweak.util

import com.takekazex.hypertweak.settings.contract.SettingsValues
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ScopePromptStoreTest {
    private class Storage {
        var ids = setOf("restore:com.old", "remove:com.legacy", "malformed")
        var durable = ids
        var flushes = 0
        val events = mutableListOf<String>()
        val settings = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SettingsValues::class.java)) { _, method, args ->
            when (method.name) {
                "getStringSet" -> { assertEquals(PlatformSettingsKeys.KEY_IGNORED_SCOPE_PROMPTS, args!![0]); ids.toSet() }
                "putStringSet" -> {
                    assertEquals(PlatformSettingsKeys.KEY_IGNORED_SCOPE_PROMPTS, args!![0])
                    @Suppress("UNCHECKED_CAST")
                    ids = (args[1] as Set<String>).toSet()
                    events += "write"
                    null
                }
                "flush" -> { durable = ids.toSet(); flushes++; events += "flush"; null }
                else -> error("Unexpected settings call ${method.name}")
            }
        } as SettingsValues
    }

    @Test fun dismissalsPreserveLegacyAndUnknownIdsAndAreFlushedBeforeReturning() {
        val storage = Storage()
        val store = ScopePromptStore(storage.settings)
        val prompt = ScopePrompt(ScopePromptAction.RESTORE, "com.new")
        store.ignore(prompt)
        assertTrue(prompt in store.ignoredPrompts())
        assertEquals(setOf("restore:com.old", "remove:com.legacy", "malformed", prompt.id), storage.durable)
        assertEquals(listOf("write", "flush"), storage.events)
        store.unignore(prompt)
        assertFalse(prompt.id in storage.durable)
        assertTrue("remove:com.legacy" in storage.durable)
        store.ignoreAll(emptyList())
        assertEquals(2, storage.flushes)
    }

    @Test fun simultaneousDismissalsDoNotLoseUpdates() {
        val storage = Storage()
        val store = ScopePromptStore(storage.settings)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val pending = (1..24).map { id -> executor.submit { store.ignore(ScopePrompt(ScopePromptAction.RESTORE, "com.test$id")) } }
            pending.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue((1..24).all { "restore:com.test$it" in storage.durable })
            assertEquals(24, storage.flushes)
        } finally {
            executor.shutdownNow()
        }
    }
}
