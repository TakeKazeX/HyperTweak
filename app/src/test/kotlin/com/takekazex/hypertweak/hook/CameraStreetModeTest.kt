package com.takekazex.hypertweak.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [CameraStreetMode], the pure parsing/migration rules behind
 * [Preferences.KEY_CAMERA_STREET_MODE] (街拍 unlock selector: off / new / compat).
 *
 * The legacy `camera_street_enable` boolean (true = street forced on the impersonated config,
 * false = off) must keep resolving for users who never wrote the new key; a garbage value in
 * the NEW key must fall back to the default rather than resurrect the legacy boolean.
 */
class CameraStreetModeTest {

    // ── parse ───────────────────────────────────────────────────────────────────

    @Test fun `parse accepts every mode constant`() {
        assertEquals("off", CameraStreetMode.parse("off"))
        assertEquals("new", CameraStreetMode.parse("new"))
        assertEquals("compat", CameraStreetMode.parse("compat"))
    }

    @Test fun `parse trims surrounding whitespace`() {
        assertEquals("compat", CameraStreetMode.parse(" compat "))
    }

    @Test fun `parse rejects unknown, blank and null values`() {
        assertNull(CameraStreetMode.parse("New"))
        assertNull(CameraStreetMode.parse("enabled"))
        assertNull(CameraStreetMode.parse(""))
        assertNull(CameraStreetMode.parse("   "))
        assertNull(CameraStreetMode.parse(null))
    }

    // ── resolve (stored key wins; legacy boolean migrates only when key absent) ──

    @Test fun `a parsable stored value always wins over the legacy boolean`() {
        assertEquals(
            "compat",
            CameraStreetMode.resolve(stored = "compat", legacyEnable = false)
        )
        assertEquals(
            "off",
            CameraStreetMode.resolve(stored = "off", legacyEnable = true)
        )
        assertEquals(
            "new",
            CameraStreetMode.resolve(stored = "new", legacyEnable = null)
        )
    }

    @Test fun `absent stored value migrates the legacy boolean`() {
        assertEquals("new", CameraStreetMode.resolve(stored = null, legacyEnable = true))
        assertEquals("off", CameraStreetMode.resolve(stored = null, legacyEnable = false))
    }

    @Test fun `nothing stored at all behaves like the legacy default (true)`() {
        assertEquals("new", CameraStreetMode.resolve(stored = null, legacyEnable = null))
    }

    @Test fun `an unparsable stored value falls back to the default, not to legacy`() {
        // A present-but-garbage key means the new scheme owns the setting; the superseded
        // boolean must not resurrect (e.g. legacy=false + garbage -> default "new", not "off").
        assertEquals("new", CameraStreetMode.resolve(stored = "garbage", legacyEnable = false))
        assertEquals("new", CameraStreetMode.resolve(stored = "", legacyEnable = true))
    }

    // ── UI index mapping ────────────────────────────────────────────────────────

    @Test fun `index and fromIndex round-trip every mode`() {
        for ((expectedIndex, mode) in CameraStreetMode.MODES.withIndex()) {
            assertEquals(expectedIndex, CameraStreetMode.index(mode))
            assertEquals(mode, CameraStreetMode.fromIndex(expectedIndex))
        }
    }

    @Test fun `index clamps unknown modes to the first entry (off)`() {
        assertEquals(0, CameraStreetMode.index("garbage"))
        assertEquals(0, CameraStreetMode.index(null))
    }

    @Test fun `fromIndex clamps out-of-range indices into the list`() {
        assertEquals("off", CameraStreetMode.fromIndex(-1))
        assertEquals("compat", CameraStreetMode.fromIndex(99))
    }
}
