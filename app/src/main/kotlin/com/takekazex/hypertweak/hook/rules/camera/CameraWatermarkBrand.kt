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
 * (`ro.product.marketname`). A user-supplied custom brand/model wins over both, gated on the
 * custom-watermark master switch `KEY_CAMERA_WM_CUSTOM`.
 *
 * The resolved values are cached and only recomputed when the mutable inputs (custom master /
 * custom brand / custom model / `Build.BRAND`) change, so the hot watermark keep hooks never pay
 * the reflective `ro.product.marketname` read per call.
 */
object CameraWatermarkBrand {

    @Volatile private var cachedSig: String? = null
    @Volatile private var cachedBrand: String = ""
    @Volatile private var cachedModel: String = ""

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
        refreshIfChanged()
        return cachedBrand
    }

    /**
     * The on-picture watermark model text. A custom model is used verbatim; otherwise resolve a
     * real marketing name by reading `ro.product.marketname` (the EXIF `Model` source, the same
     * property `Je/d.f8434h` is initialised from), falling back to `Build.MODEL`. Defensive and
     * never throws.
     *
     * The leading brand token is stripped ("REDMI K90 Pro Max" → "K90 Pro Max"): on-picture the
     * classic watermark shows the brand as a separate logo (`@{logo}` / the red REDMI wordmark),
     * so the model line must not repeat it — feeding the full market name through
     * `hookWatermarkRender`/the `S8.d` cache made `fs/m.o` render "REDMI REDMI K90 Pro Max"
     * (one red logo "REDMI" + black "REDMI K90 Pro Max"). This matches the app's own
     * `Ku.b.a()` model and the stock `Je.c.v()[1]`. EXIF `Model` is unaffected (it reads
     * `ro.product.marketname` directly via `Je/d.f8434h`, never this value). A custom model is
     * never touched.
     */
    fun model(): String {
        refreshIfChanged()
        return cachedModel
    }

    /** Signature of the mutable inputs; when it changes the resolved brand/model are recomputed. */
    private fun signatureOfInputs(): String =
        "${customBrand()}|${customModel()}|${Build.BRAND}"

    private fun refreshIfChanged() {
        val sig = signatureOfInputs()
        if (sig == cachedSig) return
        cachedBrand = resolveBrand()
        cachedModel = resolveModel()
        cachedSig = sig
    }

    private fun resolveBrand(): String {
        customBrand().takeIf { it.isNotEmpty() }?.let { return it.uppercase() }
        val brand = Build.BRAND
        return when (brand?.lowercase()) {
            "xiaomi", "redmi", "poco" -> brand.uppercase()
            else -> brand?.uppercase() ?: "XIAOMI"
        }
    }

    private fun resolveModel(): String {
        customModel().takeIf { it.isNotEmpty() }?.let { return it }
        return stripLeadingBrand(readMarketName() ?: Build.MODEL)
    }

    /**
     * Drop a leading XIAOMI/REDMI/POCO token (+ space) from a device model string. Only strips
     * when the first word is one of the bundled logo brands (so `Build.MODEL` fallbacks like
     * "2407FPN8DG" or "MIX Fold 4" are preserved verbatim); anything else is returned untouched.
     */
    private fun stripLeadingBrand(model: String): String {
        val trimmed = model.trim()
        val space = trimmed.indexOf(' ')
        if (space > 0) {
            val first = trimmed.substring(0, space)
            if (first.equals("xiaomi", ignoreCase = true) ||
                first.equals("redmi", ignoreCase = true) ||
                first.equals("poco", ignoreCase = true)
            ) {
                return trimmed.substring(space + 1).trim()
            }
        }
        return trimmed
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
