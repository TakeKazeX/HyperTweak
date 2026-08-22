package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Modifier

/**
 * 系统设置侧「急速相机」选项补全 (`Preferences.KEY_CAMERA_STREET_QUICK_LAUNCH` 在 Settings
 * 进程里的另一半)。
 *
 * 设置→锁屏→其他→急速相机 的控件形态由
 * `com.android.settings.helper.LockscreenOthersHelper.supportCameraStreetMode()`(静态,反射
 * miui-framework 的 `InputFeature.supportCameraStreetMode()` = `persist.vendor.camera.
 * IsVariableApertureSupported || IsStreetModeSupported`)决定:
 *  - true  → `initCameraSettings()` 删掉开关、留下「关闭 / 打开相机 / 打开相机并拍照」下拉,
 *    选中项写回 `Settings.System.volumekey_launch_camera` = 0/1/2;
 *  - false → 删掉下拉,只留「锁屏后快速双击音量下键打开相机」开关(写 0/1)。
 * 真机 myron 两台厂商属性都未置位,这里永远只显示开关,「打开相机并拍照」没有入口。
 *
 * 开关打开时,这个 hook 强制该静态方法返回 true,让下拉项出现——用户选中「打开相机并拍照」
 * 即写 2,配合相机侧的快速抢拍分类 hook(同开关,见 CameraImpersonationHooker
 * [hookStreetQuickLaunch])走完整街拍快速抢拍闭环。RAISE-ONLY(只升不降)、live-read
 * (100 ms memo);hook 在 Settings 进程 attach 时安装,重开锁屏设置页即可看到下拉。
 */
object FastCameraSettingsHooker : StaticHooker() {
    private const val TAG = "FastCamSettings"
    private const val HELPER_CLASS = "com.android.settings.helper.LockscreenOthersHelper"

    override fun onHook() {
        val clazz = HELPER_CLASS.toClassOrNull() ?: run {
            DebugLog.w(TAG, "$HELPER_CLASS not found; lockscreen fast-camera setting skipped")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "supportCameraStreetMode" &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.isEmpty() &&
                it.returnType == java.lang.Boolean.TYPE &&
                !it.isSynthetic
        } ?: run {
            DebugLog.w(TAG, "$HELPER_CLASS#supportCameraStreetMode() not found; skipped")
            return
        }
        deoptimize(method)
        method.hook("cam_setting_fast_camera_street_support") {
            after { param ->
                if (!Preferences.cameraStreetQuickLaunch()) return@after
                param.result = true
            }
        }
        DebugLog.i(
            TAG,
            "lockscreen fast-camera street-support forced true on ${clazz.name}#supportCameraStreetMode()"
        )
    }
}