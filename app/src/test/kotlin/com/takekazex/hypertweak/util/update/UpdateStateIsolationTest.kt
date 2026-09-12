package com.takekazex.hypertweak.util.update

import com.takekazex.hypertweak.hook.Preferences
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards decision D15: the update flow's ephemeral state must never reach the module settings store.
 *
 * `Preferences` exports every non-runtime key into the user's settings backup, so a timestamp, the
 * per-channel response cache, the recovery arm, the completion flag or the skip list landing there
 * would silently pollute every exported backup and ride the LSPosed daemon channel for no reason.
 * The ephemeral side therefore lives in its own app-local file owned by [UpdateStateStore].
 *
 * The check is a set comparison rather than a scan of `Preferences`, because reading that object's
 * contents would initialize the LSPosed bridge that unit tests have no runtime for. The four keys
 * below are the only update keys `Preferences` is allowed to declare.
 */
class UpdateStateIsolationTest {
    private val allowedUserSettings = setOf(
        Preferences.KEY_UPDATE_CHANNEL,
        Preferences.KEY_UPDATE_CHECK_INTERVAL,
        Preferences.KEY_UPDATE_PROXY_MODE,
        Preferences.KEY_UPDATE_PROXY_URL
    )

    @Test
    fun `no key owned by the update state store is a persisted user setting`() {
        val overlap = UpdateStateStore.OWNED_KEYS intersect allowedUserSettings
        assertTrue("update state keys leaked into the settings backup: $overlap", overlap.isEmpty())
    }

    @Test
    fun `every persisted update setting is still declared by the settings store`() {
        // A rename in either place must fail here rather than silently dropping the user's choice.
        assertTrue(allowedUserSettings.all { it.startsWith("update_") })
        assertTrue(UpdateStateStore.OWNED_KEYS.isNotEmpty())
        assertTrue(UpdateStateStore.OWNED_KEYS.none { it.startsWith("update_") })
    }

    @Test
    fun `the update state uses its own preferences file`() {
        // Sharing `hypertweak_settings` would put the ephemeral keys inside the exported file.
        assertNotEquals(Preferences.NAME, UpdateStateStore.NAME)
    }

    @Test
    fun `the throttle is driven by the attempt timestamp so a failure cannot retry every launch`() {
        val day = 24L * 60L * 60L * 1000L
        val now = 1_800_000_000_000L

        // Never checked before.
        assertTrue(UpdateStateStore.isCheckDue(lastAttemptAt = 0L, interval = UpdateCheckInterval.DAILY, now = now))
        // A failed check one hour ago must not be retried yet.
        assertFalse(
            UpdateStateStore.isCheckDue(
                lastAttemptAt = now - 60L * 60L * 1000L,
                interval = UpdateCheckInterval.DAILY,
                now = now
            )
        )
        assertTrue(UpdateStateStore.isCheckDue(lastAttemptAt = now - day, interval = UpdateCheckInterval.DAILY, now = now))
        // "Never" is never due, whatever the stamp says.
        assertFalse(UpdateStateStore.isCheckDue(lastAttemptAt = 0L, interval = UpdateCheckInterval.NEVER, now = now))
        assertFalse(UpdateStateStore.isCheckDue(lastAttemptAt = now - day, interval = UpdateCheckInterval.NEVER, now = now))
    }

    @Test
    fun `a stamp in the future is treated as due instead of wedging the schedule`() {
        val now = 1_800_000_000_000L
        // Happens when the clock is corrected backwards or the user travels across time zones.
        assertTrue(
            UpdateStateStore.isCheckDue(
                lastAttemptAt = now + 60L * 60L * 1000L,
                interval = UpdateCheckInterval.WEEKLY,
                now = now
            )
        )
    }

    @Test
    fun `weekly and monthly windows are longer than the daily one`() {
        val hour = 60L * 60L * 1000L
        val now = 1_800_000_000_000L
        val yesterday = now - 24L * hour
        assertFalse(UpdateStateStore.isCheckDue(yesterday, UpdateCheckInterval.WEEKLY, now))
        assertFalse(UpdateStateStore.isCheckDue(yesterday, UpdateCheckInterval.MONTHLY, now))
        assertTrue(UpdateStateStore.isCheckDue(yesterday, UpdateCheckInterval.DAILY, now))
    }
}
