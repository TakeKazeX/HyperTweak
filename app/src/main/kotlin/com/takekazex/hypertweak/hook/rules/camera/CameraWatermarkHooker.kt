package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.io.File

/**
 * Unlocks watermark categories in the Xiaomi camera app (`com.android.camera`,
 * MiuiCamera). Verified against 6.6.000460.0 on OS4.0.0.15.XPMCNXM (REDMI K90 Pro Max,
 * `myron`); reverse-engineering notes live in the reverse workspace at
 * `cache/camera-5cd70925b1646cdf/`.
 *
 * The camera keeps its watermark resources under `files/watermarks/` (downloaded by its
 * cloud sync — the sync itself receives the full set, including festival editions such as
 * `2026_parents_day`). The gallery screen (`WmGalleryFragment` → `WmGalleryPreference`)
 * reads the scanned groups through `Gg.P` (the `WmBaseManager`), whose `d(boolean)`
 * (`filterData`) applies a filter chain — id whitelist, validity time window,
 * device-type whitelist, system-properties match, theme, region, name length — that hides
 * entries whose `config.json` `limitation` does not match this device. On this baseline the
 * Leica set (ids 88..94, 111) all require `"ro.boot.product.theme_customize": "lcc"`, so a
 * non-LCC device like myron gets **no** Leica watermarks at all.
 *
 * The whole chain is wrapped in `if (!C1686u.f6071a.getValue()) { ... }`, where
 * `C1686u$b.invoke()` reads the system property `camera.cloud.watermark.debug` — the same
 * debug gate the media editor uses (`tb0.v$b` there). Hooking that read to true while
 * [Preferences.KEY_WM_CAMERA] is on skips the entire filter chain, so every synced
 * watermark (Leica, festival, sports, ...) becomes selectable in the gallery.
 *
 * The property read runs per watermark-group scan (menu open), so the switch takes effect
 * the next time the watermark gallery is opened without restarting the camera.
 */
object CameraWatermarkHooker : StaticHooker() {
    private const val TAG = "CamWmUnlock"
    private const val PACKAGE = "com.android.camera"

    /** Obfuscated property-reader class of the verified baseline. */
    private const val DEBUG_FLAG_CLASS = "Gg.C1686u\$b"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        installHooks()
    }

    private fun installHooks() {
        val info = hookParam.appInfo ?: return
        val clazz = DEBUG_FLAG_CLASS.toClassOrNull() ?: run {
            // Fallback: resolve by the property string signature (R8 names can change).
            val baseDir = info.deviceProtectedDataDir ?: info.dataDir ?: return
            val apkPath = info.sourceDir ?: return
            DexKitManager.resolveClasses(
                cacheDir = File(baseDir, "cache"),
                apkPath = apkPath,
                classLoader = classLoader,
                queries = mapOf(
                    "debugFlag" to { bridge ->
                        bridge.findClass { matcher { usingStrings("camera.cloud.watermark.debug") } }
                            .firstOrNull { it.name.endsWith("\$b") }?.name
                    }
                )
            )["debugFlag"] ?: run {
                DebugLog.e(TAG, "watermark debug flag class not resolved")
                return
            }
        }

        val invoke = runCatching {
            clazz.declaredMethods.first { it.parameterTypes.isEmpty() }.apply { isAccessible = true }
        }.getOrNull() ?: run {
            DebugLog.e(TAG, "invoke() not found on ${clazz.name}")
            return
        }
        deoptimize(invoke)
        invoke.hook("wm_debug_flag") {
            after { param ->
                if (Preferences.getBoolean(Preferences.KEY_WM_CAMERA, false)) {
                    param.result = true
                }
            }
        }
        DebugLog.d(TAG, "camera watermark debug flag hooked on ${clazz.name}")

        hookDeviceLogo()
    }

    /**
     * Repairs the classic-watermark brand logo on devices whose capability config leaves it
     * empty. The watermark templates reference `${logo}` (resolved to `xiaomi_black.webp` /
     * `redmi_black.webp` / `poco_black.webp` per device), and the value comes from
     * `Je.c.x()` = `v()[0]`. On this baseline `myron` (REDMI K90 Pro Max) falls through the
     * `v()` brand-array switch to an empty array (only the `ALSC` market-name variant gets
     * `{"REDMI","Turbo 5"}`), so classic watermarks render with no logo at all — neither
     * brand nor Leica (classic watermarks never carry the Leica mark; `leica.webp` only
     * exists in the Leica category). With the camera switch on, a null/empty logo is
     * replaced by the brand of the device family.
     */
    private fun hookDeviceLogo() {
        val clazz = "Je.c".toClassOrNull() ?: run {
            DebugLog.w(TAG, "Je.c device config not resolved; logo repair skipped")
            return
        }
        val xMethod = runCatching {
            clazz.declaredMethods.first {
                it.name == "x" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }.apply { isAccessible = true }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "Je.c#x() not found; logo repair skipped")
            return
        }
        deoptimize(xMethod)
        xMethod.hook("wm_device_logo") {
            after { param ->
                if (!Preferences.getBoolean(Preferences.KEY_WM_CAMERA, false)) return@after
                val logo = param.result as? String
                if (logo.isNullOrEmpty()) {
                    // Custom brand honoured here too (shared resolution with the impersonation
                    // keep-model hooks); falls back to the built-in device-family brand.
                    param.result = CameraWatermarkBrand.brand()
                    DebugLog.d(TAG, "logo was empty; using ${CameraWatermarkBrand.brand()}")
                }
            }
        }
        DebugLog.d(TAG, "device logo repair hooked on ${clazz.name}#x()")
    }
}
