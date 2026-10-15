package com.takekazex.hypertweak.hook.rules.systemui.island

import org.junit.Assert.*
import org.junit.Test

class IslandEventFieldsTest {
    // Verified host State defaults to expanded=true; changeToState only carries timestamps.
    open class State {
        var expanded = true
        var tempShow = false
        var updateKey = ""
        var updateOrder: Boolean? = false
        var deleteKey = ""
        var deleteNoAnimation = false
    }
    class Summary : State()
    class Hidden : State()

    @Test fun `leased update preserves no-expansion request instead of adopting hidden default`() {
        val incoming = Summary().apply {
            expanded = false; updateKey = "media"; updateOrder = null
            deleteKey = "old"; deleteNoAnimation = true; tempShow = true
        }
        val hidden = Hidden()
        assertTrue(hidden.expanded)
        IslandEventFields(Summary::class.java).transfer(incoming, hidden)
        assertFalse(hidden.expanded)
        assertEquals("media", hidden.updateKey)
        assertNull(hidden.updateOrder)
        assertEquals("old", hidden.deleteKey)
        assertTrue(hidden.deleteNoAnimation)
        assertTrue(hidden.tempShow)
    }

    @Test fun `leased automatic expansion remains requested`() {
        val hidden = Hidden().apply { expanded = false }
        IslandEventFields(Summary::class.java).transfer(Summary(), hidden)
        assertTrue(hidden.expanded)
    }
}
