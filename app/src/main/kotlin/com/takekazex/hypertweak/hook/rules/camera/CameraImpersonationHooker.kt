package com.takekazex.hypertweak.hook.rules.camera

import android.os.Build
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.StaticFieldWriter
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * Impersonates the camera app's per-device capability config as a flagship, so every
 * capability gate (mode visibility, MIVI pipeline switches, `instanceof`-based flagship
 * branches, ...) opens on any device — while keeping the **watermark device model** and the
 * **focal-length line-up** on this device's own values.
 *
 * Architecturally (reverse-engineering notes in
 * `cache/camera-5cd70925b1646cdf/CAMERA_UNLOCK_EVALUATION.md` and
 * `CAMERA_FEATURE_GATES_17ULTRA.md`):
 *
 * 1. The whole capability surface funnels through one object: `Je.c.b.f8427a.f8420e`,
 *    created by the single static factory `Je/e.q()` (`Class.forName("com.mi.device."+device)`).
 *    Hooking `Je/e#q` to return a flagship instance (`com.mi.device.Nezha`, the 17 Ultra
 *    config) unlocks everything at once.
 * 2. The watermark brand/model strings come from `Je/c#x()` (=`v()[0]`, brand logo), `Je/c#y()`
 *    (=`v()[1]` model text) and `Je/c#w()` (=`v()[2]`). Both `x()/v()` are forced back to THIS
 *    device's brand + market name (or a user custom brand/model) so the on-picture watermark
 *    keeps the local model. The EXIF `Model` tag is `ro.product.marketname` directly
 *    (`Je.d.f8434h`), never the capability config, so EXIF stays correct.
 * 3. The zoom/focal getters (`B1/q0/e1/A1/C1/v1/x1/y0/h1`) are kept on the REAL device config
 *    instance (see `KEY_CAMERA_KEEP_FOCAL`), so 焦段 stays the device's own while every
 *    capability boolean still comes from the flagship.
 * 4. `Je/c#V()` (LCC theme gate) is only impersonated when the user opts in
 *    (`KEY_CAMERA_IMPERSONATE_THEME_LCC`); and `Ox/g#i()` (the LCC customization-provider
 *    toggle that hides the 相机配色 settings entry) is forced true whenever the master switch is
 *    on so the tint-color entry is never lost.
 *
 * Requires a camera app restart after the first enable (the hooks are installed by
 * `HookEntry` on attach). Disabling removes the overrides immediately on the next
 * capability read (the callbacks re-read `Preferences` live).
 */
object CameraImpersonationHooker : StaticHooker() {
    private const val TAG = "CamImpersonate"
    private const val PACKAGE = "com.android.camera"

    /** Obfuscated (visible-name) config-factory class and flagship device classes. */
    private const val CONFIG_FACTORY = "Uf.c"
    private const val DEVICE_NEZHA = "com.mi.device.Nezha"
    private const val DEVICE_FLAGSHIP = "com.mi.device.xiaomi.CommonFlagship"

    /**
     * Focal-length getters to keep on the device's own config instance while impersonating a
     * flagship. All nine are declared (and overridden) on the flagship config hierarchy
     * (`defpackage/C1143` Common → `C1136` CommonFlagship → `C1178` Nezha) and are consumed by
     * the zoom line-up / mm labels / SAT route code. Boolean capability getters are deliberately
     * NOT included — those must keep the flagship (impersonated) values.
     */
    private val FOCAL_GETTERS = arrayOf("B1", "q0", "e1", "A1", "C1", "v1", "x1", "y0", "h1")

