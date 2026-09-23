package com.takekazex.hypertweak.hook.rules.camera

import android.content.ContentProvider
import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Runs config-dependent camera hooks after the host initializes its shared config provider. */
internal object CameraApplicationInit {
    private data class Pending(val actions: LinkedHashMap<BaseHooker, () -> Unit> = linkedMapOf())

    private val lock = Any()
    private val pendingByLoader = WeakHashMap<ClassLoader, Pending>()
    private val completedLoaders = WeakHashMap<ClassLoader, Boolean>()

    fun afterConfigProviderCreate(hooker: BaseHooker, action: () -> Unit) {
        val loader = hooker.classLoader
        if (hooker.hookParam.isPackageReady) {
            postAction(hooker, action)
            return
        }

        var alreadyCreated = false
        synchronized(lock) {
            if (completedLoaders.containsKey(loader)) {
                alreadyCreated = true
                return@synchronized
            }
            val pending = pendingByLoader[loader]
            if (pending != null) {
                pending.actions[hooker] = action
                return
            }

            val callbacks = Pending(linkedMapOf(hooker to action))
            pendingByLoader[loader] = callbacks
            val method = resolveConfigReadyMethod(hooker)
            if (method == null) {
                pendingByLoader.remove(loader)
                DebugLog.w("CamAppInit", "camera config initialization boundary was not resolved; deferred hooks skipped")
                return
            }

            runCatching {
                with(hooker) {
                    method.hook("camera_config_provider_initialized") {
                        after {
                            val actions = synchronized(lock) {
                                completedLoaders[loader] = true
                                pendingByLoader.remove(loader)?.actions?.toList().orEmpty()
                            }
                            DebugLog.i("CamAppInit", "config-ready boundary returned; running deferred hooks=${actions.size}")
                            actions.forEach { (owner, queued) -> runAction(owner, queued) }
                        }
                    }
                }
            }.onFailure { t ->
                pendingByLoader.remove(loader)
                DebugLog.w("CamAppInit", "failed to hook camera config initialization boundary", t)
            }
        }
        if (alreadyCreated) postAction(hooker, action)
    }

    /** The semantic provider completes the camera config singleton before Application.onCreate. */
    private fun resolveConfigReadyMethod(hooker: BaseHooker): Method? {
        if (hooker.hookParam.isMainProcess) {
            resolveConfigProviderOnCreate(hooker)?.let {
                DebugLog.i("CamAppInit", "resolved config-ready boundary at ${it.declaringClass.name}#${it.name}()")
                return it
            }
        }
        return resolveApplicationOnCreate(hooker)?.also {
            DebugLog.w("CamAppInit", "config provider was unavailable; using ${it.declaringClass.name}#${it.name}()")
        }
    }

    private fun resolveConfigProviderOnCreate(hooker: BaseHooker): Method? {
        val ctx = CameraResolver.Ctx(hooker.classLoader, hooker.hookParam.appInfo)
        val providerClass = CameraResolver.resolveClassByStrings(
            scope = "CamAppInit",
            key = "camera_config_initializing_provider",
            ctx = ctx,
            anchors = listOf("MiviInfoContentProvider"),
            validate = { type ->
                ContentProvider::class.java.isAssignableFrom(type) &&
                    type.declaredMethods.count {
                        it.name == "onCreate" && !Modifier.isStatic(it.modifiers) &&
                            it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE &&
                            !it.isBridge && !it.isSynthetic
                    } == 1
            },
        ) ?: return null
        return providerClass.declaredMethods.singleOrNull {
            it.name == "onCreate" && !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE &&
                !it.isBridge && !it.isSynthetic
        }?.apply { isAccessible = true }
    }

    private fun resolveApplicationOnCreate(hooker: BaseHooker): Method? {
        val className = hooker.hookParam.appInfo?.className?.takeIf { it.isNotBlank() } ?: return null
        val applicationClass = runCatching { hooker.classLoader.loadClass(className) }.getOrNull() ?: return null
        return runCatching { applicationClass.getDeclaredMethod("onCreate") }
            .getOrNull()
            ?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Void.TYPE
            }
            ?.apply { isAccessible = true }
    }

    private fun runAction(hooker: BaseHooker, action: () -> Unit) {
        runCatching(action).onFailure { t ->
            DebugLog.w("CamAppInit", "deferred ${hooker.hookerName} initialization failed", t)
        }
    }

    private fun postAction(hooker: BaseHooker, action: () -> Unit) {
        Handler(Looper.getMainLooper()).post { runAction(hooker, action) }
    }
}
