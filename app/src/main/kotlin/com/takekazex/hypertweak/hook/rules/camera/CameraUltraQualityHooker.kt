package com.takekazex.hypertweak.hook.rules.camera

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Pin-unlocks the camera's 超高 (SUPER) image-quality option (`com.android.camera`,
 * MiuiCamera). Verified against 6.6.000460.0 and 6.6.000510.0 (the gate is byte-identical on
 * both); reverse-engineering notes live in the reverse workspace at
 * `cache/camera-8f41d7b82453cdeb/`.
 *
 * 设置 → 图片质量 (`SettingImageQuality`, pref key `pref_camera_jpegquality_key`) only offers
 * the 超高 entry while the per-device capability config reports `l7() == true`. Three call
 * sites read that single gate:
 *  - the capture-mode option list (`features/mode/capture/Y`, "SettingImageQuality" case,
 *    ~L3489) and the settings-page entry (`fragment/settings/e`, same key, ~L1665) prepend
 *    超高 (`R.string.h72` / value list `R.string.h77`) at index 0 only when
 *    `Je.c.b.f8427a.f8420e.l7()`;
 *  - the quality clamp `com.android.camera.data.data.j#t()` (~L4417) caps the effective
 *    selection at `F1.g3.SUPER` when `l7()` is true, else at `HIGH` — so a stored 超高 value
 *    silently degrades to 高 without the gate.
 *
 * The quality enum `F1.g3` is plain JPEG-quality pairs: LOW(67,81) NORMAL(87,89) HIGH(96,95)
 * SUPER(100,100) persisted under `pref_camera_jpegquality_key`. There is no HAL dependency in
 * the gate, so forcing it true only widens the settings UI and raises the clamp.
 *
 * `l7()` is declared ONCE on the config base class (jadx `C1174` on 510, `C1143` on 460) as
 * `return this instanceof C1148` — a flagship-only marker check — and is NOT overridden by the
 * configs that matter here: this device's own `com.mi.device.Myron` (C1196) and the K100
 * Pro Max impersonation target `com.mi.device.Songyuan` (C1200) both inherit it -> false ->
 * 超高 hidden. Only the Nezha flagship family (C1160/C1204/C1209) overrides it final-true.
 * Hooking the base's declared Method therefore intercepts every subclass instance that does
 * not override it — one hook covers both the native and the K100-impersonated path. With the
 * legacy `nezha` impersonation target the config class overrides `l7()` as final-true, so the
 * hook does not fire there and 超高 stays visible regardless of this switch (native flagship
 * behaviour; the default `k100promax` target IS covered).
 *
 * Read live from [Preferences.KEY_CAMERA_ULTRA_HD_QUALITY] (default ON = 固定解锁). When off,
 * the hook forces false — exactly the stock value on this device — which also re-clamps a
 * stale stored 超高 selection back to 高 through `j#t()`. Toggling takes effect the next time
 * the quality option list is built or the clamp runs (no camera restart once hooks are
 * installed; the first enable needs one, like every hooker installed on attach).
 */
object CameraUltraQualityHooker : StaticHooker() {
    private const val TAG = "CamUltraQuality"
    private const val PACKAGE = "com.android.camera"

