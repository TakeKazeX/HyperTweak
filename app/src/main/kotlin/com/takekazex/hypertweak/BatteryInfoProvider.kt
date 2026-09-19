package com.takekazex.hypertweak

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.takekazex.hypertweak.util.BatteryInfoChannel

/**
 * Holds the latest battery-info snapshot pushed by the privileged hooker(s).
 *
 * The provider runs in the module's own (main) process, so it keeps the snapshot in memory for as
 * long as the process lives. On a foreground page request, `com.miui.securitycenter` (system uid)
 * publishes formatted values and their actual sample times; the UI reads that immutable snapshot.
 * A missing snapshot (Security Center never ran, or the process died) returns an empty bundle and falls
 * back to the always-available `BatteryManager`/sysfs tiers.
 *
 * Both directions are caller-checked, because `ContentProvider.call` is NOT covered by the
 * manifest's `readPermission`/`writePermission`: [METHOD_SET] only accepts the system-uid hooked
 * privilege centre (so an arbitrary app cannot poison the snapshot), and [METHOD_GET] only accepts
 * the module itself plus root/shell for adb diagnostics. The snapshot carries hardware identity
 * (battery serial, `soh_sn`, manufacturing/first-use dates, authenticity), so it must never be
 * readable by an ordinary third-party app.
 */
class BatteryInfoProvider : ContentProvider() {

    @Volatile
    private var snapshot = Bundle()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            BatteryInfoChannel.METHOD_SET -> {
                if (android.os.Binder.getCallingUid() != Process.SYSTEM_UID || extras == null) return null
                val updatedAt = extras.getLong(BatteryInfoChannel.KEY_UPDATED_AT, -1)
                if (updatedAt < 0 || updatedAt > SystemClock.elapsedRealtime() ||
                    extras.getBundle(BatteryInfoChannel.KEY_SAMPLED_AT) == null ||
                    extras.getBundle(BatteryInfoChannel.KEY_VALID_UNTIL) == null) return null
                // One immutable publication: timestamps cannot come from a different sample.
                snapshot = Bundle(extras)
                return Bundle().apply { putLong(BatteryInfoChannel.KEY_UPDATED_AT, updatedAt) }
            }
            BatteryInfoChannel.METHOD_GET -> {
                if (!isTrustedReader()) return null
                return Bundle(snapshot)
            }
        }
        return null
    }

    /** The module's own settings UI, plus root/shell so the values stay inspectable over adb. */
    private fun isTrustedReader(): Boolean {
        val uid = android.os.Binder.getCallingUid()
        return uid == Process.myUid() || uid == Process.ROOT_UID || uid == Process.SHELL_UID
    }

    // The rest of the provider surface is unused; the snapshot travels completely over [call].
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
