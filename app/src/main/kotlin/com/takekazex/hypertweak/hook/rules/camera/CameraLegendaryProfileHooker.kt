package com.takekazex.hypertweak.hook.rules.camera

import android.view.View
import com.takekazex.hypertweak.hook.CameraLegendaryMomentMode
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Process-local status shared with the entry and registry hooks. */
internal object CameraLegendaryProfileState {
    private val appliedMode = AtomicReference<String?>(null)
    private val nativeConfig = AtomicReference<Any?>(null)
    private val activeConfig = AtomicReference<Any?>(null)
    private val pixelEntry = AtomicReference<Class<*>?>(null)
    private val cinematicEntry = AtomicReference<Class<*>?>(null)

    fun captureNativeConfigIfMissing(config: Any) {
        nativeConfig.compareAndSet(null, config)
    }

    fun nativeConfigSnapshot(): Any? = nativeConfig.get()

    fun markApplied(mode: String?) {
        appliedMode.set(mode)
    }

    fun isApplied(mode: String): Boolean = appliedMode.get() == mode

    fun setActiveConfig(config: Any?) {
        activeConfig.set(config)
    }

    fun isActiveConfig(config: Any?): Boolean = config != null && activeConfig.get() === config

    fun setPixelEntry(type: Class<*>?) {
        pixelEntry.set(type)
    }

    fun pixelEntry(): Class<*>? = pixelEntry.get()

    fun setCinematicEntry(type: Class<*>?) {
        cinematicEntry.set(type)
    }

    fun cinematicEntry(): Class<*>? = cinematicEntry.get()

    fun selectedProfileApplied(): Boolean {
        val mode = Preferences.cameraLegendaryMomentMode()
        return mode == CameraLegendaryMomentMode.MODE_LEGENDARY && isApplied(mode)
    }
}

/** Opens Leica Moment on the device's existing config and applies opt-in mode gates individually. */
object CameraLegendaryProfileHooker : StaticHooker() {
    private const val TAG = "CamLegendaryProfile"
    private const val PACKAGE = "com.android.camera"
    private const val PIXEL_ENTRY = "com.android.camera.features.mode.pixel.PixelModuleEntry"
    private const val CINEMATIC_ENTRY = "com.android.camera.features.mode.cinematic.CinematicModuleEntry"
    private const val PIXEL_200MP_ITEM_ID = 254

