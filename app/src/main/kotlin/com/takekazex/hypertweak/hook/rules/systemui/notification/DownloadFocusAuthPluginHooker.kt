package com.takekazex.hypertweak.hook.rules.systemui.notification

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.shared.DownloadFocusIdentity
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.WeakHashMap

/** Native auth-success branch for a marked provider notification, after native per-app gates. */
object DownloadFocusAuthPluginHooker : StaticHooker() {
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_FOCUS_NOTIFICATION, false)
    }
    private const val TAG = "DownloadFocusAuth"
    private val main = Handler(Looper.getMainLooper())
    private var ready by generationState { false }
    @Volatile private var retiring = false
    private val loaders = generationMainResource(WeakHashMap<ClassLoader, Boolean>()) { it.clear() }

    override fun onHook() {
        ready = true
        retiring = false
        noteDeferredInstallation()
        main.post { if (!retiring) hookLoadedPluginLoaders() }
    }

    fun hookPluginLoader(loader: ClassLoader?) {
        if (loader == null || !ready || retiring) return
        HostAccess.onMain {
            if (loaders.containsKey(loader)) return@onMain
            val controller = runCatching { loader.loadClass("miui.systemui.notification.focus.FocusNotificationController") }.getOrNull()
            if (controller == null) { loaders[loader] = false; return@onMain }
            HookFailurePolicy.open(TAG, "resolve marked notification auth", Unit) {
                val callback = loader.loadClass("miui.systemui.notification.focus.InflateAndAuthCallBack")
                val signature = listOf(Context::class.java, StatusBarNotification::class.java,
                    String::class.java, Bundle::class.java, callback)
                val fetch = controller.declaredMethods.single { it.returnType == Void.TYPE && it.parameterTypes.toList() == signature }
                val success = HostAccess.requirePublicMethod(callback, "onAuthSuccess", String::class.java,
                    String::class.java, accessible = false)
                check(success.returnType == Void.TYPE)
                deoptimize(fetch)
                fetch.hook {
                    before { param ->
                        if (retiring || !enabled) return@before
                        HookFailurePolicy.open(TAG, "marked download notification auth", Unit) {
                            val sbn = param.args.getOrNull(1) as? StatusBarNotification ?: return@open
                            if (!DownloadFocusIdentity.owns(sbn.packageName, sbn.id, sbn.tag,
                                    sbn.notification.extras.getLong(DownloadFocusIdentity.EXTRA_TASK_ID, -1))) return@open
                            val receiver = param.args.getOrNull(4) ?: return@open
                            // Same callback and arguments as the verified native success branch.
                            success.invoke(receiver, sbn.key, param.args[2])
                            param.result = null
                        }
                    }
                }
                loaders[loader] = true
                DebugLog.d(TAG, "marked download notification auth installed")
            }
        }
    }

    private fun hookLoadedPluginLoaders() = HookFailurePolicy.open(TAG, "find focus plugin", Unit) {
        val dependency = classLoader.loadClass("com.android.systemui.plugins.PluginDependency").getField("INSTANCE").get(null) ?: return@open
        val provider = HostAccess.invoke(dependency, "getSProvider") ?: return@open
        val lazyManager = HostAccess.read(provider, "managerLazy") ?: return@open
        val manager = HostAccess.invoke(lazyManager, "get") ?: return@open
        val actions = HostAccess.read(manager, "pluginMap") as? Map<*, *> ?: return@open
        actions.values.filterNotNull().forEach { action ->
            (HostAccess.read(action, "pluginInstances") as? List<*>)?.filterNotNull()?.forEach { instance ->
                val data = HostAccess.read(instance, "pluginData")
                val context = data?.let { HostAccess.read(it, "context") } as? Context
                hookPluginLoader(context?.classLoader)
            }
        }
    }

    override fun onPrepareHotReload() {
        retiring = true
        ready = false
        main.removeCallbacksAndMessages(null)
    }
}
