package com.takekazex.hypertweak.hook.base.dex

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DexScopesTest {
    @Test fun `nested consumers keep one backend and one detached index through dispatch`() {
        var opened = 0; var closed = 0
        val scopes = DexScopes { opened++; AutoCloseable { closed++ } }
        val outer = scopes.begin("apk")
        val index = Any()
        assertSame(index, scopes.artifact("apk", Any::class.java, "version") { index })
        scopes.begin("apk").use {
            assertSame(index, scopes.artifact("apk", Any::class.java, "version") { error("duplicate parse") })
        }
        assertEquals(1, opened)
        assertEquals(0, closed)
        outer.close(); outer.close()
        assertEquals(1, closed)
    }

    @Test fun `next phase reuses a surviving weak index but target replacement reparses`() {
        val scopes = DexScopes { AutoCloseable {} }
        val index = Any()
        scopes.begin("apk").use { scopes.artifact("apk", Any::class.java, "v1") { index } }
        scopes.begin("apk").use {
            assertSame(index, scopes.artifact("apk", Any::class.java, "v1") { error("unnecessary reparse") })
            assertNotSame(index, scopes.artifact("apk", Any::class.java, "v2") { Any() })
        }
    }

    @Test fun `another thread cannot borrow the current scope or index`() {
        val scopes = DexScopes { AutoCloseable {} }
        val index = Any()
        val pool = Executors.newSingleThreadExecutor()
        try {
            scopes.begin("apk").use {
                scopes.artifact("apk", Any::class.java, "v1") { index }
                val other = pool.submit<Any> {
                    scopes.begin("apk").use { scopes.artifact("apk", Any::class.java, "v1") { Any() } }
                }.get(2, TimeUnit.SECONDS)
                assertNotSame(index, other)
            }
        } finally { pool.shutdownNow() }
    }
}
