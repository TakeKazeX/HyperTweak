package com.takekazex.hypertweak.hook.rules.camera

import android.content.Context
import android.content.res.AssetManager
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.nio.file.Path

object CameraWatermarkHooker : StaticHooker() {
    private const val TAG = "CamWmUnlock"
    private const val PACKAGE = "com.android.camera"


    private const val LEICA_ASSET_ROOT = "watermarks/leica"

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        installHooks()
    }

    private fun installHooks() {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val features: List<Pair<String, () -> Unit>> = listOf(
            "hookDebugFlag" to { hookDebugFlag(ctx) },
            "hookCloudCatalogFilter" to { hookCloudCatalogFilter(ctx) },
            "hookFilterData" to { hookFilterData(ctx) },
            "hookLeicaResourceHydration" to { hookLeicaResourceHydration(ctx) },
            "hookDeviceLogo" to { hookDeviceLogo(ctx) },
        )
        features.forEach { (name, install) ->
            runCatching(install).onFailure { DebugLog.w(TAG, "$name initialization failed; other features continue", it) }
        }
    }

    private fun hookDebugFlag(ctx: CameraResolver.Ctx) {
        val semantics = CameraSemantics.create(ctx, TAG) ?: return
        val invoke = semantics.anchored("watermark debug property", "camera.cloud.watermark.debug") {
            it.parameterTypes.isEmpty() &&
                (it.returnType in setOf("Z", "Ljava/lang/Boolean;") ||
                    (it.returnType == "Ljava/lang/Object;" && semantics.dex.code(it).calls.any { call ->
                        call.method.definingClass == "Ljava/lang/Boolean;" && call.method.name == "valueOf"
                    }))
        } ?: return
        val clazz = invoke.declaringClass
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

    private fun hookCloudCatalogFilter(ctx: CameraResolver.Ctx) {
        fun hasNativeFilter(type: Class<*>): Boolean = type.declaredMethods.any { method ->
            Modifier.isStatic(method.modifiers) && Modifier.isNative(method.modifiers) &&
                method.returnType == String::class.java && method.parameterTypes.contentEquals(
                    arrayOf(
                        String::class.java, String::class.java,
                        java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
                        java.lang.Float.TYPE, java.lang.Double.TYPE,
                        java.lang.Long.TYPE, Integer.TYPE,
                    ),
                )
        }

        val clazz = CameraResolver.resolveClass(
            scope = TAG,
            key = "wm_cloud_catalog_filter",
            ctx = ctx,

            probe = { bridge ->
                bridge.findMethod {
                    matcher {
                        returnType(String::class.java)
                        paramCount(8)
                    }
                }.asSequence()
                    .filter { data ->
                        Modifier.isNative(data.modifiers) && data.returnTypeName == String::class.java.name &&
                            data.paramTypeNames == listOf(
                                String::class.java.name, String::class.java.name,
                                "boolean", "boolean", "float", "double", "long", "int",
                            )
                    }
                    .map { it.className }
                    .distinct()
                    .singleOrNull()
            },
            validate = ::hasNativeFilter,
        ) ?: run {
            DebugLog.w(TAG, "cloud watermark native filter not resolved; catalog stays stock")
            return
        }

        val wrapper = clazz.declaredMethods.singleOrNull { method ->
            Modifier.isStatic(method.modifiers) && !Modifier.isNative(method.modifiers) &&
                method.returnType == String::class.java && method.parameterTypes.contentEquals(
                    arrayOf(
                        String::class.java, String::class.java,
                        java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
                        java.lang.Float.TYPE, java.lang.Long.TYPE, Integer.TYPE,
                    ),
                )
        }?.apply { isAccessible = true } ?: run {
            DebugLog.w(TAG, "cloud watermark native-filter wrapper is not unique; catalog stays stock")
            return
        }

        deoptimize(wrapper)
        wrapper.hook("wm_cloud_catalog_capabilities") {
            before { param ->
                if (!Preferences.getBoolean(Preferences.KEY_WM_CAMERA, false)) return@before
                param.args[2] = true
                param.args[3] = true
            }
        }
        DebugLog.i(TAG, "cloud watermark catalog capability filter hooked on ${clazz.name}#${wrapper.name}()")
    }

    private fun hookFilterData(ctx: CameraResolver.Ctx) {
        val semantics = CameraSemantics.create(ctx, TAG) ?: return
        val method = semantics.anchored("watermark limitation filter", "filterData: E ", "filterData: delete ") {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z")
        } ?: return
        val clazz = method.declaringClass
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

    private fun hookLeicaResourceHydration(ctx: CameraResolver.Ctx) {
        val semantics = CameraSemantics.create(ctx, TAG) ?: return
        val scan = semantics.anchored("watermark resource scan", "initData: E", "initData: X") {
            it.returnType == "V" && it.parameterTypes.isEmpty()
        } ?: return
        val clazz = scan.declaringClass
        val workingPath = semantics.anchored("watermark working directory", "workingPath") {
            it.definingClass == CameraDexIndex.descriptor(clazz) && it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/nio/file/Path;"
        } ?: return
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



    private fun hookDeviceLogo(ctx: CameraResolver.Ctx) {
        val profile = CameraHostProfile.resolve(ctx, TAG) ?: return
        val clazz = profile.facade
        val xMethod = CameraResolver.resolveMethod(
            scope = TAG, key = "wm_device_logo_x", clazz = clazz,
            name = profile.brandGetter ?: return,
            shape = { it.parameterTypes.isEmpty() && it.returnType == String::class.java },
        ) ?: run {
            DebugLog.w(TAG, "${clazz.name}#${profile.brandGetter}() not found; logo repair skipped")
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
        DebugLog.d(TAG, "device logo repair hooked on ${clazz.name}#${profile.brandGetter}()")
    }
}
