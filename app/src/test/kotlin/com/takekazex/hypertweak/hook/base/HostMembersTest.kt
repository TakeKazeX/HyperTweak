package com.takekazex.hypertweak.hook.base

import org.junit.Assert.*
import org.junit.Test

class HostMemberFixture {
    private var token = "native"
    private fun value() = token
}

class HostMembersTest {
    open class Parent {
        private val number = 7
        private fun lookup() = "parent"
        private fun lookup(value: Int) = value + 1
    }
    class Child : Parent()
    interface Named { fun name(): String = "interface" }
    class NamedChild : Named

    @Test fun inheritedPrivateMembersAndExactOverloadsRemainInvocable() {
        val child = Child()
        assertEquals(7, HostMembers.read(child, "number"))
        assertEquals("parent", HostMembers.method(child, "lookup")!!.invoke(child))
        assertEquals(9, HostMembers.method(child, "lookup", Int::class.javaPrimitiveType!!)!!.invoke(child, 8))
        assertNull(HostMembers.method(child, "lookup", String::class.java))
        assertNull(HostMembers.field(child.javaClass, "absent"))
        assertSame(HostMembers.field(child.javaClass, "number"), HostMembers.field(child.javaClass, "number"))
        assertEquals("interface", HostMembers.method(NamedChild(), "name")!!.invoke(NamedChild()))
    }

    @Test fun sameBinaryNameFromDifferentLoadersNeverSharesMembersOrValues() {
        val fixture = HostMemberFixture::class.java
        val bytes = fixture.getResourceAsStream("/${fixture.name.replace('.', '/')}.class")!!.use { it.readBytes() }
        fun fresh(): Class<*> = object : ClassLoader(fixture.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name != fixture.name) return super.loadClass(name, resolve)
                return findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size).also { if (resolve) resolveClass(it) }
            }
        }.loadClass(fixture.name)
        val first = fresh()
        val second = fresh()
        val a = first.getDeclaredConstructor().newInstance()
        val b = second.getDeclaredConstructor().newInstance()
        val fieldA = HostMembers.field(first, "token")!!
        val fieldB = HostMembers.field(second, "token")!!
        assertNotSame(fieldA, fieldB)
        fieldA.set(a, "changed")
        assertEquals("changed", HostMembers.method(a, "value")!!.invoke(a))
        assertEquals("native", HostMembers.method(b, "value")!!.invoke(b))
    }
    @Test fun exactLookupKeepsDeclarationVisibilityAndAccessibilitySeparate() {
        assertNull(HostMembers.declaredField(Child::class.java, "number", accessible = true))
        assertNotNull(HostMembers.field(Child::class.java, "number"))
        assertNull(HostMembers.publicField(Parent::class.java, "number", accessible = false))
        assertThrows(NoSuchMethodException::class.java) {
            HostMembers.requirePublicMethod(Parent::class.java, "lookup", accessible = false)
        }
        assertThrows(NoSuchMethodException::class.java) {
            HostMembers.requireDeclaredMethod(Child::class.java, "lookup", accessible = false)
        }
        val raw = HostMembers.requireDeclaredMethod(Parent::class.java, "lookup", accessible = false)
        val accessible = HostMembers.requireDeclaredMethod(Parent::class.java, "lookup", accessible = true)
        assertNotSame(raw, accessible)
        @Suppress("DEPRECATION")
        assertFalse(raw.isAccessible)
        assertEquals("parent", accessible.invoke(Parent()))
        assertEquals(3, HostMembers.requireDeclaredMethod(Parent::class.java, "lookup",
            Int::class.javaPrimitiveType, accessible = true).invoke(Parent(), 2))
        assertThrows(NoSuchMethodException::class.java) {
            HostMembers.requireDeclaredMethod(Parent::class.java, "lookup", Int::class.javaObjectType, accessible = false)
        }
    }

    @Test fun structuralSelectorsCacheMissesAndDoNotRetainOldInstanceValues() {
        var misses = 0
        repeat(3) {
            assertNull(HostMembers.cached(Parent::class.java, "test:missing-selector", Any::class.java) { misses++; null })
        }
        assertEquals(1, misses)
        var lookups = 0
        val a = HostMemberFixture()
        val b = HostMemberFixture()
        val field = HostMembers.cached(HostMemberFixture::class.java, "test:token-selector", java.lang.reflect.Field::class.java) {
            lookups++
            HostMembers.declaredField(HostMemberFixture::class.java, "token", accessible = true)
        }!!
        field.set(a, "a")
        assertSame(field, HostMembers.cached(HostMemberFixture::class.java, "test:token-selector", java.lang.reflect.Field::class.java) {
            lookups++; error("must reuse member metadata")
        })
        assertEquals("a", field.get(a))
        assertEquals("native", field.get(b))
        assertEquals(1, lookups)
    }

    @Test fun concurrentSelectorsResolveOnceAndFailuresCanRetry() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val values = (1..12).map {
                pool.submit<Any> { HostMembers.cached(Child::class.java, "test:concurrent-selector", Any::class.java) {
                    attempts.incrementAndGet(); Any()
                }!! }
            }.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, attempts.get())
            assertTrue(values.all { it === values.first() })
        } finally { pool.shutdownNow() }
        assertThrows(IllegalStateException::class.java) {
            HostMembers.cached(Child::class.java, "test:retry-selector", String::class.java) { error("transient failure") }
        }
        assertEquals("resolved", HostMembers.cached(Child::class.java, "test:retry-selector", String::class.java) { "resolved" })
    }

    @Test fun typedContractsRejectConflictingResultTypesIncludingCachedMisses() {
        assertEquals("value", HostMembers.cached(Child::class.java, "test:typed-value", String::class.java) { "value" })
        assertThrows(IllegalArgumentException::class.java) {
            HostMembers.cached(Child::class.java, "test:typed-value", Any::class.java) { error("must not resolve again") }
        }
        assertNull(HostMembers.cached(Child::class.java, "test:typed-miss", String::class.java) { null })
        assertThrows(IllegalArgumentException::class.java) {
            HostMembers.cached(Child::class.java, "test:typed-miss", Any::class.java) { error("must not resolve again") }
        }
    }

    @Test fun missingRequiredSelectorRetriesUntilSuccess() {
        var attempts = 0
        fun resolve() = HostMembers.cached(Child::class.java, "test:required-retry", String::class.java) {
            if (++attempts <= 2) throw NoSuchElementException("callback not found")
            "resolved"
        }
        repeat(2) { assertThrows(NoSuchElementException::class.java) { resolve() } }
        assertEquals("resolved", resolve())
        assertEquals("resolved", resolve())
        assertEquals(3, attempts)
    }

}
