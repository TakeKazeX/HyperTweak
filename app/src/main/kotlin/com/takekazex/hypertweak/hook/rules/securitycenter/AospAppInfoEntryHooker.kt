package com.takekazex.hypertweak.hook.rules.securitycenter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.lingqiqi5211.ezhooktool.core.callMethodOrNull
import java.util.Locale

/**
 * Adds an "App info" entry to Security Center's app details page, opening Settings' AOSP app info
 * screen that HyperOS otherwise gives no route to.
 *
 * The target is Settings' SPA route `AppInfoSettings/{package}/{user}`, served by
 * `com.android.settings.spa.app.appinfo.AppInfoSettingsProvider`.
 *
 * Every lookup is null-checked so the hooker fails silently if Security Center's shape drifts from
 * the recorded baseline (`cache/securitycenter-2627ffd76e9d8f79`).
 */
object AospAppInfoEntryHooker : StaticHooker() {
    private const val TAG = "AospAppInfoEntry"

    private const val DETAILS_FRAGMENT = "com.miui.appmanager.fragment.ApplicationsDetailsFragment"

    /** Preference key of the entry the new one is inserted after. */
    private const val KEY_OPEN_BY_DEFAULT = "app_default_pref"
    private const val KEY_AOSP_APP_INFO = "hypertweak_aosp_app_info"

    /**
     * Title of the injected "App info (AOSP)" row. It renders inside Security Center, so it follows
     * the device (system) locale rather than the module's app-language preference.
     */
    private fun appInfoTitle(): String =
        if (Locale.getDefault().language == "zh") "应用信息 (AOSP)" else "App info (AOSP)"

    /** Security Center styles its preferences with miuix; androidx is the portable fallback. */
    private val PREFERENCE_CLASSES = listOf(
        "miuix.preference.TextPreference",
        "androidx.preference.Preference"
    )

    override val hotReloadMode = HotReloadMode.RECREATE

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_AOSP_APP_INFO_ENTRY, false)) {
            DebugLog.hookSkipped(TAG, "app details page", "disabled")
            return
        }

        val fragment = DETAILS_FRAGMENT.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, DETAILS_FRAGMENT, "class not found")
            return
        }

        val method = CompatibleMethodResolver.find(
            fragment,
            "onCreatePreferences",
            parameterTypes = listOf(Bundle::class.java, String::class.java)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$DETAILS_FRAGMENT#onCreatePreferences(Bundle,String)",
                "method not found"
            )
            return
        }

        runCatching {
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "onCreatePreferences", Unit) {
                        addAppInfoPreference(param.thisObject)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$DETAILS_FRAGMENT#onCreatePreferences(Bundle,String)", it)
        }
    }

    private fun addAppInfoPreference(fragment: Any?) {
        if (fragment == null) return
        if (fragment.callMethodOrNull("findPreference", KEY_AOSP_APP_INFO) != null) return

        val anchor = fragment.callMethodOrNull("findPreference", KEY_OPEN_BY_DEFAULT) ?: return
        val parent = anchor.callMethodOrNull("getParent") ?: return
        val activity = fragment.callMethodOrNull("requireActivity") as? Activity ?: return
        val order = (anchor.callMethodOrNull("getOrder") as? Int ?: return) + 1
        val intent = createAppInfoIntent(activity) ?: return

        shiftPreferencesAfter(parent, order)

        val preference = newPreference(activity) ?: return
        preference.callMethodOrNull("setKey", KEY_AOSP_APP_INFO)
        preference.callMethodOrNull("setTitle", appInfoTitle())
        preference.callMethodOrNull("setVisible", true)
        preference.callMethodOrNull("setPersistent", false)
        preference.callMethodOrNull("setOrder", order)
        // Not setIntent(): androidx Preference.performClick() would call startActivity(intent) with
        // no catch, so a missing SPA route crashes Security Center. Launch from a guarded click
        // listener instead, matching AospAppManagerEntryHooker.
        setSafeClickListener(preference, activity, intent)
        parent.callMethodOrNull("addPreference", preference)
    }

    /**
     * Installs an `OnPreferenceClickListener` that launches [intent] guarded by runCatching. The
     * listener interface lives only in Security Center's class loader, so it is implemented through a
     * [java.lang.reflect.Proxy] built from the `setOnPreferenceClickListener` parameter type.
     */
    private fun setSafeClickListener(preference: Any, activity: Activity, intent: Intent) {
        val setListener = preference.javaClass.methods.firstOrNull {
            it.name == "setOnPreferenceClickListener" && it.parameterTypes.size == 1
        } ?: return
        val listenerType = setListener.parameterTypes[0]
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            listenerType.classLoader,
            arrayOf(listenerType)
        ) { proxy, method, args ->
            when (method.name) {
                "onPreferenceClick" -> {
                    runCatching { activity.startActivity(intent) }
                        .onFailure { DebugLog.w(TAG, "failed to open AOSP app info", it) }
                    true
                }
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "AospAppInfoClickListener"
                else -> null
            }
        }
        runCatching { setListener.invoke(preference, listener) }
    }

    /** Makes room at [order] so the new entry lands directly after its anchor. */
    private fun shiftPreferencesAfter(parent: Any, order: Int) {
        val count = parent.callMethodOrNull("getPreferenceCount") as? Int ?: return
        for (index in 0 until count) {
            val preference = parent.callMethodOrNull("getPreference", index) ?: continue
            val current = preference.callMethodOrNull("getOrder") as? Int ?: continue
            // Preference.DEFAULT_ORDER (Int.MAX_VALUE) means "append last"; + 1 would overflow to
            // Int.MIN_VALUE and jump the row to the very top. Leave those where they are.
            if (current in order until Int.MAX_VALUE) preference.callMethodOrNull("setOrder", current + 1)
        }
    }

    private fun newPreference(activity: Activity): Any? =
        PREFERENCE_CLASSES.firstNotNullOfOrNull { name ->
            runCatching {
                name.toClassOrNull()
                    ?.getConstructor(Context::class.java)
                    ?.newInstance(activity)
            }.getOrNull()
        }

    private fun createAppInfoIntent(activity: Activity): Intent? {
        val packageName = activity.intent?.getStringExtra("package_name") ?: return null
        // UserHandle.myUserId() is @hide. `miui.intent.extra.USER_ID` is already a userId (0/10/999) —
        // the same value the fragment itself reads — not a uid, so it must NOT be run through
        // getUserId(int), which would collapse XSpace's 999 and second-space's 10 back to user 0.
        val myUserId = runCatching {
            UserHandle::class.java.getMethod("myUserId").invoke(null) as? Int
        }.getOrNull() ?: 0
        val userId = activity.intent.getIntExtra("miui.intent.extra.USER_ID", myUserId)

        return Intent(Intent.ACTION_MAIN).apply {
            setClassName("com.android.settings", "com.android.settings.spa.SpaActivity")
            putExtra("spaActivityDestination", "AppInfoSettings/$packageName/$userId")
            putExtra("sessionSource", "browse")
        }
    }
}
