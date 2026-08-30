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
 * long as the process lives. `com.miui.securitycenter` (system uid) calls [METHOD_SET] with the
 * formatted values; the settings UI queries [METHOD_GET] and renders them. A missing snapshot (the
 * security center never ran, or the process died) just returns an empty bundle and the page falls
 * back to the always-available `BatteryManager`/sysfs tiers.
 *
 * [METHOD_SET] is only honoured for system-uid callers (the hooked privilege centre), so an
 * arbitrary app cannot poison the snapshot; [METHOD_GET] is open (battery info, read-only).
 */
class BatteryInfoProvider : ContentProvider() {

    @Volatile
    private var snapshot = Bundle()
    @Volatile
    private var updatedAt = 0L

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            BatteryInfoChannel.METHOD_SET -> {
                if (android.os.Binder.getCallingUid() != Process.SYSTEM_UID || extras == null) return null
                snapshot = Bundle(extras)
                updatedAt = SystemClock.elapsedRealtime()
                return Bundle().apply { putLong(BatteryInfoChannel.KEY_UPDATED_AT, updatedAt) }
            }
            BatteryInfoChannel.METHOD_GET -> {
                val out = Bundle(snapshot)
                out.putLong(BatteryInfoChannel.KEY_UPDATED_AT, updatedAt)
                return out
            }
            BatteryInfoChannel.METHOD_CLEAR -> {
                snapshot = Bundle()
                updatedAt = 0L
                return null
            }
        }
        return null
    }

    // The rest of the provider surface is unused; the snapshot travels completely over [call].
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