    /**
     * Device-config facade `Je.c` — identical dex name on 6.6.000460.0 and 6.6.000510.0
     * (same candidates as CameraImpersonationHooker.CONFIG_FACADE_CANDIDATES; kept local so
     * this hooker stays independent of that class's private constants).
     */
    private val CONFIG_FACADE_CANDIDATES = listOf("Je.b", "Je.c")

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        installHooks()
    }

    private fun installHooks() {
        val gate = resolveSuperQualityGate() ?: run {
            DebugLog.w(TAG, "config l7 gate not resolved; ultra image-quality unlock skipped")
            return
        }
        deoptimize(gate)
        gate.hook("cam_ultra_hd_quality") {
            before { param ->
                // Always short-circuit (set result, never proceed): forcing false while the
                // switch is off equals the stock value on this device (inherited l7()=false),
                // so always setting changes nothing when off and additionally clamps a stale
                // stored 超高 selection back to 高 via j#t().
                param.result = cameraUltraHdQuality()
            }
        }
        DebugLog.d(
            TAG,
            "ultra image-quality gate hooked on ${gate.declaringClass.name}#${gate.name}()"
        )
    }

    /**
     * Resolve the `l7()` Method on the config hierarchy through the `Je.c` facade.
     *
     * Channel A (static; touches no instance, so nothing is initialized prematurely): the
     * facade's config field `e` (jadx alias `f8420e`) is declared with the config base type;
     * `type.getMethod("l7")` walks the hierarchy and returns the Method of the class that
     * actually DECLARES it — exactly the Method inherited dispatch executes for subclasses
     * without an override.
     *
     * Channel B (runtime fallback for a renamed facade field): read the live singleton
     * `Je.c$b.a` -> its config field -> the concrete config class -> the same declaring
     * Method. Reached only when channel A fails; by then the impersonation factory hook
     * (attached before this hooker in HookEntry) already owns the singleton, so reading it
     * cannot race the impersonation.
     */
    private fun resolveSuperQualityGate(): Method? {
        val ctx = CameraResolver.Ctx(classLoader, hookParam.appInfo)
        val facade = CameraResolver.resolveClass(
            scope = TAG, key = "ultra_hd_facade", ctx = ctx,
            candidates = CONFIG_FACADE_CANDIDATES,
            validate = { c ->
                c.declaredMethods.any {
                    it.name == "x" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
                }
            },
        ) ?: run {
            DebugLog.w(TAG, "Je.c facade not resolved; cannot derive config class")
            return null
        }
        // Channel A: declared config-field type -> declaring Method of l7().
        resolveField(facade, "e", "f8420e")?.let { configField ->
            runCatching { configField.type.getMethod("l7") }.getOrNull()
                ?.takeIf(::isSuperQualityGate)
                ?.let {
                    DebugLog.d(TAG, "config gate resolved via facade field type ${configField.type.name}")
                    return it
                }
        }
        // Channel B: live singleton's concrete config class -> same declaring Method. The holder
        // was `Je.c$b` on 460/510 and `Je.b$C0165b` on 540, so discover it by its static field
        // type instead of baking another R8-generated nested-class name.
        runCatching {
            val singleton = facade.declaredClasses.asSequence()
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.type == facade
                }?.apply { isAccessible = true }?.get(null) ?: return@runCatching
            val config = resolveField(singleton.javaClass, "e", "f8420e")
                ?.apply { isAccessible = true }?.get(singleton) ?: return@runCatching
            config.javaClass.getMethod("l7").takeIf(::isSuperQualityGate)?.let {
                DebugLog.d(TAG, "config gate resolved via live config ${config.javaClass.name}")
                return it
            }
        }.onFailure { t ->
            DebugLog.w(TAG, "live config instance fallback failed (defensive)", t)
        }
        DebugLog.w(TAG, "no zero-arg boolean l7() found on the config hierarchy")
        return null
    }

    /** Zero-arg instance method returning a primitive boolean — the `l7()` shape. */
    private fun isSuperQualityGate(method: Method): Boolean =
        method.parameterTypes.isEmpty() &&
            method.returnType == java.lang.Boolean.TYPE &&
            !Modifier.isStatic(method.modifiers)

    /** Resolve a field by its real dex name, falling back to the jadx alias. */
    private fun resolveField(clazz: Class<*>, vararg names: String): Field? {
        for (name in names) {
            runCatching { clazz.getDeclaredField(name) }.getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Live read of the 超高图片质量 pin switch (same accessor pattern as the impersonation
     * hookers' `streetEnable()` / `masterliveTeleFallback()`; served from the 100 ms
     * Preferences memo). Default ON = 固定解锁.
     */
    private fun cameraUltraHdQuality(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_ULTRA_HD_QUALITY, true)
}
