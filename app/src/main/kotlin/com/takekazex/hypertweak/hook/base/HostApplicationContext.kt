package com.takekazex.hypertweak.hook.base

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.function.Consumer

/**
 * Hands out the host Application only after LoadedApk has published it.
 *
 * The application-attach callback runs inside `Application.attach`, before `makeApplication`
 * calls `ContextImpl.setOuterContext(app)`. A receiver registered then is keyed on the bare
 * ContextImpl, while every later unregister is keyed on the Application: it fails with
 * "Receiver not registered", and the retired generation's receiver keeps firing after hot
 * reload. `LoadedApk.mApplication` is assigned after `setOuterContext`, so a non-null
 * `applicationContext` proves the registration key is final. Until then the action is posted
 * behind the bind-application message that is still attaching the host.
 */
object HostApplicationContext {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Runs [action] with the published Application; callers re-check their own retirement. */
    fun whenPublished(context: Context, action: Consumer<Context>) {
        context.applicationContext?.let { action.accept(it); return }
        main.post {
            val application = context.applicationContext
            if (application == null) {
                DebugLog.w("HostContext", "application still unpublished after attach; using the attach context")
            }
            action.accept(application ?: context)
        }
    }
}
