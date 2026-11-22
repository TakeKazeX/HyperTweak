package com.takekazex.hypertweak.hook.rules.phrase

import android.content.ComponentName
import android.content.ContentProvider
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.provider.Settings

internal object ClipboardProviderAccess {
    fun isLocalHistory(uri: Uri?): Boolean =
        uri?.scheme == "content" && uri.authority == "com.miui.phrase.input.provider" &&
            uri.pathSegments == listOf("getClipboardList")

    fun isDefaultIme(provider: ContentProvider): Boolean {
        val context = provider.context ?: return false
        val callerUid = Binder.getCallingUid()
        if (callerUid == Process.myUid() || Binder.getCallingUserHandle() != Process.myUserHandle()) return false
        val caller = provider.callingPackage ?: return false
        val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let(ComponentName::unflattenFromString) ?: return false
        val uidPackages = context.packageManager.getPackagesForUid(callerUid)
        return caller == selected.packageName && uidPackages?.singleOrNull() == caller
    }
}
