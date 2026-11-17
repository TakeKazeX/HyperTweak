package com.takekazex.hypertweak.hook.rules.systemui.island.flashlight

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.UserHandle
import android.provider.Settings

/**
 * Drives the torch through the same SystemUI channel as the native full-screen flashlight page,
 * so the tile, focus notification, thermal and low-battery paths all keep their own state:
 * `Settings.Global mi_flashlight_strength` plus `ACTION_OPERATE_FLASHLIGHT`, which the plugin's
 * `MiFlashlightOnSystemUiReceiver` and `MiuiFlashlightControllerImpl$6` handle in SystemUI.
 * Runs inside SystemUI, which holds INTERACT_ACROSS_USERS.
 */
@SuppressLint("MissingPermission")
internal class FlashlightTorch private constructor(
    private val context: Context,
    val cameraManager: CameraManager,
    val cameraId: String,
    val maxLevel: Int,
) {
    fun storedLogic(): Float = FlashlightLevels.clamp(
        runCatching { Settings.Global.getFloat(context.contentResolver, KEY_STRENGTH, 1f) }.getOrDefault(1f), maxLevel)

    fun apply(on: Boolean, logic: Float) {
        val level = FlashlightLevels.clamp(logic, maxLevel)
        if (on) runCatching { Settings.Global.putFloat(context.contentResolver, KEY_STRENGTH, level) }
        val intent = Intent(ACTION_OPERATE).setPackage(SYSTEMUI)
            .putExtra("miui_flashlight_camera_id", cameraId)
            .putExtra("miui_flashlight_strength", if (on) FlashlightLevels.strength(level, maxLevel) else 0)
            .putExtra("miui_flashlight_is_can_use_strength_level", maxLevel >= 100)
        context.sendBroadcastAsUser(intent, CURRENT_USER)
    }

    /** The close `MiuiFlashlightControllerImpl.handleFocusNotificationOrStatus(false, 0)` sends. */
    fun closeIsland() {
        val intent = Intent(ACTION_TOGGLE).setPackage(FlashlightNativeAssets.PLUGIN_PACKAGE)
            .putExtra("flashlight_is_open_or_close", false)
            .putExtra("notification_operation", FlashlightIslandHold.OP_CANCEL)
        context.sendBroadcastAsUser(intent, CURRENT_USER)
    }

    companion object {
        private const val SYSTEMUI = "com.android.systemui"
        /** Hidden `UserHandle.CURRENT`, the target the host controller sends to; SystemUI may read it. */
        private val CURRENT_USER: UserHandle by lazy {
            runCatching { UserHandle::class.java.getField("CURRENT").get(null) as UserHandle }
                .getOrElse { android.os.Process.myUserHandle() }
        }
        private const val KEY_STRENGTH = "mi_flashlight_strength"
        private const val ACTION_OPERATE = "miui.systemui.action.ACTION_OPERATE_FLASHLIGHT"
        private const val ACTION_TOGGLE = "miui.systemui.action.ACTION_TOGGLE_FLASHLIGHT"

        fun resolve(context: Context): FlashlightTorch {
            val manager = requireNotNull(context.getSystemService(CameraManager::class.java)) { "no camera service" }
            val id = manager.cameraIdList.firstOrNull { id ->
                val c = manager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: error("no back camera with a flash unit")
            val max = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            return FlashlightTorch(context, manager, id, max.coerceAtLeast(1))
        }
    }
}
