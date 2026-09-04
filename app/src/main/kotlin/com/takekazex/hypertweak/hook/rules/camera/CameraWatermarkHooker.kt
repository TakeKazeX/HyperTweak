package com.takekazex.hypertweak.hook.rules.camera

import android.content.Context
import android.content.res.AssetManager
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Path

/**
 * Unlocks watermark categories in the Xiaomi camera app (`com.android.camera`,
 * MiuiCamera). First verified against 6.6.000460.0 on OS4.0.0.15.XPMCNXM and re-verified
 * (via DexKit string fallback) against 6.6.000510.0 on OS4.0.0.19.XPMCNXM; reverse-engineering
 * notes live in the reverse workspace at `cache/camera-5cd70925b1646cdf/` and
 * `cache/camera-8f41d7b82453cdeb/`.
 *
 * The camera keeps its watermark resources under `files/watermarks/` (downloaded by its
 * cloud sync — the sync itself receives the full set, including festival editions such as
 * `2026_parents_day`). The gallery screen (`WmGalleryFragment` → `WmGalleryPreference`)
 * reads the scanned groups through `Gg.P` (the `WmBaseManager`), whose `d(boolean)`
 * (`filterData`) applies a filter chain — id whitelist, validity time window,
 * device-type whitelist, system-properties match, theme, region, name length — that hides
 * entries whose `config.json` `limitation` does not match this device. On the verified
 * baselines the downloaded Leica set (ids 88..94, 111) requires Leica/LCC capability, while
 * the APK also contains ordinary Leica templates 1..5 and 104 that are not downloaded to a
 * non-Leica device like myron. The latter must be hydrated locally before the scan; filtering
 * alone cannot expose files that are absent from `files/watermarks/leica/`.
 *
 * The whole chain is wrapped in `if (!C1686u.f6071a.getValue()) { ... }`, where
 * `C1686u$b.invoke()` reads the system property `camera.cloud.watermark.debug` — the same
 * debug gate the media editor uses (`tb0.v$b` there). Hooking that read to true while
 * [Preferences.KEY_WM_CAMERA] is on skips the filter chain; the final `Gg.P#d(boolean)`
 * funnel is also bypassed to cover a cached lazy value. Bundled ordinary Leica files are
 * copied by the scan hook before this filter stage.
 *
 * The property read runs per watermark-group scan (menu open), so the switch takes effect
 * the next time the watermark gallery is opened without restarting the camera.
 */
object CameraWatermarkHooker : StaticHooker() {
    private const val TAG = "CamWmUnlock"
    private const val PACKAGE = "com.android.camera"

    /**
     * Class-name candidates for the watermark debug-gate holder, newest builds first. The
     * holder is the `$b` inner class of a `Gg` lazy property that reads
     * `camera.cloud.watermark.debug`:
     *  - 6.6.000510.0 (OS4.0.0.19): `Gg.u$b`
     *  - 6.6.000460.0 (OS4.0.0.15): `Gg.C1686u$b`
     * `camera.cloud.watermark.debug` stays a plaintext dex string across builds, so the
     * DexKit probe in [installHooks] is the durable layer.
     */
    private val DEBUG_FLAG_CANDIDATES = listOf("Gg.u\$b", "Gg.C1686u\$b")

    /** Watermark group manager (the 6.6.000550.0 build still exposes `Gg.P`). */
    private val FILTER_MANAGER_CANDIDATES = listOf("Gg.P")
    private val FILTER_DATA_ANCHORS = listOf("filterData: E", "filterData: delete")

    /** Device-config facade candidates (540 renamed `Je.c` -> `Je.b`). */
    private val DEVICE_FACADE_CANDIDATES = listOf("Je.b", "Je.c")

