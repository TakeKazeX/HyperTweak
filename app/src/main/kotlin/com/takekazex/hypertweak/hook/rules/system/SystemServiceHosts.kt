package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.HostAccess
import java.lang.reflect.Field

/** Verified framework-owned roots, also available when an older module saved no feature hosts. */
internal object SystemServiceHosts {
    fun singleton(loader: ClassLoader, name: String): Any? {
        val owner = Class.forName(name + "\$Provider\$SINGLETON", true, loader)
        return HostAccess.requireDeclaredField(owner, "INSTANCE", accessible = true).get(null)
    }

    fun binderOwner(loader: ClassLoader, service: String, owner: Class<*>): Any? {
        val manager = Class.forName("android.os.ServiceManager", false, loader)
        val binder = HostAccess.requireDeclaredMethod(manager, "getService", String::class.java, accessible = true)
            .invoke(null, service) ?: return null
        val field = HostAccess.cached(binder.javaClass, "outer-service:${owner.name}", Field::class.java) {
            binder.javaClass.declaredFields.singleOrNull { it.type == owner }?.apply { isAccessible = true }
        } ?: return null
        return field.get(binder)
    }
}
