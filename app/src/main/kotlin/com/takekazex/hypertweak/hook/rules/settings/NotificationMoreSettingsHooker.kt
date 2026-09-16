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
 * 恢复更多通知设置 (Settings): puts the channel details page's 「重要性」 dropdown back and wires up
 * the write-back listener HyperOS never shipped.
 *
 * The preference is already in `miui_channel_notification_settings.xml`, but
 * `removeDefaultPrefs()` calls `setPrefVisible(findPreference("importance"), false)` and the whole
 * APK only ever *reads* `mImportance` — the field is never assigned (so
 * `setEnabled(this.mImportance, …)` is a no-op on a permanently null field) and no
 * `OnPreferenceChangeListener` is ever installed. The MIUI variant keeps its listener class as dead
 * code (`ChannelNotificationSettings$3`), the AOSP variant does not even contain it. Revealing the
 * row without this writes nothing.
 *
 * This hook therefore does both halves:
 *  1. `setPrefVisible` before: force the `true` argument for the `importance` key so the drop-down
 *     is never removed from `main_category`;
 *  2. `removeDefaultPrefs` after: assign `mImportance`, sync the current selection from
 *     `mBackupImportance`, and install a `Proxy[OnPreferenceChangeListener]` that writes the
 *     channel importance back through `MiuiNotificationBackend.updateChannel` (Settings is
 *     `android.uid.system`, so NMS's system-caller check passes).
 *
 * `allow_keyguard` is deliberately untouched — `ChannelKeyguardToggleHooker` owns it under its own
 * switch, and revealing a checkbox whose backing field stays unassigned would only show a broken
 * control. `setting_badge` is likewise owned by `NotificationBadgeSettingsHooker`.
 */
object NotificationMoreSettingsHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "NotifMoreSettings"
    private const val BASE = "com.android.settings.notification.BaseNotificationSettings"
    private const val DROP_DOWN = "miuix.preference.DropDownPreference"
    private const val PREFERENCE = "androidx.preference.Preference"
    private const val BACKEND = "com.android.settings.notification.MiuiNotificationBackend"
    private const val PREF_KEY = "importance"
    private const val USER_LOCKED_IMPORTANCE = 4
    private const val LISTENER_IFACE =
        "androidx.preference.Preference\$OnPreferenceChangeListener"

    /** Both channel-page variants read the same XML, so both need their own `removeDefaultPrefs`. */
    private val VARIANTS = listOf(
        "com.android.settings.notification.ChannelNotificationSettings",
        "com.android.settings.notification.app.ChannelNotificationSettings"
    )

    private var importanceField: Field? = null
    private var backupImportanceField: Field? = null
    private var backendField: Field? = null
    private var pkgField: Field? = null
    private var uidField: Field? = null
    private var channelField: Field? = null
    private var findSpinnerIndexOfValue: Method? = null
    private var setValueIndex: Method? = null
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
        if (!Preferences.notificationMoreSettings()) {
            DebugLog.hookSkippedDebug(TAG, "channel importance dropdown", "disabled")
            return
        }
        val baseClass = BASE.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BASE, "class not found")
            return
        }
        if (!resolveReflection(baseClass)) {
            DebugLog.hookSkipped(TAG, "channel importance wiring", "resolve failed")
            return
        }

        // ① Keep the drop-down attached: `setPrefVisible` is declared once on the base class and is
        //    not overridden by either variant, so a single hook covers both.
        val setPrefVisible = findMethod(baseClass, "setPrefVisible", 2) ?: run {
            DebugLog.hookSkipped(TAG, "$BASE#setPrefVisible", "method not found")
            return
        }
        setPrefVisible.hook("notification_more_settings_reveal") {
            before { param ->
                HookFailurePolicy.open(TAG, "setPrefVisible.before", Unit) {
                    val pref = param.args.getOrNull(0) ?: return@open
                    val key = getKeyMethod?.invoke(pref) as? String
                    if (key == PREF_KEY) param.args[1] = true
                }
            }
        }

        // ② Assign mImportance and install the write-back listener per variant.
        var hookedVariants = 0
        for (name in VARIANTS) {
            val clazz = name.toClassOrNull() ?: continue
            val removeDefaultPrefs = findMethod(clazz, "removeDefaultPrefs", 0) ?: continue
            val findPreference = clazz.methods.firstOrNull {
                it.name == "findPreference" && it.parameterTypes.size == 1
            }
            removeDefaultPrefs.hook("notification_more_settings_bind") {
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

                        // The ROM never assigns this field; setEnabled(null, …) is why the control
                        // stays inert even when it is revealed.
                        importanceField?.set(frag, pref)

                        // Align the drop-down with the channel's real importance. Upstream gates the
                        // whole block on `mBackupImportance > 0`, which also skips the listener and
                        // leaves blocked channels unwritable; only the value sync is conditional.
                        // `updateDependents` runs after this and disables the drop-down for blocked
                        // channels, so the listener stays reachable only where the ROM allows edits.
                        val backup = backupImportanceField?.getInt(frag) ?: 0
                        if (backup > 0) {
                            val index = findSpinnerIndexOfValue
                                ?.invoke(pref, backup.toString()) as? Int ?: -1
                            if (index > -1) setValueIndex?.invoke(pref, index)
                        }
                        installImportanceListener(frag, pref)
                    }
                }
            }
            hookedVariants++
        }
        if (hookedVariants == 0) {
            DebugLog.hookSkipped(TAG, "channel importance wiring", "no channel page variant found")
            return
        }
        DebugLog.hookRegistered(TAG, "channel importance dropdown revealed + writable")
    }

    /** Resolves every reflectively-reached member once; false when an essential one is missing. */
    private fun resolveReflection(baseClass: Class<*>): Boolean {
        val dropDown = DROP_DOWN.toClassOrNull()
        val preference = PREFERENCE.toClassOrNull()
        val backend = BACKEND.toClassOrNull()
        importanceField = findField(baseClass, "mImportance")
        backupImportanceField = findField(baseClass, "mBackupImportance")
        backendField = findField(baseClass, "mBackend")
        pkgField = findField(baseClass, "mPkg")
        uidField = findField(baseClass, "mUid")
        channelField = findField(baseClass, "mChannel")
        // Private on miuix.preference.DropDownPreference — reflection must open it explicitly,
        // otherwise the value sync silently fails the way upstream's swallowed-catch does.
        findSpinnerIndexOfValue = dropDown?.let { findMethod(it, "findSpinnerIndexOfValue", 1) }
        setValueIndex = dropDown?.let { findMethod(it, "setValueIndex", 1) }
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

        return importanceField != null &&
            backupImportanceField != null &&
            backendField != null &&
            pkgField != null &&
            uidField != null &&
            channelField != null &&
            findSpinnerIndexOfValue != null &&
            setValueIndex != null &&
            setListener != null &&
            updateChannelMethod != null &&
            lockFieldsMethod != null &&
            listenerInterface != null
    }

    /** Installs a Proxy[OnPreferenceChangeListener] that persists the selected importance. */
    private fun installImportanceListener(frag: Any, pref: Any) {
        val iface = listenerInterface ?: return
        val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, method, args ->
            when (method.name) {
                "onPreferenceChange" -> HookFailurePolicy.open(TAG, "onPreferenceChange", true) {
                    val value = (args?.getOrNull(1) as? String)?.toIntOrNull()
                        ?: return@open true
                    writeImportance(frag, value)
                    true
                }
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.getOrNull(0)
                "toString" -> "NotifMoreSettings@" +
                    Integer.toHexString(System.identityHashCode(p))
                else -> null
            }
        }
        runCatching { setListener?.invoke(pref, proxy) }
            .onFailure { DebugLog.w(TAG, "setOnPreferenceChangeListener failed", it) }
    }

    /** Mirrors the ROM's own dead `ChannelNotificationSettings$3` write-back path. */
    private fun writeImportance(frag: Any, importance: Int) {
        backupImportanceField?.setInt(frag, importance)
        val channel = channelField?.get(frag) as? NotificationChannel ?: return
        channel.setImportance(importance)
        lockFieldsMethod?.invoke(channel, USER_LOCKED_IMPORTANCE)
        val backend = backendField?.get(frag) ?: return
        val pkg = pkgField?.get(frag) as? String ?: return
        val uid = uidField?.getInt(frag) ?: return
        updateChannelMethod?.invoke(backend, pkg, uid, channel)
        findMethod(frag.javaClass, "updateDependents", 1)?.invoke(frag, false)
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