    private val pixelItemLookupDepth = ThreadLocal<Int>()
    private val hookedPixelItemBuilders = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!hookParam.isMainProcess) {
            CameraLegendaryProfileState.markApplied(null)
            CameraLegendaryProfileState.setActiveConfig(null)
            return
        }

        // The camera creates its shared device config in a provider before building its mode list.
        CameraApplicationInit.afterConfigProviderCreate(this) {
            initializeCameraModes()
        }
    }

    private fun initializeCameraModes() {
        if (!hookParam.isMainProcess) return
        val mode = Preferences.cameraLegendaryMomentMode()
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val binding = CameraHostProfile.resolve(ctx, TAG) ?: run {
            reject(mode, "camera config ABI was not resolved")
            return
        }
        val current = binding.configInstance() ?: run {
            reject(mode, "active camera config could not be read")
            return
        }
        CameraLegendaryProfileState.captureNativeConfigIfMissing(current)
        val native = CameraLegendaryProfileState.nativeConfigSnapshot() ?: current

        if (current !== native &&
            (!binding.acceptsConfig(native) || !binding.replaceConfigInstance(native))
        ) {
            reject(mode, "could not restore the camera's native config before selecting a mode")
            CameraLegendaryProfileState.setActiveConfig(current)
            installExtraModeHooks(ctx, binding, current)
            return
        }
        CameraLegendaryProfileState.setActiveConfig(native)

        when (mode) {
            CameraLegendaryMomentMode.MODE_OFF -> {
                CameraLegendaryProfileState.markApplied(mode)
                DebugLog.d(TAG, "mode selector is off; keeping the device's native camera config")
            }
            CameraLegendaryMomentMode.MODE_LEICA -> {
                // Leica Moment is an entry gate. Importing Nezha's full config replaced the
                // device's Pixel/12.5/50MP and zoom data, so this selection never swaps config.
                CameraLegendaryProfileState.markApplied(mode)
                DebugLog.i(TAG, "Leica Moment entry enabled on the native config ${native.javaClass.name}")
            }
            CameraLegendaryMomentMode.MODE_LEGENDARY -> applyMadridProfile(ctx, binding, native)
            else -> {
                CameraLegendaryProfileState.markApplied(CameraLegendaryMomentMode.MODE_OFF)
                DebugLog.w(TAG, "unknown mode selection; keeping the device's native camera config")
            }
        }

        val active = binding.configInstance() ?: native
        CameraLegendaryProfileState.setActiveConfig(active)
        installExtraModeHooks(ctx, binding, active)
    }

    /** Legendary Moment still uses the camera's Madrid profile when that profile is packaged. */
    private fun applyMadridProfile(
        ctx: CameraResolver.Ctx,
        binding: CameraHostProfile.Binding,
        native: Any,
    ) {
        val mode = CameraLegendaryMomentMode.MODE_LEGENDARY
        val sourceName = CameraLegendaryMomentMode.targetProfileClassName(mode) ?: run {
            reject(mode, "Madrid profile name is unavailable")
            return
        }
        val sourceResolver = CameraResolver.resolveSourceNameResolver(ctx, TAG) ?: run {
            reject(mode, "camera source-name resolver was not uniquely resolved")
            return
        }
        val targetClass = runCatching { sourceResolver.invoke(null, sourceName) as? Class<*> }.getOrNull()
            ?: run {
                reject(mode, "camera did not provide $sourceName")
                return
            }
        if (!binding.configType.isAssignableFrom(targetClass)) {
            reject(mode, "${targetClass.name} does not implement the active ${binding.family} config ABI")
            return
        }
        val target = runCatching {
            targetClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull() ?: run {
            reject(mode, "${targetClass.name} could not be constructed")
            return
        }
        if (!binding.acceptsConfig(target)) {
            reject(mode, "${targetClass.name} failed full camera-config ABI validation")
            return
        }
        if (!binding.replaceConfigInstance(target)) {
            reject(mode, "camera facade refused the validated Madrid config")
            return
        }
        CameraLegendaryProfileState.markApplied(mode)
        CameraLegendaryProfileState.setActiveConfig(target)
        DebugLog.i(TAG, "applied Legendary Moment camera profile ${target.javaClass.name} (from $sourceName)")
    }

    /**
     * Extra modes are targeted entry changes. They do not replace the active device config:
     * Pixel's 200MP item is added to its native mode UI, and Cinematic support is raised only
     * for CinematicModuleEntry. The setting is live-read so other camera features remain native.
     */
    private fun installExtraModeHooks(
        ctx: CameraResolver.Ctx,
        binding: CameraHostProfile.Binding,
        activeConfig: Any,
    ) {
        if (binding.family != CameraHostProfile.Family.CAMERA_66) return
        hookPixelMode(ctx, binding, activeConfig)
        hookCinematicMode(ctx)
    }

    private fun hookPixelMode(
        ctx: CameraResolver.Ctx,
        binding: CameraHostProfile.Binding,
        activeConfig: Any,
    ) {
        val entry = CameraResolver.resolveClass(
            scope = TAG,
            key = "pixel_module_entry",
            ctx = ctx,
            candidates = listOf(PIXEL_ENTRY),
            validate = { type ->
                type.declaredMethods.any {
                    it.name == "support" && it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
                } && type.declaredMethods.any {
                    it.name == "getModeItem" && it.parameterCount == 0
                } && type.declaredMethods.any {
                    it.name == "getModeUI" && it.parameterCount == 0
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "PixelModuleEntry was not resolved; Pixel extras skipped")
            return
        }
        CameraLegendaryProfileState.setPixelEntry(entry)

        val getModeItem = CameraResolver.resolveMethod(
            scope = TAG,
            key = "pixel_module_item_name",
            clazz = entry,
            names = listOf("getModeItem"),
            shape = { it.parameterCount == 0 },
        ) ?: return
        val e4 = binding.configMethod(activeConfig.javaClass, "E4", java.lang.Boolean.TYPE)
        if (e4 == null) {
            DebugLog.w(TAG, "Pixel 200MP display gate was not found on ${activeConfig.javaClass.name}")
        } else {
            deoptimize(e4)
            e4.hook("cam_pixel_200mp_entry_label") {
                after { param ->
                    if (pixelItemLookupDepth() > 0 && extraModesEnabled() &&
                        CameraLegendaryProfileState.isActiveConfig(activeConfig)
                    ) {
                        param.result = true
                    }
                }
            }
            getModeItem.hook("cam_pixel_200mp_entry_label_scope") {
                before {
                    if (extraModesEnabled()) pixelItemLookupDepth.set(pixelItemLookupDepth() + 1)
                }
                after {
                    val depth = pixelItemLookupDepth()
                    if (depth > 1) {
                        pixelItemLookupDepth.set(depth - 1)
                    } else if (depth == 1) {
                        pixelItemLookupDepth.remove()
                    }
                }
            }
        }

        val getModeUi = CameraResolver.resolveMethod(
            scope = TAG,
            key = "pixel_module_ui_provider",
            clazz = entry,
            names = listOf("getModeUI"),
            shape = { it.parameterCount == 0 && !it.returnType.isPrimitive },
        ) ?: return
        val itemFactory = CameraResolver.resolveMethodByNumbers(
            scope = TAG,
            key = "pixel_200mp_item_factory",
            ctx = ctx,
            numbers = listOf(PIXEL_200MP_ITEM_ID),
            shape = { method ->
                Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
                    !method.returnType.isPrimitive && method.returnType.declaredFields.count {
                        !Modifier.isStatic(it.modifiers) && it.type == Integer.TYPE
                    } >= 2 && method.returnType.declaredFields.any {
                        !Modifier.isStatic(it.modifiers) && View.OnClickListener::class.java.isAssignableFrom(it.type)
                    }
            },
        )
        if (itemFactory == null) {
            DebugLog.w(TAG, "200MP Pixel-item factory was not uniquely resolved; no synthetic item will be added")
        }

        getModeUi.hook("cam_pixel_200mp_item_ui") {
            after { param ->
                val ui = param.result ?: return@after
                val itemBuilder = ui.javaClass.declaredMethods.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                        ArrayList::class.java.isAssignableFrom(it.returnType)
                }?.apply { isAccessible = true } ?: run {
                    DebugLog.w(TAG, "Pixel UI item builder was not unique on ${ui.javaClass.name}")
                    return@after
                }
                if (!hookedPixelItemBuilders.add(itemBuilder)) return@after
                itemBuilder.hook("cam_pixel_200mp_item") {
                    after { itemParam ->
                        if (!extraModesEnabled()) return@after
                        @Suppress("UNCHECKED_CAST")
                        val items = itemParam.result as? ArrayList<Any?> ?: return@after
                        append200MpItem(items, itemFactory)
                    }
                }
                DebugLog.d(TAG, "Pixel mode item builder hooked on ${itemBuilder.declaringClass.name}#${itemBuilder.name}()")
            }
        }
        DebugLog.i(TAG, "Pixel entry gate, label, and optional 200MP mode item hooks installed")
    }

    private fun append200MpItem(items: ArrayList<Any?>, factory: Method?) {
        factory ?: return
        val template = items.firstOrNull() ?: return
        val type = template.javaClass
        val idField = type.declaredFields.singleOrNull {
            !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.type == Integer.TYPE
        }?.apply { isAccessible = true } ?: return
        if (items.any { item ->
                item != null && runCatching { idField.getInt(item) == PIXEL_200MP_ITEM_ID }.getOrDefault(false)
            }
        ) {
            return
        }
        val constructor: Constructor<*> = type.declaredConstructors.singleOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == factory.returnType
        }?.apply { isAccessible = true } ?: run {
            DebugLog.w(TAG, "Pixel mode item wrapper does not accept ${factory.returnType.name}; 200MP item skipped")
            return
        }
        val builder = runCatching { factory.invoke(null) }.getOrNull() ?: run {
            DebugLog.w(TAG, "200MP Pixel-item factory failed; item skipped")
            return
        }
        val item = runCatching { constructor.newInstance(builder) }.getOrNull() ?: return
        items.add(item)
        DebugLog.i(TAG, "added the native 200MP Pixel item to its existing mode UI")
    }

    private fun hookCinematicMode(ctx: CameraResolver.Ctx) {
        val entry = CameraResolver.resolveClass(
            scope = TAG,
            key = "cinematic_module_entry",
            ctx = ctx,
            candidates = listOf(CINEMATIC_ENTRY),
            validate = { type ->
                type.declaredMethods.any {
                    it.name == "getModuleId" && it.parameterCount == 0 && it.returnType == Integer.TYPE
                } && type.declaredMethods.any {
                    it.name == "support" && it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "CinematicModuleEntry was not resolved; Cinematic extras skipped")
            return
        }
        CameraLegendaryProfileState.setCinematicEntry(entry)
        val support = CameraResolver.resolveMethod(
            scope = TAG,
            key = "cinematic_module_support",
            clazz = entry,
            names = listOf("support"),
            shape = { it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE },
        ) ?: return
        deoptimize(support)
        support.hook("cam_unlock_cinematic_entry") {
            after { param ->
                if (extraModesEnabled()) param.result = true
            }
        }
        DebugLog.i(TAG, "CinematicModuleEntry.support() follows the extra camera modes switch")
    }

    private fun extraModesEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_LEGENDARY_EXTRA_MODES, false)

    private fun pixelItemLookupDepth(): Int = pixelItemLookupDepth.get() ?: 0

    private fun reject(mode: String, reason: String) {
        CameraLegendaryProfileState.markApplied(null)
        DebugLog.w(TAG, "$mode profile was not applied; keeping the device's native config: $reason")
    }
}
