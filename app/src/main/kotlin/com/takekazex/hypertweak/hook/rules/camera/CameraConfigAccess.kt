package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.base.BaseHooker

/** One lazy host identity shared by independent camera feature installers. */
internal class CameraConfigAccess(owner: BaseHooker) {
    val profile by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CameraHostProfile.resolve(CameraResolver.Ctx(owner.classLoader, owner.hookParam.appInfo), "CamImpersonate")
    }
    fun activeInstance(): Any? = runCatching { profile?.configInstance() }.getOrNull()
    fun dispatchClasses(): List<Class<*>> = activeInstance()?.javaClass?.let(::listOf).orEmpty()
}