    private val flagshipInstance = AtomicReference<Any?>(null)
    private val originalInstance = AtomicReference<Any?>(null)
    private val originalThirdSlot = AtomicReference<Any?>(null)

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        installHooks()
    }

    private fun installHooks() {
        hookConfigFactory()
        hookWatermarkKeep()
        hookLccTheme()
        hookLccCustomizationProvider()
        hookKeepFocal()
    }

    // ─── 1. Replace the per-device capability config with a flagship instance ──────

    private fun hookConfigFactory() {
        val clazz = "Je.e".toClassOrNull() ?: run {
            DebugLog.w(TAG, "Je.e config factory not resolved; impersonation skipped")
            return
        }
        val qMethod = runCatching {
            clazz.declaredMethods.first {
                it.name == "q" && it.parameterTypes.isEmpty()
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "Je.e#q() not found; impersonation skipped")
            return
        }
        deoptimize(qMethod)
        qMethod.hook("cam_impersonate_q") {
            after { param ->
                if (!enabled()) return@after
                val flagship = flagshipInstance() ?: return@after
                param.result = flagship
                // Help callers that read the private cache field instead of this method.
                patchConfigCacheFields(flagship)
            }
        }
        DebugLog.d(TAG, "impersonation hooked on ${clazz.name}#q()")
    }

    /**
     * If the config factory already ran (the app initialised `Je.c` before our hook), the
     * singleton's `f8420e` and the factory cache `Je.e.b` still hold the original instance.
     * Rewrite both so the impersonation is actually effective; harmless when they already
     * point at our flagship instance.
     */
    private fun patchConfigCacheFields(flagship: Any) {
        runCatching {
            val jeE = "Je.e".toClassOrNull()
            val bField = jeE?.getDeclaredField("b") ?: return
            StaticFieldWriter.set(bField, flagship)
        }.onFailure { t ->
            DebugLog.w(TAG, "Je.e.b cache patch failed", t)
        }
        runCatching {
            val jeC = "Je.c".toClassOrNull()
            val singletonField = jeC?.getField("b") ?: return
            val singleton = singletonField.get(null) ?: return
            val f8420e = singleton.javaClass.getDeclaredField("f8420e")
            f8420e.isAccessible = true
            f8420e.set(singleton, flagship)
        }.onFailure { t ->
            DebugLog.w(TAG, "Je.c.f8420e patch failed (defensive; usually not needed)", t)
        }
    }

    private fun flagshipInstance(): Any? {
        flagshipInstance.get()?.let { return it }
        synchronized(flagshipInstance) {
            flagshipInstance.get()?.let { return it }
            val built = buildFlagshipInstance()
            if (built != null) flagshipInstance.set(built)
            return built
        }
    }

    /**
     * Load `com.mi.device.Nezha` (or the generic flagship) through the host's own class-name
     * rewrite (`Uf.c.a`), so the R8-obfuscated dex class name is resolved exactly as the app
     * does, then instantiate it. The instance is host-loaded, so all `instanceof <flagship>`
     * branches inside the capability config work.
     */
    private fun buildFlagshipInstance(): Any? {
        val loader = classLoader ?: return null
        return runCatching {
            val factory = loader.loadClass(CONFIG_FACTORY)
            val resolver = factory.getDeclaredMethod("a", String::class.java)
            buildFrom(loader, resolver, DEVICE_NEZHA)
                ?: buildFrom(loader, resolver, DEVICE_FLAGSHIP)
        }.getOrNull()?.also {
            DebugLog.d(TAG, "flagship config instance created: ${it.javaClass.name}")
        } ?: run {
            DebugLog.e(TAG, "flagship config could not be instantiated")
            null
        }
    }

    private fun buildFrom(
        loader: ClassLoader,
        resolver: java.lang.reflect.Method,
        name: String
    ): Any? = runCatching {
        val cls = resolver.invoke(null, name) as? Class<*> ?: return@runCatching null
        cls.newInstance()
    }.getOrNull()

    // ─── 2. Keep this device's brand + model on the watermark (or a user custom one) ─

    private fun hookWatermarkKeep() {
        captureOriginalThirdSlot()
        val clazz = "Je.c".toClassOrNull() ?: run {
            DebugLog.w(TAG, "Je.c not resolved; watermark keep skipped")
            return
        }
        val xMethod = runCatching {
            clazz.declaredMethods.first {
                it.name == "x" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "Je.c#x() not found; watermark keep skipped")
            return
        }
        deoptimize(xMethod)
        xMethod.hook("cam_keep_model_logo") {
            after { param ->
                if (!keepModel()) return@after
                param.result = CameraWatermarkBrand.brand()
            }
        }

        val vMethod = runCatching {
            clazz.declaredMethods.first {
                it.name == "v" && it.parameterTypes.isEmpty() && it.returnType.isArray
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "Je.c#v() not found; watermark keep skipped")
            return
        }
        deoptimize(vMethod)
        vMethod.hook("cam_keep_model_brand") {
            after { param ->
                if (!keepModel()) return@after
                // Mirror the platform's 3-slot shape [brand, model, third] so `w()` (=v()[2])
                // keeps behaving; `y()` (=v()[1]) and `x()` (=v()[0]) read back our values.
                param.result = arrayOf(
                    CameraWatermarkBrand.brand(),
                    CameraWatermarkBrand.model(),
                    originalThirdSlot.get()
                )
            }
        }
        DebugLog.d(TAG, "watermark keep hooked on ${clazz.name}#x()/#v()")
    }

    /**
     * Best-effort capture of the ORIGINAL real config's watermark third text slot so the
     * keep-model `v()` after-hook reproduces the platform's 3-slot shape. The config classes
     * (C1143 family) do NOT expose a `v()` method — that lives on the `Je.c` facade — so this
     * normally resolves nothing and leaves the third slot null, which is safe (`Je.c.w()` guards
     * on `length>2`) and matches a stock non-flagship's empty third element.
     */
    private fun captureOriginalThirdSlot() {
        val original = originalConfigInstance().get() ?: return
        runCatching {
            val v = original.javaClass.getMethod("v")
            val arr = v.invoke(original) as? Array<*> ?: return@runCatching
            if (arr.size > 2) originalThirdSlot.set(arr[2])
        }.onFailure { t ->
            // NoSuchMethodException on the config class is expected (v() is a facade method);
            // only surface unexpected failures.
            if (t !is java.lang.reflect.InvocationTargetException &&
                t.message?.contains("NoSuchMethod", ignoreCase = true) != true
            ) {
                DebugLog.w(TAG, "original v()[2] capture failed (defensive)", t)
            }
        }
    }

    // ─── 3. (Optional) fake the LCC theme so LCC-gated flagship branches open too ──

    private fun hookLccTheme() {
        val clazz = "Je.c".toClassOrNull() ?: return
        val vMethod = runCatching {
            clazz.declaredMethods.first {
                it.name == "V" && it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "Je.c#V() LCC gate not found; theme impersonation skipped")
            return
        }
        deoptimize(vMethod)
        vMethod.hook("cam_impersonate_theme_lcc") {
            after { param ->
                if (Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE_THEME_LCC, false)) {
                    param.result = true
                }
            }
        }
        DebugLog.d(TAG, "LCC theme impersonation hooked on ${clazz.name}#V()")
    }

    // ─── 4. Keep the 相机配色 (tint color) settings entry visible under LCC impersonation ─

    /**
     * `CameraCommonPreferenceFragment.addCustomizationPreferences` gates the 相机配色 entry on
     * `p496o9.a.f53967a.d().i()`. `p496o9/a` picks the provider holder from `Je/c.V()`: the LCC
     * branch uses `Ox.g(5)` whose `i()` returns false (jadx `Ox/g.java:195`), so forcing the
     * LCC theme hides the entry. Forcing `Ox.g#i()` to true whenever the master switch is on
     * restores it; a genuinely-LCC device without the master switch keeps its stock behaviour.
     */
    private fun hookLccCustomizationProvider() {
        val clazz = "Ox.g".toClassOrNull() ?: run {
            DebugLog.w(TAG, "Ox.g LCC customization provider not resolved; tint-color restore skipped")
            return
        }
        val iMethod = runCatching {
            clazz.declaredMethods.first {
                it.name == "i" && it.parameterTypes.isEmpty() &&
                    it.returnType == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "Ox.g#i() not found; tint-color restore skipped")
            return
        }
        deoptimize(iMethod)
        iMethod.hook("cam_restore_tint_color") {
            before { param ->
                if (enabled()) param.result = true
            }
        }
        DebugLog.d(TAG, "tint-color restore hooked on ${clazz.name}#i()")
    }

    // ─── 5. Keep this device's own focal lengths (焦段) while impersonating ─────────

    /**
     * After `Je/e.q()` returns a flagship, the bottom zoom line-up / mm labels come from Nezha.
     * Keep all capability booleans (flagship) but delegate the focal getters to the REAL device
     * config instance, so 焦段 stays the device's own. Gated on `KEY_CAMERA_IMPERSONATE` AND
     * `KEY_CAMERA_KEEP_FOCAL` (default true).
     */
    private fun hookKeepFocal() {
        val flagship = flagshipInstance() ?: return
        // Hook on the actual concrete flagship class so virtual dispatch resolves to our hook
        // (hooking the base C1143 would be skipped by Nezha/CommonFlagship overrides).
        val target = flagship.javaClass
        var hooked = 0
        for (name in FOCAL_GETTERS) {
            val method = runCatching { target.getMethod(name) }.getOrNull()
            if (method == null || method.parameterCount != 0) {
                DebugLog.d(TAG, "focal getter ${target.name}#$name() not found; skipped")
                continue
            }
            deoptimize(method)
            method.hook("cam_keep_focal_$name") {
                before { param ->
                    if (!keepFocal()) return@before
                    val original = originalConfigInstance().get() ?: return@before
                    // Delegate to the ORIGINAL device config's getter so 焦段 stays this device's own.
                    // Methods are cached at first use (AGENTS.md hot-path rule), not per call.
                    originalFocalMethods(original)[name]?.invoke(original)?.let {
                        param.result = it
                    }
                }
            }
            hooked++
        }
        DebugLog.d(TAG, "keep-focal hooked on ${target.name}: $hooked/${FOCAL_GETTERS.size} getters")
    }

    /** Per-getter cached delegation methods resolved off the ORIGINAL device config's concrete class. */
    private val originalFocalMethodsCache = AtomicReference<Map<String, Method>?>()

    private fun originalFocalMethods(original: Any): Map<String, Method> {
        originalFocalMethodsCache.get()?.let { return it }
        synchronized(originalFocalMethodsCache) {
            originalFocalMethodsCache.get()?.let { return it }
            val resolved = FOCAL_GETTERS.associateWith { name ->
                runCatching { original.javaClass.getMethod(name) }
                    .getOrNull()
                    ?.takeIf { it.parameterCount == 0 }
            }.filterValues { it != null }.mapValues { it.value!! }
            originalFocalMethodsCache.set(resolved)
            return resolved
        }
    }

    /**
     * The REAL device config instance (e.g. `com.mi.device.Myron`), built exactly the way the
     * factory does — NOT read from the `Je.e.b` cache (which now holds the impersonated flagship).
     * Used both as the source of the original watermark third slot and for focal delegation.
     */
    private fun originalConfigInstance(): AtomicReference<Any?> {
        originalInstance.get()?.let { return originalInstance }
        synchronized(originalInstance) {
            originalInstance.get()?.let { return originalInstance }
            val built = buildOriginalConfigInstance()
            if (built != null) originalInstance.set(built)
            return originalInstance
        }
    }

    /**
     * Replays `Je/e.q()`'s full fallback chain (jadx `Je/e.java:47-72`) with the REAL device
     * base name, so the ORIGINAL config instance resolves exactly as it would without
     * impersonation — including devices whose per-device class is not shipped in the APK
     * (then it falls back to `com.mi.device.others.<Manufacturer>` and finally the weak
     * default `Ne.a`, mirroring the factory's own catch branches):
     * 1. `com.mi.device.<Capitalize(Je/a.f8410c | Build.DEVICE)>` — e.g. Myron
     * 2. `com.mi.device.others.<Capitalize(Build.MANUFACTURER)>`
     * 3. `new Ne.a()` — the low-spec weak default used by non-flagship Redmis
     *
     * Returns null (focal keep & third-slot capture are skipped) only if even `Ne.a` is
     * unexpectedly unavailable.
     */
    private fun buildOriginalConfigInstance(): Any? {
        val loader = classLoader ?: return null
        return runCatching {
            val factory = loader.loadClass(CONFIG_FACTORY)
            val resolver = factory.getDeclaredMethod("a", String::class.java)
            val deviceBase = readDeviceBaseName() ?: Build.DEVICE
            buildFrom(loader, resolver, "com.mi.device.${capitalize(deviceBase)}")
                ?: buildFrom(
                    loader, resolver,
                    "com.mi.device.others.${capitalize(Build.MANUFACTURER)}"
                )
                ?: runCatching {
                    loader.loadClass("Ne.a").getDeclaredConstructor().newInstance()
                }.getOrNull()
        }.getOrNull()?.also {
            DebugLog.d(TAG, "original device config instance created: ${it.javaClass.name}")
        } ?: run {
            DebugLog.w(TAG, "original device config could not be instantiated; focal keep unavailable")
            null
        }
    }

    /** Capitalise the first ASCII letter (mirrors `Je/e.f(String)`). */
    private fun capitalize(name: String): String {
        if (name.isEmpty()) return name
        val first = name[0]
        return if (first in 'a'..'z') first.uppercaseChar() + name.substring(1) else name
    }

    /**
     * Read the device base name from `Je/a.f8410c` (a `Pu.n` lazy) exactly as `Je/e.q()` does
     * (`f8444c = (String) a.f8410c.getValue()`). `Pu.n.getValue()` is a public no-arg method.
     */
    private fun readDeviceBaseName(): String? = runCatching {
        val jeA = "Je.a".toClassOrNull() ?: return@runCatching null
        val field = jeA.getDeclaredField("f8410c").apply { isAccessible = true }
        val lazy = field.get(null) ?: return@runCatching null
        val getValue = lazy.javaClass.getMethod("getValue")
        (getValue.invoke(lazy) as? String)?.takeIf { it.isNotEmpty() }
    }.onFailure { t ->
        DebugLog.w(TAG, "Je/a.f8410c read failed", t)
    }.getOrNull()

    // ─── gate helpers ─────────────────────────────────────────────────────────────

    private fun enabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE, false)

    private fun keepModel(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_WM_KEEP_MODEL, true)

    private fun keepFocal(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_KEEP_FOCAL, true)
}
