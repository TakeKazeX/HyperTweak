package com.takekazex.hypertweak.hook.rules.securitycenter

import org.junit.Assert.*
import org.junit.Test

class SecurityCenterFragmentListContractTest {
    class Fragment
    class RenamedManager { fun x0(): List<Any?> = listOf(Fragment(), null) }
    class AmbiguousManager {
        fun x0(): List<Any> = emptyList()
        fun y0(): List<Any> = emptyList()
    }
    class NamedManager {
        fun getFragments(): List<Any> = listOf(Fragment())
        fun other(): List<Any> = emptyList()
    }

    @Test fun obfuscatedAccessorMustBeUniqueAndReturnOnlyFragments() {
        val manager = RenamedManager()
        val method = SecurityCenterFragmentListContract.resolve(manager.javaClass)!!
        assertEquals(1, SecurityCenterFragmentListContract.validate(method.invoke(manager), Fragment::class.java)!!.size)
        assertNull(SecurityCenterFragmentListContract.validate(listOf(Fragment(), "foreign"), Fragment::class.java))
        assertNull(SecurityCenterFragmentListContract.resolve(AmbiguousManager::class.java))
        assertEquals("getFragments", SecurityCenterFragmentListContract.resolve(NamedManager::class.java)!!.name)
    }
}
