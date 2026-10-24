package com.takekazex.hypertweak.hook.rules.securitycenter

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method

private object SecurityCenterPopupFeature : DexFeature {
    override val id = "securitycenter.sidebar_popups"
    override val schema = 1
}

internal data class SidebarPopupPolicy(val dolby: Boolean = false, val game: Boolean = false) {
    fun blocksTiming(text: Any?, dolbyText: Int) = dolby && text == dolbyText
}

/** Stable show hooks consume acknowledged settings, including already queued publication. */
object SecurityCenterPopupHooker : DynamicHooker() {
    private const val TAG = "SecurityCenterPopups"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var retired = false
    private var targets: Targets? = null
    private data class Targets(val methods: Map<String, Method>, val singleton: Field,
                               val launchView: Field, val dolbyText: Int, val callers: List<Method>)

    private fun policy() = SidebarPopupPolicy(
        Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_HIDE_DOLBY_POPUP, false),
        Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_HIDE_GAME_POPUP, false),
    )

    override fun onHook() {
        if (hookParam.packageName != SecurityCenterDockHost.PACKAGE || hookParam.processName != SecurityCenterDockHost.PROCESS) return
        retired = false
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(name = SecurityCenterPopupFeature.id, resolve = {
            DexRuntime.resolve(feature = SecurityCenterPopupFeature, apkPath = apk, cacheDir = dexCacheDirectory,
                compute = { SecurityCenterPopupResolver(DexRuntime.operands(apk)).resolve() },
                decode = { values, _ -> decode(values) })
        }, install = ::install)
    }

    private fun decode(values: Map<String, String>): Targets? = runCatching {
        val roles = setOf("timing", "dolbyProducer", "launch", "immediate", "wild", "launchRender", "wildRender",
            "removeLaunch", "removeImmediate", "removeWild", "window", "windowRender", "removeWindow")
        val methods = roles.associateWith { requireNotNull(MethodDescriptorCodec.method(values.getValue("m.$it"), classLoader)) }
        check(methods.values.all { it.returnType == Void.TYPE })
        Targets(methods, requireNotNull(MethodDescriptorCodec.field(values.getValue("singleton"), classLoader)),
            requireNotNull(MethodDescriptorCodec.field(values.getValue("launchView"), classLoader)), values.getValue("dolbyText").toInt(),
            values.getValue("callers").lineSequence().filter(String::isNotBlank).map {
                requireNotNull(MethodDescriptorCodec.method(it, classLoader))
            }.toList())
    }.onFailure { DebugLog.w(TAG, "sidebar popup contract unavailable; retaining native behavior", it) }.getOrNull()

    private fun install(t: Targets) {
        t.callers.forEach { deoptimize(it) }
        for (role in listOf("timing", "dolbyProducer", "launch", "immediate", "wild", "launchRender", "wildRender", "window", "windowRender")) {
            val method = t.methods.getValue(role)
            deoptimize(method)
            method.hook {
                before { param ->
                    if (!retired) {
                        val settings = policy()
                        val block = when (role) {
                            "timing" -> settings.blocksTiming(param.args.getOrNull(1), t.dolbyText)
                            "dolbyProducer" -> settings.dolby
                            else -> settings.game
                        }
                        if (block) param.result = null
                    }
                }
            }
        }
        targets = t
        refreshSettings()
        DebugLog.hookRegistered(TAG, "sidebar Dolby and game popup boundaries")
    }

    fun refreshSettings(): Boolean {
        Preferences.invalidateRuntimeReadCache()
        if (retired) return true
        main.post {
            if (!retired) runCatching { recoverVisiblePopups() }
                .onFailure { DebugLog.w(TAG, "existing popup recovery failed", it) }
        }
        return true
    }

    private fun recoverVisiblePopups() {
        val t = targets ?: return
        val settings = policy()
        if (settings.game) {
            val host = t.singleton.get(null)
            if (host != null) {
                // These two native removers own only the corresponding Wild views.
                for (role in listOf("removeImmediate", "removeWild")) runCatching {
                    t.methods.getValue(role).invoke(host, "HyperTweak settings")
                }.onFailure { DebugLog.w(TAG, "native $role failed", it) }
                val view = t.launchView.get(host) as? View
                val id = view?.resources?.getIdentifier("ll_features", "id", SecurityCenterDockHost.PACKAGE) ?: 0
                // The shared toast field can also contain unrelated action messages.
                if (view == null || (id != 0 && view.findViewById<View>(id) != null)) {
                    t.methods.getValue("removeLaunch").invoke(host, "HyperTweak settings", true)
                }
                t.methods.getValue("removeWindow").invoke(host, "HyperTweak settings")
            }
        }
        if (!settings.dolby) return
        // Includes a bubble created by the previous APK, which has no saved module state.
        // Retain the host's timed removal/accounting: only its already visible presentation is hidden.
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getDeclaredMethod("getInstance").invoke(null)
        val roots = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as? List<*> ?: return
        for (root in roots.toList().filterIsInstance<View>()) {
            if (root.context.packageName != SecurityCenterDockHost.PACKAGE ||
                (root.layoutParams as? WindowManager.LayoutParams)?.type != WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) continue
            val resources = root.resources
            if (resources.getResourceEntryName(t.dolbyText) != "vb_video_effects_dolby_open") continue
            val text = resources.getText(t.dolbyText)
            fun matches(view: View): Boolean = when (view) {
                is TextView -> view.text == text
                is ViewGroup -> (0 until view.childCount).any { matches(view.getChildAt(it)) }
                else -> false
            }
            if (matches(root)) root.visibility = View.INVISIBLE
        }
    }

    override fun recoverHotReloadHosts() { refreshSettings() }
    override fun onPrepareHotReload() {
        retired = true
        main.removeCallbacksAndMessages(null)
        targets = null
    }
}
