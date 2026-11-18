package com.takekazex.hypertweak.hook.base

import java.lang.ref.WeakReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RetiringClassValueTest {
    private class Host

    /** Stands in for a module generation: its values reach the cache through the loader's statics. */
    private class Generation { lateinit var cache: RetiringClassValue<Generation> }

    private fun cacheOnHostClass(retire: Boolean): WeakReference<Generation> {
        val generation = Generation()
        generation.cache = RetiringClassValue { generation }
        assertSame(generation, generation.cache.get(Host::class.java))
        if (retire) generation.cache.retire()
        return WeakReference(generation)
    }

    @Test fun retirementReleasesTheGenerationFromHostClasses() {
        val retained = cacheOnHostClass(retire = false)
        val retired = cacheOnHostClass(retire = true)
        for (attempt in 0 until 20) {
            if (retired.get() == null) break
            System.gc()
            Thread.sleep(10)
        }
        assertNull(retired.get())
        // Without retirement the host class keeps the value, and the value keeps its own key alive.
        assertNotNull(retained.get())
    }
}
