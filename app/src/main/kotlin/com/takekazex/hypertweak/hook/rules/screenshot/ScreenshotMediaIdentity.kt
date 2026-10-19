package com.takekazex.hypertweak.hook.rules.screenshot

import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.core.net.toUri

/** Uses the owner's MediaStore row, including pending rows; FUSE file visibility is not a save fence. */
internal data class ScreenshotMediaIdentity(val key: String, val stamp: String, val width: Int, val height: Int, val mime: String) {
    companion object {
        fun read(context: Context, uri: Uri): ScreenshotMediaIdentity? {
            val user = Process.myUid() / 100_000
            val file = uri.scheme == "file"
            if (!file && ScreenshotFramePolicy.mediaKey(uri.toString(), user) == null) return null
            val target = if (file) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else uri
            val projection = arrayOf("_id", "_display_name", "date_added", "width", "height", "mime_type", "owner_package_name")
            context.contentResolver.query(target, projection, if (file) "_data=?" else null,
                if (file) arrayOf(uri.path ?: return null) else null, null)?.use { row ->
                if (!row.moveToFirst() || row.getString(6) != "com.miui.screenshot") return null
                val width = row.getInt(3)
                val height = row.getInt(4)
                val mime = row.getString(5) ?: return null
                if (width <= 0 || height <= 0) return null
                val key = ScreenshotFramePolicy.mediaKey("content://media/external/images/media/${row.getLong(0)}", user) ?: return null
                val contentUri = if (file) "content://media/external/images/media/${row.getLong(0)}".toUri() else uri
                val bytes = context.contentResolver.openFileDescriptor(contentUri, "r")?.use { it.statSize } ?: return null
                val stamp = listOf(row.getString(1), row.getLong(2), width, height, mime, bytes).joinToString("\u001f")
                return ScreenshotMediaIdentity(key, stamp, width, height, mime)
            }
            return null
        }
    }
}
