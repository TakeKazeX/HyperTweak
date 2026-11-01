package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.rules.camera.CameraModeEntryOwnership.Feature
import org.junit.Assert.*
import org.junit.Test

class CameraModeEntryOwnershipTest {
    @Test fun disableAndResetRemoveOnlyModuleAdditions() {
        val native = Any(); val legendary = Any(); val master = Any()
        val registry = mutableMapOf(1 to native, 2 to legendary, 3 to master)
        val ownership = CameraModeEntryOwnership()
        ownership.added(2, legendary, Feature.LEGENDARY)
        ownership.added(3, master, Feature.MASTER_LIVE)
        ownership.restore(setOf(Feature.MASTER_LIVE), registry::get) { registry.remove(it) }
        assertEquals(mapOf(1 to native, 3 to master), registry)
        ownership.restore(emptySet(), registry::get) { registry.remove(it) }
        assertEquals(mapOf(1 to native), registry)
    }

    @Test fun laterNativeReplacementAndReenableArePreserved() {
        val module = Any(); val native = Any(); val registry = mutableMapOf(2 to module)
        val ownership = CameraModeEntryOwnership()
        ownership.added(2, module, Feature.EXTRA)
        registry[2] = native
        ownership.restore(emptySet(), registry::get) { registry.remove(it) }
        assertSame(native, registry[2])
        val next = Any(); registry[3] = next
        ownership.added(3, next, Feature.EXTRA)
        ownership.restore(setOf(Feature.EXTRA), registry::get) { registry.remove(it) }
        assertSame(next, registry[3])
        ownership.restore(emptySet(), registry::get) { registry.remove(it) }
        assertEquals(mapOf(2 to native), registry)
    }
}
