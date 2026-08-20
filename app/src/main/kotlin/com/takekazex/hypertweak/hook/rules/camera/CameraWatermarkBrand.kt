package com.takekazex.hypertweak.hook.rules.camera

import android.os.Build
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.DebugLog

/**
 * Shared watermark brand/model resolution used by both `CameraImpersonationHooker` (keep-model
 * hooks) and `CameraWatermarkHooker.hookDeviceLogo` so a custom brand/model is honoured in both
 * places with one implementation.
 *
 * The brand is the classic-watermark logo string (XIAOMI / REDMI / POCO); the model is the
 * on-picture model text which by default mirrors the EXIF `Model` tag
 * (`ro.product.marketname`). A user-supplied custom brand/model wins over both.
 */
object CameraWatermarkBrand {

    /**
     * Custom brand override; only honoured when the custom-watermark master switch
     * (`KEY_CAMERA_WM_CUSTOM`) is on and the value is non-blank.
     */
    fun customBrand(): String =
        if (Preferences.getBoolean(Preferences.KEY_CAMERA_WM_CUSTOM, false)) {
            Preferences.getString(Preferences.KEY_CAMERA_WM_CUSTOM_BRAND, "").trim()
        } else {
            ""
        }

    /** Custom model override; only honoured when `KEY_CAMERA_WM_CUSTOM` is on. */
    fun customModel(): String =
        if (Preferences.getBoolean(Preferences.KEY_CAMERA_WM_CUSTOM, false)) {
            Preferences.getString(Preferences.KEY_CAMERA_WM_CUSTOM_MODEL, "").trim()
        } else {
            ""
        }

    /**
     * The classic-watermark brand logo. A custom brand is passed through uppercased (the same
     * normalization the stock XIAOMI / REDMI / POCO logos use); otherwise the device's
     * `Build.BRAND` is normalized the same way.
     */
    fun brand(): String {
        customBrand().takeIf { it.isNotEmpty() }?.let { return it.uppercase() }
        val brand = Build.BRAND
        return when (brand?.lowercase()) {
            "xiaomi", "redmi", "poco" -> brand.uppercase()
            else -> brand?.uppercase() ?: "XIAOMI"
        }
    }

    /**
     * The on-picture watermark model text. A custom model is used verbatim; otherwise resolve a
     * real marketing name by reading `ro.product.marketname` (the EXIF `Model` source, the same
     * property `Je/d.f8434h` is initialised from), falling back to `Build.MODEL`. Defensive and
     * never throws.
     */
    fun model(): String {
        customModel().takeIf { it.isNotEmpty() }?.let { return it }
        return readMarketName() ?: Build.MODEL
    }

    /**
     * Read `ro.product.marketname` the same way the camera's property reader does
     * (`android.os.SystemProperties.get("ro.product.marketname", Build.MODEL)`). Returns null only
     * if the read produces a blank value (caller falls back to `Build.MODEL`).
     */
    private fun readMarketName(): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, "ro.product.marketname", Build.MODEL) as? String)
            ?.takeIf { it.isNotBlank() }
    }.onFailure { t ->
        DebugLog.w("CamWmBrand", "ro.product.marketname read failed", t)
    }.getOrNull()
}
