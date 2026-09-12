package com.takekazex.hypertweak.util.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersioningTest {
    @Test
    fun `a higher remote version code is an update and a lower one is not`() {
        assertTrue(UpdateVersioning.isRemoteNewer(localVersionCode = 351, remoteVersionCode = 352))
        assertFalse(UpdateVersioning.isRemoteNewer(localVersionCode = 351, remoteVersionCode = 350))
    }

    /**
     * Decision D22: an equal version code is never an update. Android refuses to install a lower
     * versionCode, and a same-code rebuild would present an "update" that changes nothing, so the
     * stable channel must read as up to date for a CI build that is ahead.
     */
    @Test
    fun `an equal version code is never an update`() {
        assertFalse(
            UpdateVersioning.isUpdateAvailable(
                localVersionCode = 351,
                localVersionName = "1.8.0-dev",
                remoteVersionCode = 351,
                remoteVersionName = "1.8.0-dev"
            )
        )
        // The real cross-channel case: a CI build (351) switching to the stable release (275).
        assertFalse(
            UpdateVersioning.isUpdateAvailable(
                localVersionCode = 351,
                localVersionName = "1.8.0-dev",
                remoteVersionCode = 275,
                remoteVersionName = "1.8.0"
            )
        )
    }

    @Test
    fun `a release without metadata falls back to semantic comparison`() {
        assertTrue(
            UpdateVersioning.isUpdateAvailable(
                localVersionCode = 275,
                localVersionName = "1.8.0",
                remoteVersionCode = null,
                remoteVersionName = "1.9.0"
            )
        )
        assertFalse(
            UpdateVersioning.isUpdateAvailable(
                localVersionCode = 275,
                localVersionName = "1.8.0",
                remoteVersionCode = null,
                remoteVersionName = "1.8.0"
            )
        )
        assertFalse(
            UpdateVersioning.isUpdateAvailable(
                localVersionCode = 275,
                localVersionName = "1.8.0",
                remoteVersionCode = null,
                remoteVersionName = "1.7.1"
            )
        )
    }

    @Test
    fun `distance is only reported when the remote is ahead`() {
        assertEquals(76, UpdateVersioning.distance(localVersionCode = 275, remoteVersionCode = 351))
        assertNull(UpdateVersioning.distance(localVersionCode = 351, remoteVersionCode = 351))
        assertNull(UpdateVersioning.distance(localVersionCode = 351, remoteVersionCode = 275))
    }

    @Test
    fun `semantic comparison handles a v prefix, a prerelease and a malformed value`() {
        assertEquals(0, UpdateVersioning.compareSemanticVersions("1.8.0", "v1.8.0"))
        assertTrue(UpdateVersioning.compareSemanticVersions("1.9.0", "1.8.0") > 0)
        // A release outranks its own prerelease.
        assertTrue(UpdateVersioning.compareSemanticVersions("1.8.0", "1.8.0-dev") > 0)
        assertTrue(UpdateVersioning.compareSemanticVersions("1.8.0-dev", "1.8.0-beta") > 0)
        // "1.8.0-dev" appears in CI builds, so a later CI build must still sort above a stable one.
        assertTrue(UpdateVersioning.compareSemanticVersions("1.10.0", "1.9.9") > 0)
        // Unparseable input degrades to a plain string compare instead of throwing.
        assertEquals(0, UpdateVersioning.compareSemanticVersions("nightly", "nightly"))
    }
}
