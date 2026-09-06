package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field

/**
 * Experimental: lets the AON air-gesture path honor the 左右挥手 / 隔空暂停或播放 toggles that the
 * stock ROM never enables on AON devices.
 *
 * In `miui-services.jar` (system_server), `ContactlessGestureController` initialises all six gesture
 * feature bits from `android.miui` integer resources in its constructor, but the AON-only
 * `updateSupprotFeature()` reads just `miui_aon_up_down_waving` and zeroes left/right/double/circle
 * (up/down are re-derived from resources). So the AON engine is only ever told (via
 * `aonEventUpdate`, from `getCurrentSupportGesture`) to recognise up/down, and
 * `handleGestureEvent` never injects anything for labels 1/2/7 because
 * `getCurrentSupportFeature()` (= 0x1801) does not intersect the left/right feature values.
 *
 * This hooker re-enables the two features Xiaomi ships Settings UI for (left/right, double press):
 *  - `updateSupprotFeature()` after: when 解锁更多隔空手势 is on and the matching Secure key
 *    (`miui_aon_left_right_waving` / `miui_aon_double_press`) is enabled, restore that feature
 *    field from the same resource integer the constructor uses (0x110b002d/0x110b002e/0x110b002a).
 *  - `getCurrentSupportFeature()` after: OR the enabled feature values into the per-app mask so
 *    `handleGestureEvent` passes the mask check and the engine advertises the gestures.
 *
 * Both hooks only act on `AONGestureController` instances (not the TOF controller), only when the
 * module preference is on, and are guarded so a wrong/drifted build degrades to stock behaviour.
 * Feature/resource ids verified on OS4.0.0.25.XPMCNXM (framework-ext-res public.xml +
 * miui-services decompile). Applies from the next system_server start; whether the AON camera
 * engine actually emits left/right/double labels is hardware-dependent — see docs/FEATURE_DETAIL.md.
 */
object AonGestureFeatureHooker : StaticHooker() {
    private const val TAG = "AonGestureFeature"
    private const val CONTROLLER_CLASS = "com.android.server.tof.ContactlessGestureController"
    private const val AON_CONTROLLER_NAME = "com.android.server.tof.AONGestureController"

    /** config_tof_gesture_feature_left = 0x1400c (0x110b002d). */
    private const val RES_ID_LEFT = 285933613
    /** config_tof_gesture_feature_right = 0x22012 (0x110b002e). */
    private const val RES_ID_RIGHT = 285933614
    /** config_tof_gesture_feature_double_press = 0x8001 (0x110b002a). */
    private const val RES_ID_DOUBLE_PRESS = 285933610

    private const val SECURE_LEFT_RIGHT = "miui_aon_left_right_waving"
    private const val SECURE_DOUBLE_PRESS = "miui_aon_double_press"

    override fun onHook() {
        val clazz = CONTROLLER_CLASS.toClassOrNull() ?: return
        val update = clazz.declaredMethods.firstOrNull {
            it.name == "updateSupprotFeature" && it.parameterTypes.isEmpty()
        }
        val current = clazz.declaredMethods.firstOrNull {
            it.name == "getCurrentSupportFeature" && it.parameterTypes.isEmpty()
        }
        if (update == null || current == null) {
            DebugLog.w(TAG, "AON gesture controller methods not found; skipped")
            return
        }
        deoptimize(update)
        deoptimize(current)
        update.hook("aon_gesture_support_features") { after { param ->
            val host = param.thisObject
            if (host == null || !isAonController(host) || !Preferences.unlockMoreAonGestures()) {
                return@after
            }
            runCatching { restoreEnabledFeatures(host) }
                .onFailure { t -> DebugLog.w(TAG, "failed to restore AON gesture features", t) }
        } }
        current.hook("aon_gesture_app_mask") { after { param ->
            val host = param.thisObject
            if (host == null || !isAonController(host) || !Preferences.unlockMoreAonGestures()) {
                return@after
            }
            runCatching {
                val base = param.result as? Int ?: return@after
                param.result = base or
                    fieldInt(host, "mTofGestureLeftSupportFeature") or
                    fieldInt(host, "mTofGestureRightSupportFeature") or
                    fieldInt(host, "mTofGestureDoublePressSupportFeature")
            }.onFailure { t -> DebugLog.w(TAG, "failed to widen AON gesture mask", t) }
        } }
        DebugLog.i(TAG, "AON left/right + double-press gesture features armed")
    }

    private fun isAonController(host: Any): Boolean =
        host.javaClass.name == AON_CONTROLLER_NAME

    private fun restoreEnabledFeatures(host: Any) {
        val ctx = fieldValue(host, "mContext") as? Context ?: return
        val resolver = ctx.contentResolver
        val resources = ctx.resources
        restoreFeature(host, "mTofGestureLeftSupportFeature", RES_ID_LEFT, SECURE_LEFT_RIGHT, resolver, resources)
        restoreFeature(host, "mTofGestureRightSupportFeature", RES_ID_RIGHT, SECURE_LEFT_RIGHT, resolver, resources)
        restoreFeature(host, "mTofGestureDoublePressSupportFeature", RES_ID_DOUBLE_PRESS, SECURE_DOUBLE_PRESS, resolver, resources)
    }

    private fun restoreFeature(
        host: Any,
        fieldName: String,
        resId: Int,
        secureKey: String,
        resolver: android.content.ContentResolver,
        resources: android.content.res.Resources
    ) {
        val enabled = android.provider.Settings.Secure.getInt(resolver, secureKey, 0) == 1
        if (!enabled) return // ROM's zeroing stays; nothing to restore
        val featureValue = runCatching { resources.getInteger(resId) }.getOrDefault(0)
        if (featureValue == 0) return // resource missing on a drifted build → keep stock
        findField(host, fieldName)?.setInt(host, featureValue)
    }

    private fun fieldValue(host: Any, name: String): Any? =
        findField(host, name)?.get(host)

    private fun fieldInt(host: Any, name: String): Int =
        findField(host, name)?.getInt(host) ?: 0

    private fun findField(host: Any, name: String): Field? {
        var clazz: Class<*>? = host.javaClass
        while (clazz != null) {
            try {
                val f: Field = clazz.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        return null
    }
}
