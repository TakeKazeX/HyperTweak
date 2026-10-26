package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.base.HostAccess
import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Experimental: links the orphaned AON gesture pages (左右挥手 / 隔空暂停或播放) into the 隔空手势
 * landing page (`com.android.settings.aon.gesture.AonGestureSettings`).
 *
 * The stock ROM ships `AonLeftRightGestureSettings` / `AonDoublePressGestureSettings` fragments and
 * their XMLs, but no preference tree or activity references them, so the landing page only ever
 * offers 上下滑动屏幕. This hooker appends rows for both pages (same keys/strings the fragments
 * already manage: `miui_aon_left_right_waving` / `miui_aon_double_press`) whenever
 * [Preferences.unlockMoreAonGestures] is on. All `androidx.preference` types are manipulated
 * reflectively because this module does not compile against the preference library; the host
 * (Settings) process provides the classes at runtime.
 *
 * Gate: 解锁更多隔空手势. Rows appear on the next `onResume` of the 隔空手势 page.
 */
object AonGestureSettingsHooker : StaticHooker() {
    private const val TAG = "AonGestureExtras"
    private const val CLASS = "com.android.settings.aon.gesture.AonGestureSettings"

    private class ExtraRow(
        val key: String,
        val fragment: String,
        val titleResource: String,
        val secureKey: String
    )

    private val extraRows = listOf(
        ExtraRow(
            key = "key_left_right_waving",
            fragment = "com.android.settings.aon.gesture.AonLeftRightGestureSettings",
            titleResource = "aon_gesture_left_right_waving_title",
            secureKey = "miui_aon_left_right_waving"
        ),
        ExtraRow(
            key = "key_double_press",
            fragment = "com.android.settings.aon.gesture.AonDoublePressGestureSettings",
            titleResource = "aon_gesture_double_press_title",
            secureKey = "miui_aon_double_press"
        )
    )

    override fun onHook() {
        val clazz = CLASS.toClassOrNull() ?: return
        val onResume = clazz.declaredMethods.firstOrNull {
            it.name == "onResume" && it.parameterTypes.isEmpty()
        } ?: return
        deoptimize(onResume)
        onResume.hook("aon_gesture_extras_rows") { after { param ->
            if (!Preferences.unlockMoreAonGestures()) return@after
            val host = param.thisObject
            runCatching { ensureExtraRows(host) }
                .onFailure { t -> DebugLog.w(TAG, "failed to add extra gesture rows", t) }
        } }
        DebugLog.d(TAG, "extra AON gesture rows armed on ${clazz.name}#onResume")
    }

    private fun ensureExtraRows(host: Any) {
        val hostClass = host.javaClass
        val ctx = (HostAccess.requirePublicMethod(hostClass, "getContext", accessible = false).invoke(host) as? Context) ?: return
        val screen = HostAccess.requirePublicMethod(hostClass, "getPreferenceScreen", accessible = false).invoke(host) ?: return
        val screenClass = screen.javaClass
        // Class.classLoader is annotated @RecentlyNullable; a loaded screen class always has one.
        val loader = requireNotNull(screenClass.classLoader)
        val findPreference =
            HostAccess.requirePublicMethod(screenClass, "findPreference", CharSequence::class.java, accessible = false)
        val addPreference =
            HostAccess.requirePublicMethod(screenClass, "addPreference", loader.loadClass("androidx.preference.Preference"), accessible = false)
        // A Landing row 上下滑动屏幕 lives inside a (key-less) PreferenceCategory; add ours to the
        // first category if present so they render in the same group, otherwise to the screen root.
        val category = firstCategory(screenClass, loader, screen)

        for (row in extraRows) {
            val existing = findPreference.invoke(screen, row.key)
            if (existing != null) {
                setStatusSummary(existing, ctx, row)
                continue
            }
            val preference = loader.loadClass("androidx.preference.Preference")
                .getConstructor(Context::class.java)
                .newInstance(ctx)
            val prefClass = preference.javaClass
            HostAccess.requirePublicMethod(prefClass, "setKey", String::class.java, accessible = false).invoke(preference, row.key)
            HostAccess.requirePublicMethod(prefClass, "setFragment", String::class.java, accessible = false).invoke(preference, row.fragment)
            HostAccess.requirePublicMethod(prefClass, "setOrder", Int::class.javaPrimitiveType, accessible = false).invoke(preference, 200)
            val titleId = stringId(ctx, row.titleResource)
            if (titleId != 0) {
                HostAccess.requirePublicMethod(prefClass, "setTitle", Int::class.javaPrimitiveType, accessible = false).invoke(preference, titleId)
            }
            setStatusSummary(preference, ctx, row)
            (category ?: screen).let { addPreference.invoke(it, preference) }
        }
    }

    private fun firstCategory(screenClass: Class<*>, loader: ClassLoader, screen: Any): Any? {
        val categoryClass = runCatching { loader.loadClass("androidx.preference.PreferenceCategory") }.getOrNull()
            ?: return null
        val count = HostAccess.requirePublicMethod(screenClass, "getPreferenceCount", accessible = false).invoke(screen) as Int
        val getPreference = HostAccess.requirePublicMethod(screenClass, "getPreference", Int::class.javaPrimitiveType, accessible = false)
        for (i in 0 until count) {
            val child = getPreference.invoke(screen, i) ?: continue
            if (categoryClass.isInstance(child)) return child
        }
        return null
    }

    private fun setStatusSummary(preference: Any, ctx: Context, row: ExtraRow) {
        val open = android.provider.Settings.Secure.getInt(ctx.contentResolver, row.secureKey, 0) == 1
        val resName = if (open) "aon_gesture_status_open" else "aon_gesture_status_not_open"
        val id = stringId(ctx, resName)
        if (id != 0) {
            HostAccess.requirePublicMethod(preference.javaClass, "setSummary", Int::class.javaPrimitiveType, accessible = false)
                .invoke(preference, id)
        }
    }

    private fun stringId(ctx: Context, name: String): Int =
        ctx.resources.getIdentifier(name, "string", ctx.packageName)
}
