package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import java.lang.reflect.Modifier

/** Cached access to the native manager; a late registration may recover without a new hook. */
internal class FullAodDozingSource(
    private val managerClass: Class<*>,
    registryClass: Class<*>,
    private val api: Class<*>,
) {
    private val getImpl = registryClass.getMethod("getImpl", Class::class.java).apply {
        require(Modifier.isStatic(modifiers) && returnType == Any::class.java)
    }
    private val dozing = managerClass.getField("isDozing").apply {
        require(type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(modifiers))
    }
    @Volatile private var owner: Any? = null

    init { require(api.isAssignableFrom(managerClass)) }

    fun bind(manager: Any) {
        require(managerClass.isInstance(manager))
        owner = manager
    }

    fun read(): Boolean? {
        val manager = owner ?: getImpl.invoke(null, api)?.also(::bind) ?: return null
        return dozing.getBoolean(manager)
    }

    companion object {
        const val MANAGER = "com.android.keyguard.fullaod.MiuiFullAodManager"
        const val REGISTRY = "com.miui.systemui.interfacesmanager.InterfacesImplManager"
        const val API = "com.miui.interfaces.keyguard.IMiuiFullAodManager"
    }
}
