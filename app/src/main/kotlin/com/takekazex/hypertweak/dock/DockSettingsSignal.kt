package com.takekazex.hypertweak.dock

import android.content.Context
import androidx.core.net.toUri
import com.takekazex.hypertweak.util.DebugLog

/** A hint to reread Preferences, never a second settings store or an untrusted value payload. */
internal object DockSettingsSignal {
    val uri = "content://com.takekazex.hypertweak.dock/settings".toUri()
    @Volatile private var context: Context? = null
    fun attach(context: Context) {
        this.context = context.applicationContext ?: context
        changed()
    }
    fun changed() {
        val target = context ?: return
        runCatching { target.contentResolver.notifyChange(uri, null) }
            .onFailure { DebugLog.w("DockBackground", "committed settings notification failed", it) }
    }
}
