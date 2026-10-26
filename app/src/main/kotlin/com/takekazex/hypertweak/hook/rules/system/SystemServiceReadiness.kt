package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.cachedPublicMethod
import android.os.IBinder
import java.lang.reflect.Method

/** Probe the actual non-blocking binder capability before touching settings/content observers. */
internal object SystemServiceReadiness {
    private val checkService: Method? by lazy {
        runCatching { Class.forName("android.os.ServiceManager").cachedPublicMethod("checkService", String::class.java, accessible = false) }.getOrNull()
    }
    fun contentReady(): Boolean = available("content")
    fun available(name: String): Boolean = runCatching {
        (checkService?.invoke(null, name) as? IBinder)?.isBinderAlive == true
    }.getOrDefault(false)
}
