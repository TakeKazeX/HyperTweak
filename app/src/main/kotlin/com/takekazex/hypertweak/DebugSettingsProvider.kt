package com.takekazex.hypertweak

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.takekazex.hypertweak.app.debug.DebugSettingsService
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** UID is checked on Binder entry before dispatch; call() has no automatic method permission. */
class DebugSettingsProvider : ContentProvider() {
    private val busy = AtomicBoolean(false)
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != 0) return response(false, JSONObject().put("error", "ROOT_REQUIRED"))
        if (!busy.compareAndSet(false, true)) return response(false, JSONObject().put("error", "BUSY"))
        val identity = Binder.clearCallingIdentity()
        return try {
            response(true, DebugSettingsService.execute(requireNotNull(context), uid, method, arg))
        } catch (e: Exception) {
            response(false, JSONObject().put("error", e.message?.take(200) ?: e.javaClass.simpleName))
        } finally { Binder.restoreCallingIdentity(identity); busy.set(false) }
    }
    private fun response(ok: Boolean, data: JSONObject) = Bundle().apply {
        putBoolean("ok", ok)
        putString("data", data.put("protocol", 1).put("ok", ok).toString())
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
