package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.settings.contract.AppSettingsKeys

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PreferencesLogLevelTest {
    private fun withBackend(read: () -> Int, check: () -> Unit) {
        val fields = listOf("remotePrefs", "isLocalOnly", "remoteBackendUnavailable")
            .map { Preferences::class.java.getDeclaredField(it).apply { isAccessible = true } }
        val previous = fields.map { it.get(Preferences) }
        val backend = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getInt" -> { assertEquals(AppSettingsKeys.KEY_LOG_LEVEL, args!![0]); read() }
                else -> error("log threshold must not call ${method.name}")
            }
        } as SharedPreferences
        try {
            Preferences.init(backend)
            check()
        } finally {
            fields.zip(previous).forEach { (field, value) -> field.set(Preferences, value) }
            Preferences.invalidateRuntimeReadCache()
        }
    }

    @Test fun `log threshold reads only the global remote value and retains the read memo`() {
        var reads = 0
        withBackend({ reads++; 6 }) {
            assertEquals(6, Preferences.logLevel(4))
            assertEquals(6, Preferences.logLevel(4))
            assertEquals(1, reads)
            Preferences.invalidateRuntimeReadCache()
            assertEquals(6, Preferences.logLevel(4))
            assertEquals(2, reads)
        }
    }

    @Test fun `remote failure uses the default without initializing host storage`() {
        withBackend({ error("remote unavailable") }) {
            assertEquals(4, Preferences.logLevel(4))
        }
    }
}
