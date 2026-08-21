package com.takekazex.hypertweak.hook.rules.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [CameraIdentity], the sensor-identity invariant that validates camera
 * impersonation candidates.
 *
 * REGRESSION HISTORY: the original implementation compared getter results with plain `==`.
 * On camera 6.6.000510.0 both the K100 Pro Max config (`Songyuan`) and this device's own
 * config (`Myron`) mint a fresh `new int[]{17}` on every `q1()` call, so reference equality
 * never held, EVERY K100 candidate was rejected, and the impersonation silently fell back to
 * Nezha (wrong focal/imaging surface until the user switched targets by hand).
 */
class CameraIdentityTest {

    /** Mirrors a config pair whose getters allocate new arrays per call (the 510 shape). */
    @Suppress("unused", "RedundantSuppression")
    open class Config(val sensorParam: String, val sensorId: Int, val lensIds: IntArray, val lensCount: Int) {
        fun O1(): String = sensorParam
        fun D(): Int = sensorId
        fun q1(): IntArray = lensIds.copyOf()
        fun r1(): Int = lensCount
    }

    @Test
    fun `freshly allocated equal arrays compare equal`() {
        assertTrue(CameraIdentity.valueEquals(intArrayOf(17), intArrayOf(17)))
    }

    @Test
    fun `arrays with different content compare unequal`() {
        assertFalse(CameraIdentity.valueEquals(intArrayOf(17), intArrayOf(18)))
        assertFalse(CameraIdentity.valueEquals(intArrayOf(17), intArrayOf()))
    }

    @Test
    fun `scalars compare by value across boxing`() {
        assertTrue(CameraIdentity.valueEquals("same", "same"))
        assertTrue(CameraIdentity.valueEquals(17, 17))
        assertFalse(CameraIdentity.valueEquals("a", "b"))
        assertFalse(CameraIdentity.valueEquals(17, 18))
    }

    @Test
    fun `null equals only null`() {
        assertTrue(CameraIdentity.valueEquals(null, null))
        assertFalse(CameraIdentity.valueEquals(null, "x"))
        assertFalse(CameraIdentity.valueEquals("x", null))
    }

    @Test
    fun `mismatched types are unequal`() {
        assertFalse(CameraIdentity.valueEquals(intArrayOf(17), arrayOf(17)))
        assertFalse(CameraIdentity.valueEquals("17", 17))
    }

    @Test
    fun `identity invariant accepts byte-identical sensors with fresh arrays`() {
        // Same values as C1200 (Songyuan) vs C1196 (Myron) on 510: O1="...", D=..., q1=[17].
        val candidate = Config("0x0102", 17, intArrayOf(17), 3)
        val original = Config("0x0102", 17, intArrayOf(17), 3)
        assertTrue(CameraIdentity.sharesImagingIdentity(candidate, original))
    }

    @Test
    fun `identity invariant rejects a different sensor`() {
        val candidate = Config("0x9999", 17, intArrayOf(17), 3)
        val original = Config("0x0102", 17, intArrayOf(17), 3)
        assertFalse(CameraIdentity.sharesImagingIdentity(candidate, original))
    }

    @Test
    fun `identity invariant rejects when a getter throws on either side`() {
        val broken = object : Any() {
            fun O1(): String = throw IllegalStateException("host blew up")
            fun D(): Int = 17
            fun q1(): IntArray = intArrayOf(17)
            fun r1(): Int = 3
        }
        val healthy = Config("0x0102", 17, intArrayOf(17), 3)
        assertFalse(CameraIdentity.sharesImagingIdentity(broken, healthy))
        assertFalse(CameraIdentity.sharesImagingIdentity(healthy, broken))
    }

    // ── MasterLive (mode 231) carousel placement ─────────────────────────────────

    @Test
    fun `masterlive mode is fronted onto a K100-style array`() {
        // C1200 (K100) omits 231 entirely; the hook must prepend it, preserving the rest.
        val k100 = intArrayOf(167, 163, 254)
        val fronted = CameraIdentity.frontMasterLiveMode(k100)
        assertTrue(fronted!!.contentEquals(intArrayOf(231, 167, 163, 254)))
        // The input array must not be mutated in place.
        assertTrue(k100.contentEquals(intArrayOf(167, 163, 254)))
    }

    @Test
    fun `masterlive mode is not duplicated on a nezha-style array`() {
        // C1209 (Nezha) already fronts {231,…} natively — nothing to do.
        assertNull(CameraIdentity.frontMasterLiveMode(intArrayOf(231, 167, 254)))
    }

    @Test
    fun `masterlive placement handles degenerate arrays`() {
        assertNull(CameraIdentity.frontMasterLiveMode(null))
        assertTrue(
            CameraIdentity.frontMasterLiveMode(IntArray(0))!!.contentEquals(intArrayOf(231))
        )
    }
}
