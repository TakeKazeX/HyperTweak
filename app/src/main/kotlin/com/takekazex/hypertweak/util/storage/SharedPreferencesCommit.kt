package com.takekazex.hypertweak.util.storage

import android.content.SharedPreferences

/**
 * Applies [block] to one editor and commits it synchronously, returning whether storage accepted
 * the write.
 *
 * androidx.core's `edit(commit = true) { }` returns Unit, which would turn a rejected write
 * (RemotePreferences daemon refusal, full disk) into a silent success. Every caller that needs the
 * acknowledgement goes through here instead of a raw `edit()...commit()` chain.
 */
inline fun SharedPreferences.commitEdit(block: SharedPreferences.Editor.() -> Unit): Boolean {
    @Suppress("UseKtx")
    val editor = edit()
    editor.block()
    return editor.commit()
}
