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
}
