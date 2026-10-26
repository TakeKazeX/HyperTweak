package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import android.app.NotificationChannel
import android.content.Context
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 系统设置侧：让「频道详情」页重新显示 per-channel 「锁屏通知」(allow_keyguard) 开关。
 *
 * MIUI 的 `ChannelNotificationSettings.removeDefaultPrefs()` (`:75`) 会
 * `setPrefVisible(findPreference("allow_keyguard"), false)` 把「锁屏通知」复选框**隐藏**，而且
 * 该页的 `mAllowKeyguard` 字段从未被赋值（`updateDependents():296` 的 `if (mAllowKeyguard != null)`
 * 恒为假）。所以「应用通知类别 → 单个通知渠道」(ChannelNotificationSettings) 无法设置锁屏通知。
 *
 * 这个 hook 在 `removeDefaultPrefs` 之前捕获 `allow_keyguard` 这个 CheckBoxPreference，之后把它
 * 重新加回（`setPrefVisible(pref, true)`），并赋值 `mAllowKeyguard` + 绑定写「频道 key」的监听
 * （`NotificationSettingsHelper.setShowKeyguard(ctx, pkg, channelId, value)`）。`updateDependents`
 * 已经算好了 enabled/checked（基于 `canShowKeyguard(channelId)`），一旦 `mAllowKeyguard` 非空即可用。
 *
 * 与 `LockscreenAllNotificationsHooker`（SystemUI）同开关 `KEY_LOCKSCREEN_ALL_NOTIFICATIONS`：
 * 只在该开关打开时揭示；关闭时恢复 MIUI 默认的隐藏行为。
 *
 * 所有 androidx.preference 类（Preference/OnPreferenceChangeListener/PreferenceCategory）都在
 * Settings APK 里、不在模块类路径，因此全部按名字反射解析；监听器用 `Proxy` 实现宿主接口。
 */
object ChannelKeyguardToggleHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingLockscreenAllNotifications by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, false)
    }

    private const val TAG = "ChannelKeyguard"
    private const val CHANNEL_SETTINGS =
        "com.android.settings.notification.ChannelNotificationSettings"
    private const val SETTINGS_HELPER =
        "com.android.settings.notification.NotificationSettingsHelper"
    private const val PREF_KEY = "allow_keyguard"
    private const val LISTENER_IFACE =
        "androidx.preference.Preference\$OnPreferenceChangeListener"

    private var findPreference: Method? by generationState { null }
    private var setPrefVisible: Method? by generationState { null }
    private var getContext: Method? by generationState { null }
    private var setShowKeyguard: Method? by generationState { null }
    private var pkgField: Field? by generationState { null }
    private var channelField: Field? by generationState { null }
    private var allowKeyguardField: Field? by generationState { null }
    private var listenerInterface: Class<*>? by generationState { null }

    private var capturedPref: Any? by generationState { null }

    override fun onHook() {
        if (!settingLockscreenAllNotifications) {
            DebugLog.hookSkippedDebug(TAG, "per-channel keyguard toggle", "disabled")
            return
        }
        val clazz = CHANNEL_SETTINGS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CHANNEL_SETTINGS, "class not found")
            return
        }
        val removeDefaultPrefs = clazz.declaredMethods.firstOrNull {
            it.name == "removeDefaultPrefs" && it.parameterTypes.isEmpty()
        } ?: run {
            DebugLog.hookSkipped(TAG, "$CHANNEL_SETTINGS#removeDefaultPrefs", "method not found")
            return
        }
        findPreference = clazz.methods.firstOrNull {
            it.name == "findPreference" && it.parameterTypes.size == 1
        }
        setPrefVisible = findMethod(clazz, "setPrefVisible", 2)
        getContext = clazz.methods.firstOrNull {
            it.name == "getContext" && it.parameterTypes.isEmpty()
        }
        pkgField = findField(clazz, "mPkg")
        channelField = findField(clazz, "mChannel")
        allowKeyguardField = findField(clazz, "mAllowKeyguard")
        setShowKeyguard = SETTINGS_HELPER.toClassOrNull()?.let { helper ->
            helper.methods.firstOrNull {
                it.name == "setShowKeyguard" && it.parameterTypes.size == 4
            }?.apply { isAccessible = true }
        }
        listenerInterface = runCatching {
            Class.forName(LISTENER_IFACE, true, clazz.classLoader)
        }.getOrNull()
        if (findPreference == null || setPrefVisible == null || getContext == null ||
            pkgField == null || channelField == null || allowKeyguardField == null ||
            setShowKeyguard == null || listenerInterface == null
        ) {
            DebugLog.hookSkipped(TAG, "$CHANNEL_SETTINGS per-channel keyguard wiring", "resolve failed")
            return
        }
        removeDefaultPrefs.hook("channel_keyguard_toggle_reveal") {
            before { param ->
                HookFailurePolicy.open(TAG, "removeDefaultPrefs.before", Unit) {
                    val frag = param.thisObject
                    capturedPref = findPreference?.invoke(frag, PREF_KEY)
                }
            }
            after { param ->
                HookFailurePolicy.open(TAG, "removeDefaultPrefs.after", Unit) {
                    val frag = param.thisObject
                    val pref = capturedPref
                    capturedPref = null
                    if (pref == null) return@open

                    // Re-show the checkbox (it was removed moments ago by removeDefaultPrefs).
                    setPrefVisible?.invoke(frag, pref, true)
                    // Let the page handle it (updateDependents now finds mAllowKeyguard != null).
                    allowKeyguardField?.set(frag, pref)
                    // Persist an on-change toggle to the per-channel keyguard key via the provider.
                    val ctx = getContext?.invoke(frag) as? Context ?: return@open
                    val pkg = pkgField?.let { it.isAccessible = true; it.get(frag) as? String }
                        ?: return@open
                    val channel = channelField?.let {
                        it.isAccessible = true
                        (it.get(frag) as? NotificationChannel)?.id
                    } ?: return@open
                    setOnChangeListener(pref, ctx, pkg, channel)
                }
            }
        }
        DebugLog.d(TAG, "per-channel 锁屏通知 toggle revealed")
    }

    /** Bind a Proxy[OnPreferenceChangeListener] that writes the channel keyguard key on toggle. */
    private fun setOnChangeListener(pref: Any, ctx: Context, pkg: String, channel: String) {
        val iface = listenerInterface ?: return
        val setListener = pref.javaClass.methods.firstOrNull {
            it.name == "setOnPreferenceChangeListener" && it.parameterTypes.size == 1
        } ?: return
        val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, method, args ->
            when (method.name) {
                "onPreferenceChange" -> {
                    val value = args?.getOrNull(1) as? Boolean ?: return@newProxyInstance false
                    HookFailurePolicy.open(TAG, "onPreferenceChange", false) {
                        setShowKeyguard?.invoke(null, ctx, pkg, channel, value)
                        true
                    }
                }
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.getOrNull(0)
                "toString" -> "ChannelKeyguardListener@" +
                    Integer.toHexString(System.identityHashCode(p))
                else -> null
            }
        }
        setListener.isAccessible = true
        runCatching { setListener.invoke(pref, proxy) }
            .onFailure { DebugLog.w(TAG, "setOnPreferenceChangeListener failed", it) }
    }

    private fun findMethod(cls: Class<*>, name: String, paramCount: Int): Method? {
        var type: Class<*>? = cls
        while (type != null && type != Any::class.java) {
            val m = type.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == paramCount
            }
            if (m != null) {
                m.isAccessible = true
                return m
            }
            type = type.superclass
        }
        return null
    }

    private fun findField(cls: Class<*>, name: String): Field? {
        var type: Class<*>? = cls
        while (type != null && type != Any::class.java) {
            try {
                return HostAccess.requireDeclaredField(type, name, accessible = true)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        return null
    }
}