    private const val LEICA_ASSET_ROOT = "watermarks/leica"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        installHooks()
    }

    private fun installHooks() {
        // appInfo is only needed by the DexKit probe branch; L1 candidate names must keep
        // working when it is unavailable (CameraResolver skips the probe on its own).
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)

        // Keep these independent: a renamed debug lazy must not prevent the final filter
        // funnel, bundled Leica-resource hydration, or the classic-logo repair from loading.
        hookDebugFlag(ctx)
        hookFilterData(ctx)
        hookLeicaResourceHydration(ctx)
        hookDeviceLogo(ctx)
    }

    private fun hookDebugFlag(ctx: CameraResolver.Ctx) {
        // L1 known names, L2 DexKit probe by the surviving plaintext property string.
        val clazz = CameraResolver.resolveClass(
            scope = TAG,
            key = "wm_debug_flag",
            ctx = ctx,
            candidates = DEBUG_FLAG_CANDIDATES,
            probe = { bridge ->
                bridge.findClass { matcher { usingStrings("camera.cloud.watermark.debug") } }
                    .firstOrNull { it.name.endsWith("\$b") }?.name
            },
            validate = { c -> c.declaredMethods.any { it.name == "invoke" && it.parameterTypes.isEmpty() } },
        ) ?: run {
            DebugLog.e(TAG, "watermark debug flag class not resolved (L1 candidates + DexKit probe exhausted)")
            return
        }

        val invoke = CameraResolver.resolveMethod(
            scope = TAG, key = "wm_debug_flag_invoke", clazz = clazz,
            names = listOf("invoke"),
            shape = { it.parameterTypes.isEmpty() },
        ) ?: run {
            DebugLog.e(TAG, "invoke() not found on ${clazz.name}")
            return
        }
        deoptimize(invoke)
        invoke.hook("wm_debug_flag") {
            after { param ->
                runCatching {
                    if (Preferences.getBoolean(Preferences.KEY_WM_CAMERA, false)) {
                        param.result = true
                    }
                }.onFailure { t ->
                    DebugLog.w(TAG, "watermark debug flag callback failed", t)
                }
            }
        }
        DebugLog.d(TAG, "camera watermark debug flag hooked on ${clazz.name}")
    }

    /**
     * Skip the final `Gg.P#d(boolean)` filter funnel while the switch is enabled. This is
     * required in addition to the debug lazy hook: the lazy may already have cached `false`
     * before our hook runs, while this method is the last point that removes the Leica folders
     * from the in-memory group list. The boolean argument only controls deletion, so changing it
     * to false would still leave the limitation filters active; bypass the method entirely.
     */
    private fun hookFilterData(ctx: CameraResolver.Ctx) {
        val clazz = CameraResolver.resolveClass(
            scope = TAG,
            key = "wm_filter_manager",
            ctx = ctx,
            candidates = FILTER_MANAGER_CANDIDATES,
            probe = { bridge ->
                FILTER_DATA_ANCHORS.asSequence()
                    .mapNotNull { anchor ->
                        bridge.findClass { matcher { usingStrings(anchor) } }
                            .firstOrNull { descriptor ->
                                ctx.loadOrNull(descriptor.name)?.let(::isFilterManager) == true
                            }
                            ?.name
                    }
                    .firstOrNull()
            },
            validate = ::isFilterManager,
        ) ?: run {
            DebugLog.w(TAG, "watermark filter manager not resolved; Leica unlock skipped")
            return
        }
        val method = uniqueFilterDataMethod(clazz) ?: run {
            DebugLog.w(TAG, "${clazz.name}#d(boolean) is not unique; Leica unlock skipped")
            return
        }
        deoptimize(method)
        method.hook("wm_filter_data") {
            before { param ->
                runCatching {
                    if (Preferences.getBoolean(Preferences.KEY_WM_CAMERA, false)) {
                        // For a Unit method, null is the short-circuit result used by EzHookTool.
                        param.result = null
                    }
                }.onFailure { t ->
                    DebugLog.w(TAG, "watermark filter callback failed", t)
                }
            }
        }
        DebugLog.d(TAG, "camera watermark filter bypass hooked on ${clazz.name}#${method.name}(boolean)")
    }

    private fun isFilterManager(clazz: Class<*>): Boolean = uniqueFilterDataMethod(clazz) != null

    /**
     * The Leica group is not only the 88..94/111 cloud set. This camera APK also ships the
     * ordinary Leica templates 1..5 and 104 under its own `watermarks/leica` assets, but the
     * cloud-supported list on non-Leica devices does not download them. The debug filter hook
     * cannot expose a folder which is absent from `files/watermarks/`, so hydrate missing files
     * from the camera APK before `Gg.P#m()` scans the groups. Existing downloaded files are never
     * overwritten, preserving user state and newer cloud resources.
     */
    private fun hookLeicaResourceHydration(ctx: CameraResolver.Ctx) {
        val clazz = CameraResolver.resolveClass(
            scope = TAG,
            key = "wm_resource_manager",
            ctx = ctx,
            candidates = FILTER_MANAGER_CANDIDATES,
            validate = ::isFilterManagerClass,
        ) ?: run {
            DebugLog.w(TAG, "watermark resource manager not resolved; bundled Leica hydration skipped")
            return
        }
        val scan = CameraResolver.resolveMethod(
            scope = TAG,
            key = "wm_resource_scan",
            clazz = clazz,
            names = listOf("m"),
            shape = { it.parameterTypes.isEmpty() && it.returnType == Void.TYPE },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#m() not found; bundled Leica hydration skipped")
            return
        }
        val workingPath = CameraResolver.resolveMethod(
            scope = TAG,
            key = "wm_resource_working_path",
            clazz = clazz,
            names = listOf("k"),
            shape = { it.parameterTypes.isEmpty() && Path::class.java.isAssignableFrom(it.returnType) },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#k() not found; bundled Leica hydration skipped")
            return
        }
        deoptimize(scan)
        scan.hook("wm_leica_resource_hydration") {
            before { param ->
                runCatching {
                    if (!Preferences.getBoolean(Preferences.KEY_WM_CAMERA, false)) return@runCatching
                    val context = cameraContext(ctx.classLoader) ?: return@runCatching
                    val root = workingPath.invoke(param.thisObject) as? Path ?: return@runCatching
                    val copied = copyMissingLeicaAssets(context, root.toFile())
                    if (copied > 0) {
                        DebugLog.d(TAG, "hydrated $copied missing Leica resource files into $root")
                    }
                }.onFailure { t ->
                    DebugLog.w(TAG, "bundled Leica resource hydration failed", t)
                }
            }
        }
        DebugLog.d(TAG, "bundled Leica resource hydration hooked on ${clazz.name}#${scan.name}()")
    }

    private fun isFilterManagerClass(clazz: Class<*>): Boolean =
        uniqueFilterDataMethod(clazz) != null && clazz.declaredMethods.any {
            it.name == "m" && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
        }

    private fun cameraContext(classLoader: ClassLoader): Context? = runCatching {
        val global = classLoader.loadClass("com.xiaomi.camera.basic.Global")
        (global.getMethod("getContext").invoke(null) as? Context)
            ?: (global.getMethod("getApplication").invoke(null) as? Context)
    }.getOrNull()

    private fun copyMissingLeicaAssets(context: Context, workingDirectory: File): Int {
        val destination = workingDirectory.toPath().resolve("leica").toFile()
        var copied = 0
        val assets = context.assets
        val children = assets.list(LEICA_ASSET_ROOT).orEmpty()
        for (child in children) {
            val childPath = "$LEICA_ASSET_ROOT/$child"
            val childDestination = File(destination, child)
            copied += runCatching {
                copyMissingAssetTree(assets, childPath, childDestination)
            }.onFailure { t ->
                DebugLog.w(TAG, "failed to hydrate Leica asset $child", t)
            }.getOrDefault(0)
        }
        return copied
    }

    /** Copies only absent files; the camera's cloud copy always wins when present. */
    private fun copyMissingAssetTree(assets: AssetManager, assetPath: String, destination: File): Int {
        val children = assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw IllegalStateException("cannot create $destination")
            }
            var copied = 0
            for (child in children) {
                copied += copyMissingAssetTree(assets, "$assetPath/$child", File(destination, child))
            }
            return copied
        }
        if (destination.exists()) return 0
        destination.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count >= 0) {
                    if (count > 0) output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
        }
        return 1
    }

    private fun uniqueFilterDataMethod(clazz: Class<*>): Method? {
        val matches = clazz.declaredMethods.filter {
            it.name == "d" && !Modifier.isStatic(it.modifiers) && !it.isSynthetic &&
                it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
                it.returnType == Void.TYPE
        }
        return matches.singleOrNull()?.apply { isAccessible = true }
    }

    /**
     * Repairs the classic-watermark brand logo on devices whose capability config leaves it
     * empty. The watermark templates reference `${logo}` (resolved to `xiaomi_black.webp` /
     * `redmi_black.webp` / `poco_black.webp` per device), and the value comes from
     * `Je.c.x()` = `v()[0]`. On the verified baseline `myron` (REDMI K90 Pro Max) falls
     * through the `v()` brand-array switch to an empty array (only the `ALSC` market-name
     * variant gets `{"REDMI","Turbo 5"}`), so classic watermarks render with no logo at all —
     * neither brand nor Leica (classic watermarks never carry the Leica mark; `leica.webp`
     * only exists in the Leica category). With the camera switch on, a null/empty logo is
     * replaced by the brand of the device family.
     */
    private fun hookDeviceLogo(ctx: CameraResolver.Ctx) {
        val clazz = CameraResolver.resolveClass(
            scope = TAG,
            key = "wm_device_facade",
            ctx = ctx,
            candidates = DEVICE_FACADE_CANDIDATES,
            validate = { c ->
                c.declaredMethods.any {
                    it.name == "x" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "Je.c device config not resolved; logo repair skipped")
            return
        }
        val xMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "wm_device_logo_x", clazz = clazz,
            names = listOf("x"),
            shape = { it.parameterTypes.isEmpty() && it.returnType == String::class.java },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#x() not found; logo repair skipped")
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
