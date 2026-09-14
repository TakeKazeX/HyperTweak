package com.takekazex.hypertweak.hook.rules.settings

import android.app.NotificationChannel
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 通知角标 (Settings): the same "shell only" defect as
 * [NotificationMoreSettingsHooker], applied to the channel page's 「显示角标」 checkbox.
 *
 * `removeDefaultPrefs()` removes the `setting_badge` preference, `BaseNotificationSettings.mBadge`
 * is never assigned, and the ROM's own listener is dead code — the MIUI variant keeps
 * `ChannelNotificationSettings$2`, the AOSP variant has no such class at all. This hook reveals the
 * checkbox and installs the write-back the ROM dropped.
 *
 * Unlike importance, the initial checked state needs no manual sync: `updateDependents` runs right
 * after `removeDefaultPrefs()` and already does
 * `setChecked(this.mBadge, !blocked && this.mChannel.canShowBadge())`, which becomes correct the
 * moment `mBadge` is assigned. The write-back mirrors the ROM's own `$2`: `setShowBadge` →
 * `lockFields(USER_LOCKED_SHOW_BADGE)` → `updateChannel` → `refreshNotificationShade(false)`.
 *
 * The preference key is `setting_badge`, not `badge` (upstream's `VISIBLE_PREF_KEYS` lists the
 * latter, which never matches anything).
 */
object NotificationBadgeSettingsHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "NotifBadge"
    private const val BASE = "com.android.settings.notification.BaseNotificationSettings"
    private const val PREFERENCE = "androidx.preference.Preference"
    private const val BACKEND = "com.android.settings.notification.MiuiNotificationBackend"
    private const val PREF_KEY = "setting_badge"
    private const val USER_LOCKED_SHOW_BADGE = 128
    private const val LISTENER_IFACE =
        "androidx.preference.Preference\$OnPreferenceChangeListener"

    /** Both channel-page variants read the same XML, so both need their own `removeDefaultPrefs`. */
    private val VARIANTS = listOf(
        "com.android.settings.notification.ChannelNotificationSettings",
        "com.android.settings.notification.app.ChannelNotificationSettings"
    )

    private var badgeField: Field? = null
    private var backendField: Field? = null
    private var pkgField: Field? = null
    private var uidField: Field? = null
    private var channelField: Field? = null
    private var refreshNotificationShade: Method? = null
    private var setListener: Method? = null
    private var updateChannelMethod: Method? = null
    private var getKeyMethod: Method? = null
    private var lockFieldsMethod: Method? = null
    private var listenerInterface: Class<*>? = null

    /** The preference captured just before `removeDefaultPrefs` detaches it. */
    @Volatile
    private var capturedPref: Any? = null

    override fun onPrepareHotReload() {
        capturedPref = null
    }

    override fun onHook() {
        if (!Preferences.notificationBadge()) {
            DebugLog.hookSkipped(TAG, "channel badge checkbox", "disabled")
            return
        }
        val baseClass = BASE.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BASE, "class not found")
            return
        }
        if (!resolveReflection(baseClass)) {
            DebugLog.hookSkipped(TAG, "channel badge wiring", "resolve failed")
            return
        }

        // `setPrefVisible` is declared once on the base class and is not overridden by either
        // variant, so a single hook covers both. The hook id differs from the importance hooker's,
        // so the two switches can be enabled independently.
        val setPrefVisible = findMethod(baseClass, "setPrefVisible", 2) ?: run {
            DebugLog.hookSkipped(TAG, "$BASE#setPrefVisible", "method not found")
            return
        }
        setPrefVisible.hook("notification_badge_reveal") {
            before { param ->
                HookFailurePolicy.open(TAG, "setPrefVisible.before", Unit) {
                    val pref = param.args.getOrNull(0) ?: return@open
                    val key = getKeyMethod?.invoke(pref) as? String
                    if (key == PREF_KEY) param.args[1] = true
                }
            }
        }

        var hookedVariants = 0
        for (name in VARIANTS) {
            val clazz = name.toClassOrNull() ?: continue
            val removeDefaultPrefs = findMethod(clazz, "removeDefaultPrefs", 0) ?: continue
            val findPreference = clazz.methods.firstOrNull {
                it.name == "findPreference" && it.parameterTypes.size == 1
            }
            removeDefaultPrefs.hook("notification_badge_bind") {
                before { param ->
                    HookFailurePolicy.open(TAG, "removeDefaultPrefs.before", Unit) {
                        capturedPref = findPreference?.invoke(param.thisObject, PREF_KEY)
                    }
                }
                after { param ->
                    HookFailurePolicy.open(TAG, "removeDefaultPrefs.after", Unit) {
                        val frag = param.thisObject
                        val pref = capturedPref
                        capturedPref = null
                        if (pref == null) return@open

                        // The ROM never assigns this field, so the checkbox would stay inert.
                        badgeField?.set(frag, pref)
                        // No value sync needed: the updateDependents() that follows this call
                        // already computes setChecked(mBadge, canShowBadge()).
                        installBadgeListener(frag, pref)
                    }
                }
            }
            hookedVariants++
        }
        if (hookedVariants == 0) {
            DebugLog.hookSkipped(TAG, "channel badge wiring", "no channel page variant found")
            return
        }
        DebugLog.hookRegistered(TAG, "channel badge checkbox revealed + writable")
    }

    /** Resolves every reflectively-reached member once; false when an essential one is missing. */
    private fun resolveReflection(baseClass: Class<*>): Boolean {
        val preference = PREFERENCE.toClassOrNull()
        val backend = BACKEND.toClassOrNull()
        badgeField = findField(baseClass, "mBadge")
        backendField = findField(baseClass, "mBackend")
        pkgField = findField(baseClass, "mPkg")
        uidField = findField(baseClass, "mUid")
        channelField = findField(baseClass, "mChannel")
        // Protected on BaseNotificationSettings — reflection must open it explicitly.
        refreshNotificationShade = findMethod(baseClass, "refreshNotificationShade", 1)
        setListener = preference?.let { findMethod(it, "setOnPreferenceChangeListener", 1) }
        getKeyMethod = preference?.let { findMethod(it, "getKey", 0) }
        updateChannelMethod = backend?.methods?.firstOrNull {
            it.name == "updateChannel" && it.parameterTypes.size == 3
        }?.apply { isAccessible = true }
        lockFieldsMethod = runCatching {
            NotificationChannel::class.java.getMethod("lockFields", Int::class.javaPrimitiveType)
        }.getOrNull()
        listenerInterface = runCatching {
            Class.forName(LISTENER_IFACE, false, baseClass.classLoader)
        }.getOrNull()

        return badgeField != null &&
            backendField != null &&
            pkgField != null &&
            uidField != null &&
            channelField != null &&
            refreshNotificationShade != null &&
            setListener != null &&
            updateChannelMethod != null &&
            lockFieldsMethod != null &&
            listenerInterface != null
    }

    /** Installs a Proxy[OnPreferenceChangeListener] that persists the badge checkbox. */
    private fun installBadgeListener(frag: Any, pref: Any) {
        val iface = listenerInterface ?: return
        val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, method, args ->
            when (method.name) {
                "onPreferenceChange" -> HookFailurePolicy.open(TAG, "onPreferenceChange", true) {
                    val value = args?.getOrNull(1) as? Boolean ?: return@open true
                    writeBadge(frag, value)
                    true
                }
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.getOrNull(0)
                "toString" -> "NotifBadge@" +
                    Integer.toHexString(System.identityHashCode(p))
                else -> null
            }
        }
        runCatching { setListener?.invoke(pref, proxy) }
            .onFailure { DebugLog.w(TAG, "setOnPreferenceChangeListener failed", it) }
    }

    /** Mirrors the ROM's own dead `ChannelNotificationSettings$2` write-back path. */
    private fun writeBadge(frag: Any, showBadge: Boolean) {
        val channel = channelField?.get(frag) as? NotificationChannel ?: return
        channel.setShowBadge(showBadge)
        lockFieldsMethod?.invoke(channel, USER_LOCKED_SHOW_BADGE)
        val backend = backendField?.get(frag) ?: return
        val pkg = pkgField?.get(frag) as? String ?: return
        val uid = uidField?.getInt(frag) ?: return
        updateChannelMethod?.invoke(backend, pkg, uid, channel)
        refreshNotificationShade?.invoke(frag, false)
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
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        return null
    }
}
