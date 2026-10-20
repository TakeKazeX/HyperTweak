package com.takekazex.hypertweak.hook.rules.backgesture

import com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.ShellNavigationIdentity
import org.junit.Assert.*
import org.junit.Test

class ShellNavigationIdentityTest {
    @Test fun `predictive target becoming received-null invalidates the session before MOVE`() {
        val target = Any()
        assertTrue(ShellNavigationIdentity.matches(target, false, target, false))
        assertFalse(ShellNavigationIdentity.matches(target, false, target, true))
        assertFalse(ShellNavigationIdentity.isConsistent(target, true))
    }

    @Test fun `authenticated null fallback retains identity until navigation changes`() {
        assertTrue(ShellNavigationIdentity.matches(null, true, null, true))
        assertFalse(ShellNavigationIdentity.matches(null, true, null, false))
        assertFalse(ShellNavigationIdentity.matches(null, true, Any(), false))
        assertTrue(ShellNavigationIdentity.isConsistent(null, true))
    }

    @Test fun `an equivalent but replaced target cannot receive old session progress`() {
        val original = String(charArrayOf('x'))
        val replacement = String(charArrayOf('x'))
        assertEquals(original, replacement)
        assertFalse(ShellNavigationIdentity.matches(original, false, replacement, false))
    }
}
