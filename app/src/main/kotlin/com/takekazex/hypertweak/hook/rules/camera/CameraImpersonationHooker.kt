package com.takekazex.hypertweak.hook.rules.camera

import android.os.Build
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.StaticFieldWriter
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import java.lang.reflect.Field
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
 * 1. The whole capability surface funnels through one object: `Je.c$b.a.e` (jadx renders the
 *    singleton holder as `Je.c.b.f8427a` holding the facade whose config field it shows as
 *    `f8420e`), created by the single static factory `Je/e.q()` (`Class.forName("com.mi.device."+device)`).
 *    Hooking `Je/e#q` to return a flagship instance (`com.mi.device.Nezha`, the 17 Ultra
 *    config) unlocks everything at once.
 * 2. The watermark brand/model strings come from `Je/c#x()` (=`v()[0]`, brand logo), `Je/c#y()`
 *    (=`v()[1]` model text) and `Je/c#w()` (=`v()[2]`). Both `x()/v()` are forced back to THIS
 *    device's brand + market name (or a user custom brand/model) so the on-picture watermark
 *    keeps the local model. The EXIF `Model` tag is `ro.product.marketname` directly
 *    (`Je.d.a` feeds the device-name lazy; the market name is never the capability config), so
 *    EXIF stays correct.
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
     * Dex class name of the REDMI K100 Pro Max / POCO F9 Ultra config (jadx `defpackage/C1151`).
     * Verified byte-exact in the APK's dex string tables (2026-08-20). The app's own resolver
     * `Uf.c.a` maps the `com.mi.device.*` source name onto this obfuscated dex name; for an
     * already-obfuscated name the same resolver falls through to plain `Class.forName`, so a
     * direct `loadClass` is the deterministic loading path for `KEY_CAMERA_IMPERSONATE_TARGET`.
     */
    private const val DEVICE_K100PROMAX_DEX = "峡峭峯岬峯峫岬峦峧峴峫峡峧岬峑峭峬峥峻峷峣峬"

    /** `KEY_CAMERA_IMPERSONATE_TARGET` values. */
    private const val TARGET_K100PROMAX = "k100promax"
    private const val TARGET_NEZHA = "nezha"

    /**
     * Real dex class names of jadx-renamed classes used by this hooker. Verified byte-exact in
     * the APK dex string pools (RESEARCH_MYRON_ONDEVICE_EVIDENCE 2026-08-21): jadx renames dex
     * packages it cannot emit as valid Java packages (`u6`, `i5`, `fs` — the obfuscator's short
     * package names) onto synthetic `p####` names, and renames FIELDS colliding with root package
     * names onto `f#####` names. Method names are NOT renamed and always resolve. Using the jadx
     * aliases (e.g. `"p703u6.e"`) made the affected lookups return null on the device; the dex
     * names below load directly on the real class loader.
     *  - `p703u6.e` (Camera2CompatAdapterRole) -> `u6.e` -- classes.dex
     *  - `p288i5.d` (S8.d watermark entry holder)  -> `i5.d` -- classes.dex
     *  - `p203fs.m` (WmModelView)                  -> `fs.m` -- classes10.dex
     */
    private const val ROLE_ADAPTER_DEX = "u6.e"
    private const val WATERMARK_HOLDER_DEX = "i5.d"
    private const val WM_MODEL_VIEW_DEX = "fs.m"

    /** Resolve a class by its real dex name, falling back to the jadx alias for older ROMs. */
    private fun resolveClass(vararg names: String): Class<Any>? {
        for (name in names) name.toClassOrNull()?.let { return it }
        return null
    }

    /** Resolve a field by its real dex name, falling back to the jadx alias (`getDeclaredField`). */
    private fun resolveField(clazz: Class<*>, vararg names: String): Field? {
        for (name in names) {
            runCatching { clazz.getDeclaredField(name) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Resolve a public field (including inherited ones) by real dex name, falling back to the alias. */
    private fun resolvePublicField(clazz: Class<*>, vararg names: String): Field? {
        for (name in names) {
            runCatching { clazz.getField(name) }.getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Focal-length getters to keep on the device's own config instance while impersonating a
     * flagship. All nine are declared (and overridden) on the flagship config hierarchy
     * (`defpackage/C1143` Common -> `C1136` CommonFlagship -> `C1178` Nezha) and are consumed by
     * the zoom line-up / mm labels / SAT route code. Boolean capability getters are deliberately
     * NOT included — those must keep the flagship (impersonated) values.
     */
    private val FOCAL_GETTERS = arrayOf("B1", "e1", "A1", "C1", "v1", "x1", "y0", "h1")

    /**
     * Imaging-identity getters delegated back to the real device config while impersonating.
     * These report the SENSOR / LENS identity (sensor param string, sensor id, lens-id array,
     * lens count, LUT-directory float, per-camera output profile, output-format set, LUT-write
     * mechanism). Keeping them on the flagship feeds 17-Ultra calibration to MIVI/HAL
     * CCM·WB selection, which is why a Leica-Classic + tele RAW turns purple only after gallery
     * re-processing (the RAW is re-rendered with the wrong sensor calibration). Delegating them
     * to the REAL device config fixes the colour while all `instanceof`-based capability gates
     * (and the modes they open) stay flagship. Includes `B1` implicitly via FOCAL_GETTERS and
     * the mode gates (`y4/a3/b6`) separately.
     */
    private val IDENTITY_GETTERS = arrayOf("O1", "D", "q1", "r1", "o0", "S6", "M", "K2")

    /**
     * Every zero-arg getter we may delegate to the original config instance (focal lengths,
     * imaging identity, and the mode gates). Resolved once per original concrete class and
     * cached — the callbacks fire on hot paths so per-call reflection is avoided.
     */
    private val ORIGINAL_DELEGATE_NAMES =
        FOCAL_GETTERS + IDENTITY_GETTERS + arrayOf("y4", "a3", "b6", "B4", "l2")

    private val flagshipInstance = AtomicReference<Any?>(null)
    private val originalInstance = AtomicReference<Any?>(null)
    private val originalThirdSlot = AtomicReference<String?>(null)
    private val originalDelegates = AtomicReference<Map<String, Method>?>()
    private val deviceIsNezhaCache = AtomicReference<Boolean?>()

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        installHooks()
    }

    private fun installHooks() {
        hookConfigFactory()
        hookWatermarkKeep()
        hookWatermarkConfigCache()
        hookWatermarkRender()
        hookWatermarkBrandText()
        hookLccTheme()
        hookLccCustomizationProvider()
        hookKeepFocal()
        hookImagingIdentity()
        hookModeGuards()
        hookLeicaStyle()
        hookMasterLiveTeleFallback()
        hookMasterLiveOpModeSafe()
        hookShutterSoundBoundary()
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
     * singleton's config field and the factory cache `Je.e.b` still hold the original instance.
     * Rewrite both so the impersonation is actually effective; harmless when they already
     * point at our flagship instance.
     */
    private fun patchConfigCacheFields(flagship: Any) {
        runCatching {
            val jeE = "Je.e".toClassOrNull()
            // Je.e.b is the static factory cache (real dex field name `b`).
            val bField = jeE?.getDeclaredField("b") ?: return
            StaticFieldWriter.set(bField, flagship)
        }.onFailure { t ->
            DebugLog.w(TAG, "Je.e.b cache patch failed", t)
        }
        runCatching {
            // The app's singleton lives on the static inner class `Je.c$b` (jadx renders it as
            // `Je.c.b.f8427a`): its static field `a` (public static final) holds the Je.c
            // instance, whose instance field `e` (jadx alias `f8420e`) is the per-device config.
            // `Je.c` itself has no static singleton field — its `b` is an unrelated Boolean.
            val holderClass = resolveClass("Je.c\$b") ?: return
            val singletonField = resolveField(holderClass, "a", "f8427a") ?: return
            val singleton = singletonField.get(null) ?: return
            val configField = resolveField(singleton.javaClass, "e", "f8420e") ?: return
            configField.isAccessible = true
            configField.set(singleton, flagship)
        }.onFailure { t ->
            DebugLog.w(TAG, "Je.c singleton cache patch failed (defensive; usually not needed)", t)
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
     * Load the impersonated config instance. The target is chosen by
     * `KEY_CAMERA_IMPERSONATE_TARGET`:
     *  - `"k100promax"` (default): REDMI K100 Pro Max / POCO F9 Ultra (jadx C1151), loaded by its
     *    obfuscated dex name. Best target for REDMI K-series devices (myron): sensor axis
     *    byte-identical to the device's own config (correct CCM/WB — no purple), `y4()=true` with
     *    a REDMI MasterLive effect table (no 17U tele/12.9x crash path), REDMI watermark strings.
     *  - `"nezha"`: legacy 17 Ultra (old behaviour). Loaded through the host's class-name rewrite
     *    (`Uf.c.a`) exactly as the app does.
     * Both fall back to the generic flagship if the chosen class cannot be instantiated. The
     * instance is host-loaded, so all `instanceof <flagship>` branches work.
     */
    private fun buildFlagshipInstance(): Any? {
        val loader = classLoader ?: return null
        return runCatching {
            val factory = loader.loadClass(CONFIG_FACTORY)
            val resolver = factory.getDeclaredMethod("a", String::class.java)
            if (targetIsK100Promax()) {
                runCatching { loader.loadClass(DEVICE_K100PROMAX_DEX).newInstance() }.getOrNull()
                    ?: buildFrom(loader, resolver, DEVICE_NEZHA)
                    ?: buildFrom(loader, resolver, DEVICE_FLAGSHIP)
            } else {
                buildFrom(loader, resolver, DEVICE_NEZHA)
                    ?: buildFrom(loader, resolver, DEVICE_FLAGSHIP)
            }
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
                // keeps behaving and `y()` (=v()[1]) / `x()` (=v()[0]) read back our values.
                // WARNING: `v()` returns `String[]` — the array MUST materialize as a real
                // String[] (Kotlin Array<String?>). A bare `arrayOf(brand, model, Any?)` infers
                // Array<Any?> -> Object[] and the caller's `String[] v()` check-cast then throws
                // ClassCastException, which dead-locked the camera with keep-model on.
                param.result = arrayOf<String?>(
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
            if (arr.size > 2) originalThirdSlot.set(arr[2] as? String)
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

    // ─── 3. Keep the watermark config cache (S8.d) fresh with this device's brand/model ─

    /**
     * The camera caches the classic/Leica-watermark brand + model once in the `S8.d` singleton
     * (`S8.d.a.a`, an `i5.d` built from `Je/c#x()/#y()` at first construction; jadx shows the
     * names as `f15058a`/`f68841a`/`p288i5.d`, `S8/d.java:36`). Because that cache is
     * process-lifetime, two problems surfaced: (a) if the singleton is built before `Preferences`
     * is ready in the camera process the keep hooks no-op and Nezha's own strings get baked
     * ("17 Ultra" watermark right after capture, reverting only after a later live re-read), and
     * (b) a custom-watermark change made later never reaches the cached config at all. Hooking
     * the singleton accessor `S8.d.a()` re-asserts the cached entry with the current brand()/
     * model() on every access, so the watermark fires this device's values (or the custom
     * override) at every render.
     */
    private fun hookWatermarkConfigCache() {
        val clazz = "S8.d".toClassOrNull() ?: run {
            DebugLog.w(TAG, "S8.d watermark manager not resolved; config cache refresh skipped")
            return
        }
        val aMethod = runCatching {
            clazz.declaredMethods.firstOrNull {
                it.name == "a" && it.parameterTypes.isEmpty() &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == clazz
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "S8.d#a() not found; config cache refresh skipped")
            return
        }
        deoptimize(aMethod)
        aMethod.hook("cam_wm_config_refresh") {
            after { param ->
                if (!enabled()) return@after
                refreshWatermarkConfigCache(param.result ?: return@after)
            }
        }
        DebugLog.d(TAG, "watermark config cache refresh hooked on ${clazz.name}#a()")
    }

    /** Re-assert the brand+model watermark entry (`i5.d`, jadx `p288i5.d`) into the `S8.d` singleton unless it already matches. */
    private fun refreshWatermarkConfigCache(singleton: Any) {
        runCatching {
            val brand = CameraWatermarkBrand.brand()
            val model = CameraWatermarkBrand.model()
            // Real dex fields: S8.d.a (jadx `f15058a`), zi.b.a (jadx `f68841a`),
            // i5.d.a/b (jadx `f43446a`/`b`) — jadx renamed the colliding ones on root-package
            // collisions; the on-device names are the short letters.
            val holderField = resolveField(singleton.javaClass, "a", "f15058a")?.apply { isAccessible = true }
                ?: return
            val holder = holderField.get(singleton) ?: return
            val cacheField = resolveField(holder.javaClass, "a", "f68841a")?.apply { isAccessible = true }
                ?: return
            val current = cacheField.get(holder)
            // Best-effort equality guard; if the current value can't be read, just rebuild.
            val same = runCatching {
                val curBrand = current?.javaClass
                    ?.let { resolveField(it, "a", "f43446a") }
                    ?.apply { isAccessible = true }?.get(current)
                val curModel = current?.javaClass
                    ?.let { resolveField(it, "b") }
                    ?.apply { isAccessible = true }?.get(current)
                curBrand == brand && curModel == model
            }.getOrDefault(false)
            if (same) return
            val dClass = resolveClass(WATERMARK_HOLDER_DEX, "p288i5.d") ?: return
            val ctor = dClass.getDeclaredConstructor(Any::class.java, Any::class.java).apply {
                isAccessible = true
            }
            cacheField.set(holder, ctor.newInstance(brand, model))
        }.onFailure { t ->
            DebugLog.w(TAG, "watermark config cache refresh failed (defensive)", t)
        }
    }

    // ─── 4. Force this device's brand/model into every watermark render ───────────

    /**
     * `com.xiaomi.cam.watermark.a#J0(String deviceLogo, String model, boolean)` is the final
     * funnel every classic/cloud watermark render passes through (called by `zi/b.d()`, jadx
     * `p890zi/b.d()`, with the `S8.d` cached brand+model). Two reasons to force it:
     * (a) the watermark model view `fs/m.o()` (jadx `p203fs/m.o()`) treats a model of
     *     "17 ultra by leica" / "leitzphone powered by xiaomi" as an lcc_gl device and renders
     *     the 17-Ultra-style watermark — that is the "17U watermark right after capture" leak,
     *     because some capture-time reads still see the impersonated strings. Forcing the J0
     *     args to `CameraWatermarkBrand` values makes the lcc_gl branch unreachable (the model
     *     is never a 17U string) for EVERY render, including the immediate capture one;
     * (b) the custom brand/model reach the renderer regardless of which intermediate
     *     cache/path fed J0.
     * Only a non-blank incoming render is overridden; an explicitly blank (watermark-off) call
     * is left untouched.
     */
    private fun hookWatermarkRender() {
        val clazz = "com.xiaomi.cam.watermark.a".toClassOrNull() ?: run {
            DebugLog.w(TAG, "watermark renderer not resolved; render keep skipped")
            return
        }
        val j0 = runCatching {
            clazz.declaredMethods.firstOrNull {
                it.name == "J0" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "watermark renderer#J0 not found; render keep skipped")
            return
        }
        deoptimize(j0)
        j0.hook("cam_wm_render_keep") {
            before { param ->
                val incomingBrand = param.args[0] as? String
                val incomingModel = param.args[1] as? String
                if (incomingBrand.isNullOrEmpty() && incomingModel.isNullOrEmpty()) return@before
                param.args[0] = CameraWatermarkBrand.brand()
                param.args[1] = CameraWatermarkBrand.model()
            }
        }
        DebugLog.d(TAG, "watermark render keep hooked on com.xiaomi.cam.watermark.a#J0")
    }

    // ─── 5. Render the custom watermark brand as plain text when it has no logo ────

    /**
     * The classic/Leica watermark layout renders the brand as a LOGO IMAGE (`${logo}` in the
     * template, `x()=v()[0]` -> `ic_device_watermark_logo_{redmi,xiaomi,poco}`), so an arbitrary
     * custom brand (any name that is NOT one of the three bundled logo names) has no image asset
     * and never shows. The model text goes through the `WmModelView` (`fs/m#o`, jadx
     * `p203fs/m#o`; = brand, model, textUpper, isCN), which fills a per-layout set of model lines
     * from its `text` format (`@{logo}`, `@{series}`, `@{versionNumber}`, `@{versionName}`) into
     * the public `p` field on the base `fs.o` (jadx `p203fs.o.f40639p`) — the actual model line
     * ("K90 Pro Max" on this device), which is NEVER blank on a real unit. After-hooking
     * `fs/m.o` and PREPENDING the custom BRAND as its own leading line (the view renderer splits
     * on "\n" and draws each line) shows "厂商 / 机型" as two text lines for any custom brand.
     * `m.o()` rebuilds the field from its format on every call, so the prepend is naturally
     * idempotent; a contains/first-line guard protects against a format that already embeds the
     * brand via `@{logo}`.
     */
    private fun hookWatermarkBrandText() {
        val clazz = resolveClass(WM_MODEL_VIEW_DEX, "p203fs.m") ?: run {
            DebugLog.w(TAG, "WmModelView ($WM_MODEL_VIEW_DEX) not resolved; brand-as-text skipped")
            return
        }
        val oMethod = runCatching {
            clazz.declaredMethods.firstOrNull {
                it.name == "o" && it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == java.lang.Boolean.TYPE &&
                    it.parameterTypes[3] == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "WmModelView#o not found; brand-as-text skipped")
            return
        }
        deoptimize(oMethod)
        oMethod.hook("cam_wm_brand_text") {
            after { param ->
                val customBrand =
                    CameraWatermarkBrand.customBrand().takeIf { it.isNotEmpty() } ?: return@after
                val receiver = param.thisObject ?: return@after
                // Match the case the renderer would use for the brand (`m.o()` uppercases it
                // under `text_upper`, and the bundled logos are XIAOMI / REDMI / POCO).
                val brandText = customBrand.uppercase()
                // Bundled logo brands already render as a real logo image (`ic_device_watermark_logo_*`
                // resolved from `Je.c#x()`), so prepending them as text would duplicate the logo
                // ("REDMI" image + "REDMI" text) exactly like the market-name bug. Only a brand with
                // NO logo asset (an arbitrary custom word) needs the text line.
                if (brandText == "XIAOMI" || brandText == "REDMI" || brandText == "POCO") return@after
                runCatching {
                    // the model text field lives (public, real dex name `p` / jadx alias
                    // `f40639p`) on the WmModelView base `fs.o` (jadx p203fs.o)
                    val textField = resolvePublicField(receiver.javaClass, "p", "f40639p")
                        ?.apply { isAccessible = true } ?: return@runCatching
                    val current = textField.get(receiver) as? String ?: ""
                    val modelLines = current.lines().filter { it.isNotBlank() }
                    // Keep the actual model line(s); prepend the brand as its own line so the
                    // brand shows as text. The old blank-only injection never fired because the
                    // model line is populated on a real unit — hence the brand never appeared.
                    val composed = when {
                        modelLines.isEmpty() -> brandText
                        modelLines.first() == brandText -> current
                        current.contains(brandText) -> current
                        else -> (listOf(brandText) + modelLines).joinToString("\n")
                    }
                    textField.set(receiver, composed)
                }.onFailure { t ->
                    DebugLog.w(TAG, "brand-as-text injection failed (defensive)", t)
                }
            }
        }
        DebugLog.d(TAG, "brand-as-text hooked on ${clazz.name}#o()")
    }

    // ─── 6. (Optional) fake the LCC theme so LCC-gated flagship branches open too ──

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

    // ─── 7. Keep the 相机配色 (tint color) settings entry visible under LCC impersonation ─

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

    // ─── 8. Keep this device's own focal lengths (焦段) while impersonating ─────────

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
                    // SOE protection: never fall through to `proceed` and never let the
                    // reflective delegation throw. ezhooktool's proceed re-enters the still-hooked
                    // getter via Method.invoke -> infinite recursion at process start
                    // (MiviInfoContentProvider.onCreate touches these getters first).
                    if (!keepFocal()) { return@before }
                    val original = originalConfigInstance().get()
                    if (original != null) {
                        val delegated = runCatching {
                            // Delegate to the ORIGINAL device config's getter so 焦段 stays this
                            // device's own. Methods are cached (AGENTS.md hot-path rule).
                            originalFocalMethods(original)[name]?.invoke(original)
                        }.getOrNull()
                        if (delegated != null) { param.result = delegated; return@before }
                    }
                    // Always short-circuit: skipping with the flagship's own value is safe and
                    // guarantees `skipped=true` so `proceed` is never re-entered.
                    param.result = soeSafeFallback(method, param)
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

    // ─── 8b. Keep this device's own imaging identity while impersonating (变紫 fix) ───

    /**
     * Delegate the sensor/lens identity getters (O1/D/q1/r1/o0/S6/M/K2) back to the REAL device
     * config while impersonating, so colour-critical pipelines (Leica Classic + tele RAW through
     * gallery re-processing) feed the real sensor identity to MIVI/HAL CCM·WB selection instead of
     * the 17-Ultra calibration the impersonation would otherwise supply. This is the "徕卡经典 +
     * 长焦 → 相册后期处理变紫" fix (RESEARCH_LEICA_CLASSIC_PURPLE.md §6.1). Capability booleans
     * stay flagship, so no mode is lost. Gated on `KEY_CAMERA_IMPERSONATE` AND
     * `KEY_CAMERA_KEEP_IMAGING` (default on). No-op on a real nezha (delegating returns the
     * flagship's own values there) and when impersonation is off.
     */
    private fun hookImagingIdentity() {
        val flagship = flagshipInstance() ?: return
        val target = flagship.javaClass
        var hooked = 0
        for (name in IDENTITY_GETTERS) {
            val method = runCatching { target.getMethod(name) }.getOrNull()
            if (method == null || method.parameterCount != 0) {
                DebugLog.d(TAG, "imaging getter ${target.name}#$name() not found; skipped")
                continue
            }
            deoptimize(method)
            method.hook("cam_keep_imaging_$name") {
                before { param ->
                    // SOE protection: always set result / never throw -> `proceed` is never
                    // re-entered (which would recurse through the still-hooked getter).
                    if (!keepImaging() || deviceIsNezha()) { return@before }
                    val original = originalConfigInstance().get()
                    if (original != null) {
                        val delegated = runCatching {
                            originalDelegateMethods(original)[name]?.invoke(original)
                        }.getOrNull()
                        if (delegated != null) { param.result = delegated; return@before }
                    }
                    param.result = soeSafeFallback(method, param)
                }
            }
            hooked++
        }
        DebugLog.d(
            TAG,
            "keep-imaging hooked on ${target.name}: $hooked/${IDENTITY_GETTERS.size} getters"
        )
    }

    // ─── 8c. Mode gates: close the hardware-impossible ones, open the ones we now want ──

    /**
     * Mode-gate plan per impersonation target (see RESEARCH_MYRON_00_SYNTHESIS.md):
     *  - K100 Pro Max target (default): `y4()` stays true (实况运镜 visible, its REDMI effect table
     *    is hardware-realistic), `a3()` is forced true by `hookStreetEnable` (街拍 visible, opens
     *    the HAL role-0 main camera), `b6()` stays true (= the device's own native main-id scheme,
     *    `C1209` also has `b6=true`). Only 装备街拍 (hard-opens cameras 13/7 that do not exist) and
     *    传奇人像 (RAW+LUT reprocessing) stay closed.
     *  - Legacy Nezha target: keep the old delegation guards — `y4/a3` hidden by
     *    `KEY_CAMERA_GUARD_MODES` (their 17U hardware doesn't exist here), `b6` clampable via
     *    `KEY_CAMERA_GUARD_CAMERA_ID`.
     */
    private fun hookModeGuards() {
        val flagship = flagshipInstance() ?: return
        val target = flagship.javaClass
        // Legacy Nezha target only: hide the 17U hardware-dependent modes on non-flagships.
        hookDelegateBoolean(target, "y4", "cam_guard_mode_masterlive") { guardModes() && targetIsNezha() }
        hookDelegateBoolean(target, "a3", "cam_guard_mode_street") { guardModes() && targetIsNezha() }
        hookDelegateBoolean(target, "b6", "cam_guard_camera_id") { guardCameraId() && targetIsNezha() }
        // 装备街拍 can never work without the 17U module-lens cameras (13/7) — always closed on
        // impersonated non-flagships (K100 Pro Max target yields false naturally; Nezha target
        // needs the clamp).
        hookFacadeEquipStreetGate()
        hookLegendarySupport()
        // K100 Pro Max target only: make 街拍 visible + consistent with the quick-launch.
        hookStreetEnable()
        DebugLog.d(TAG, "mode guards installed on ${target.name}")
    }

    /** Delegate a zero-arg boolean getter on the flagship config class to the original config. */
    private fun hookDelegateBoolean(
        target: Class<*>,
        name: String,
        hookId: String,
        gate: () -> Boolean,
    ) {
        val method = runCatching {
            target.getMethod(name).takeIf {
                it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.d(TAG, "guard getter ${target.name}#$name() not found; skipped")
            return
        }
        deoptimize(method)
        method.hook(hookId) {
            before { param ->
                // SOE protection: always short-circuit (set result) so `proceed` is never
                // re-entered and the reflective delegation never throws out of the callback.
                if (!enabled() || !gate() || deviceIsNezha()) { return@before }
                val original = originalConfigInstance().get()
                if (original != null) {
                    val delegated = runCatching {
                        originalDelegateMethods(original)[name]?.invoke(original)
                    }.getOrNull()
                    if (delegated != null) { param.result = delegated; return@before }
                }
                param.result = soeSafeFallback(method, param)
            }
        }
        DebugLog.d(TAG, "guard hooked on ${target.name}#$name()")
    }

    /**
     * Force `a3()` (street support) true on the impersonated config. No REDMI config ships
     * `a3=true`, so with the K100 Pro Max target street is invisible/quick-launch CAPTURE until
     * this hook turns it on; the mode then opens the real HAL role-0 main camera (its
     * `b6=true` = native main-id scheme). `hookDelegateBoolean("a3", ...)` above is only armed
     * for the Nezha target, so the two never fight. Gated on `KEY_CAMERA_STREET_ENABLE`
     * (default on) + the K100 Pro Max target.
     */
    private fun hookStreetEnable() {
        val flagship = flagshipInstance() ?: return
        val target = flagship.javaClass
        val method = runCatching {
            target.getMethod("a3").takeIf {
                it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.d(TAG, "street-enable getter ${target.name}#a3() not found; skipped")
            return
        }
        deoptimize(method)
        method.hook("cam_street_enable") {
            before { param ->
                // Always short-circuit (set result true|false) so `proceed` is never re-entered
                // (same SOE-safe pattern as the other delegating before-hooks). When the gate is
                // off, forcing false equals C1151's own inherited `a3()=false`, so no behaviour
                // is changed by always setting.
                param.result = enabled() && streetEnable() && targetIsK100Promax()
            }
        }
        DebugLog.d(TAG, "street-enable hooked on ${target.name}#a3()")
    }

    /**
     * Restore the Leica photography style (摄影风格 cv_type 徕卡经典 ↔ 徕卡生动) switcher on the
     * impersonated config. The 摄影风格 component (`C4164m` F3-gate at :148) and every top-bar /
     * mode style entry gate on the config's `F3()` (`X2()` additionally for the specific-capture
     * path `capture/h0`) — `true` on the CommonFlagship (Nezha) branch, `false` on the REDMI
     * C1199 branch that C1151 (K100 Pro Max) inherits. The K100 Pro Max impersonation therefore
     * drops the 徕卡经典/徕卡生动 switcher that the legacy 17-Ultra impersonation had (agent +
     * jadx verified: `C1136#F3()/X2()=true`, `C1199#F3()=instanceof C1156=false`,
     * `C1143#X2()=false`). Forcing `F3()/X2()` to `true` on the impersonated config brings it
     * back. Legendary stays closed (`LegendaryEnter.support()=W0() && V()` with
     * `W0()=instanceof C1178=false`) and `M()` is keep-imaging-delegated to C1209 (no 231
     * LCC-RAW), so no purple/RAW regression. Side effect: `f2.c.b()` adds the four Leica shutter
     * sounds when `F3()` is true — benign (the 8-entry list also removes the IOOBE that the
     * legacy `key_shutter_sound=4` used to hit). Gated on `KEY_CAMERA_LEICA_STYLE` (default on)
     * + the K100 Pro Max target.
     */
    private fun hookLeicaStyle() {
        val flagship = flagshipInstance() ?: return
        val target = flagship.javaClass
        var hooked = 0
        for (name in arrayOf("F3", "X2")) {
            val method = runCatching {
                target.getMethod(name).takeIf {
                    it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
                }
            }.getOrNull() ?: run {
                DebugLog.d(TAG, "leica-style getter ${target.name}#$name() not found; skipped")
                continue
            }
            deoptimize(method)
            method.hook("cam_leica_style_$name") {
                before { param ->
                    // Always short-circuit (set result) so `proceed` is never re-entered (SOE-safe,
                    // same pattern as the other config before-hooks).
                    param.result = enabled() && targetIsK100Promax() && leicaStyle()
                }
            }
            hooked++
        }
        DebugLog.d(TAG, "leica-style flags hooked on ${target.name}: $hooked/2")
    }

    /** `Je.c#M()` (boolean) = `e.B4() && e.l2()` (jadx shows the config field as `f8420e`) — the 装备街拍 gate. */
    private fun hookFacadeEquipStreetGate() {
        val clazz = "Je.c".toClassOrNull() ?: run {
            DebugLog.w(TAG, "Je.c not resolved; equip-street guard skipped")
            return
        }
        val mMethod = runCatching {
            clazz.declaredMethods.first {
                it.name == "M" && it.parameterTypes.isEmpty() &&
                    it.returnType == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "Je.c#M() not found; equip-street guard skipped")
            return
        }
        deoptimize(mMethod)
        mMethod.hook("cam_guard_mode_equipstreet") {
            before { param ->
                if (!enabled() || deviceIsNezha()) return@before
                val original = originalConfigInstance().get() ?: return@before
                param.result = invokeBoolean(original, "B4") && invokeBoolean(original, "l2")
            }
        }
        DebugLog.d(TAG, "equip-street guard hooked on ${clazz.name}#M()")
    }

    /** 传奇人像 (Legendary 256) RAW / re-processing pipeline — keep it closed on non-flagships. */
    private fun hookLegendarySupport() {
        val clazz = "com.android.camera.features.mode.legendary.LegendaryEnter"
            .toClassOrNull() ?: run {
            DebugLog.w(TAG, "LegendaryEnter not resolved; legendary guard skipped")
            return
        }
        val support = runCatching {
            clazz.declaredMethods.first {
                it.name == "support" && it.parameterTypes.isEmpty() &&
                    it.returnType == java.lang.Boolean.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "LegendaryEnter#support() not found; legendary guard skipped")
            return
        }
        deoptimize(support)
        support.hook("cam_guard_mode_legendary") {
            after { param ->
                if (enabled() && !deviceIsNezha()) param.result = false
            }
        }
        DebugLog.d(TAG, "legendary guard hooked on ${clazz.name}#support()")
    }

    /**
     * MasterLive (实况运镜) role-23 (`Standalone`) fallback. The K100 Pro Max effect table ends
     * its zoom on the `Standalone` role, resolved via `u6/e#M()` (jadx `p703u6/e#M()` =
     * `f62592h.get(23, -1)`; the real dex class is `u6.e` — the jadx alias `p703u6.e` does not
     * exist in the dex and resolved to null on the device). A device whose tele is only labelled
     * role 20 (Samsung JN5) has no role-23 camera -> -1 -> the 15x endpoint never resolves. This
     * hook falls back to the role-20 tele camera (`r()`) only when role 23 is absent
     * (RESEARCH_MYRON_02 §6.2); on a device that really has role 23 (myron: role 23 <-> camera 4)
     * it is a no-op. Gated on `KEY_CAMERA_MASTERLIVE_TELE_FALLBACK` (default on).
     */
    private fun hookMasterLiveTeleFallback() {
        val clazz = resolveClass(ROLE_ADAPTER_DEX, "p703u6.e") ?: run {
            DebugLog.w(
                TAG,
                "Camera2CompatAdapterRole ($ROLE_ADAPTER_DEX) not resolved; masterlive tele fallback skipped"
            )
            return
        }
        val mMethod = runCatching {
            clazz.declaredMethods.first {
                it.name == "M" && it.parameterTypes.isEmpty() &&
                    it.returnType == java.lang.Integer.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "${clazz.name}#M() not found; masterlive tele fallback skipped")
            return
        }
        deoptimize(mMethod)
        mMethod.hook("cam_masterlive_tele_fallback") {
            after { param ->
                if (!masterliveTeleFallback() || deviceIsNezha()) return@after
                if ((param.result as? Int) != -1) return@after
                val receiver = param.thisObject ?: return@after
                teleCameraId(receiver)?.let { param.result = it }
            }
        }
        DebugLog.d(TAG, "masterlive tele fallback hooked on ${clazz.name}#M()")
    }

    /** Cached `r()` (role-20 tele) method on the role adapter (`u6.e`, jadx `p703u6/e`). */
    private val teleCameraIdMethod = AtomicReference<Method?>()

    private fun teleCameraId(receiver: Any): Int? {
        val method = teleCameraIdMethod.get() ?: synchronized(teleCameraIdMethod) {
            teleCameraIdMethod.get() ?: runCatching {
                receiver.javaClass.getMethod("r").takeIf { it.parameterCount == 0 }
            }.getOrNull()?.also { teleCameraIdMethod.set(it) }
        } ?: return null
        return runCatching { (method.invoke(receiver) as? Int)?.takeIf { it != -1 } }
            .getOrNull()
    }

    /**
     * MasterLive (实况运镜, module 231) op-mode safety net (`KEY_CAMERA_MASTERLIVE_OPMODE_SAFE`,
     * default off). On Qualcomm myron the MasterLive session would normally run op-mode 1
     * (CONSTRAINED_HIGH_SPEED, `U3/p#i` case 0); if that mode does not produce frames on the HAL
     * the capture never completes and the mode appears frozen. Forcing the MasterLive branch to
     * ALGO_UP_SAT (36866) uses a plain session that always produces frames; the K100 Pro Max effect
     * table has no 120fps type, so no high-speed semantics are lost. Class `U3/p` is a real dex
     * name (verified in the on-device APK). Enable only if on-device logs confirm op-mode 1 stalls.
     */
    private fun hookMasterLiveOpModeSafe() {
        val clazz = "U3.p".toClassOrNull() ?: run {
            DebugLog.w(TAG, "U3.p (MasterLiveModuleDevice) not resolved; op-mode safe hook skipped")
            return
        }
        val iMethod = clazz.declaredMethods.firstOrNull {
            it.name == "i" && it.returnType == java.lang.Integer.TYPE
        } ?: run {
            // NOTE: the real method is `U3/p.i(p841y3.w)` — it takes ONE parameter, so match by
            // name + return type only (verified in jadx U3/p.java:168-202). Matching a zero-arg
            // `i()` left the hook uninstalled on-device ("U3/p#i() not found").
            DebugLog.w(TAG, "U3/p#i() not found; op-mode safe hook skipped")
            return
        }
        val bField = runCatching {
            clazz.getDeclaredField("b").apply { isAccessible = true }
        }.getOrNull()
        val masterLiveB = bField != null

        deoptimize(iMethod)
        iMethod.hook("cam_masterlive_opmode_safe") {
            before { param ->
                if (!opModeSafe() || deviceIsNezha()) return@before
                val thisObj = param.thisObject ?: return@before
                // `U3/p.b` is a 0/1 module selector, NOT a module id: `getModuleId()` returns 231
                // for b==0 (MasterLive) and 167 for b==1, so the guard is b==0. (The previous
                // `b != 231` check could never fire and also the hook never installed.)
                val moduleIndex = bField?.let { runCatching { it.getInt(thisObj) }.getOrNull() }
                if (masterLiveB && moduleIndex != 0) return@before
                param.result = 36866
            }
        }
        DebugLog.d(TAG, "masterlive op-mode safety hook installed on ${clazz.name}#i()")
    }

    /**
     * Shutter-sound style-index bounds guard. `f2.c` (jadx `p180f2/c`) builds a 4-entry
     * shutter-sound style list (old/art/default/modern) because neither the native C1209 nor the
     * impersonated C1151 (both C1199) reports `F3()=true` (Leica entries skipped). Its raw getter
     * `a()` reads the stored `key_shutter_sound` (=4 from an old Leica-list migration) with NO
     * bounds check, so `MiuiCameraSound(D3)#g()` → `b().get(a())` throws
     * `IndexOutOfBoundsException: Index 4 out of bounds for length 4` on every shutter-sound
     * preload (CAM-Work) → RxJava onError (no handler) → FATAL → the camera cannot start under
     * impersonation. This hook clamps an out-of-range `a()` result back to `c()` — the getter
     * that already applies the `F3`-offset and bounds check (returns 0 when out of range).
     * Gated on the impersonation master; see RESEARCH_MYRON_06_IOOBE_ROOTCAUSE.md.
     */
    private fun hookShutterSoundBoundary() {
        val clazz = resolveClass("f2.c", "p180f2.c") ?: run {
            DebugLog.w(TAG, "f2.c (shutter sound config) not resolved; shutter-sound guard skipped")
            return
        }
        val aMethod = runCatching {
            clazz.getMethod("a").takeIf {
                it.parameterCount == 0 && it.returnType == java.lang.Integer.TYPE
            }
        }.getOrNull() ?: run {
            DebugLog.w(TAG, "f2.c#a() not found; shutter-sound guard skipped")
            return
        }
        val bMethod = runCatching {
            clazz.getMethod("b").takeIf { it.parameterCount == 0 }
        }.getOrNull()
        val cMethod = runCatching {
            clazz.getMethod("c").takeIf {
                it.parameterCount == 0 && it.returnType == java.lang.Integer.TYPE
            }
        }.getOrNull()

        deoptimize(aMethod)
        aMethod.hook("cam_shutter_sound_bounds") {
            after { param ->
                if (!enabled()) return@after
                val idx = (param.result as? Int) ?: return@after
                val size = bMethod?.let { runCatching { (it.invoke(null) as? List<*>)?.size }.getOrNull() }
                    ?: return@after
                if (idx < 0 || idx >= size) {
                    param.result = cMethod?.let { runCatching { it.invoke(null) }.getOrNull() } ?: 0
                }
            }
        }
        DebugLog.d(TAG, "shutter-sound bounds guard hooked on ${clazz.name}#a()")
    }

    private fun invokeBoolean(original: Any, name: String): Boolean =
        (originalDelegateMethods(original)[name]?.invoke(original) as? Boolean) ?: false

    /**
     * SOE-safe fallback used by the delegating before-callbacks when the original config getter
     * cannot be delegated (method missing / reflective invoke failed / returned null). Setting
     * `param.result` (never leaving `skipped` false) is what stops ezhooktool's `proceed` from
     * re-entering the still-hooked getter through `Method.invoke` (the 26611-frame SOE). The
     * value is return-type aware so a primitive-returning getter never yields a boxed null that
     * the camera would NPE on while unboxing; a genuinely-null object return is preserved.
     */
    private fun soeSafeFallback(method: Method, param: HookParam): Any? {
        val ret = method.returnType
        return when {
            ret == java.lang.Boolean.TYPE -> false
            ret == java.lang.Byte.TYPE -> 0.toByte()
            ret == java.lang.Short.TYPE -> 0.toShort()
            ret == java.lang.Integer.TYPE -> 0
            ret == java.lang.Long.TYPE -> 0L
            ret == java.lang.Float.TYPE -> 0f
            ret == java.lang.Double.TYPE -> 0.0
            ret == java.lang.Character.TYPE -> '\u0000'
            else -> param.result // preserve an already-set object value, else null
        }
    }

    /** Cached zero-arg reflection methods for every delegatable getter on the original config. */
    private fun originalDelegateMethods(original: Any): Map<String, Method> {
        originalDelegates.get()?.let { return it }
        synchronized(originalDelegates) {
            originalDelegates.get()?.let { return it }
            val resolved = ORIGINAL_DELEGATE_NAMES.associateWith { name ->
                runCatching { original.javaClass.getMethod(name) }
                    .getOrNull()
                    ?.takeIf { it.parameterCount == 0 }
            }.filterValues { it != null }.mapValues { it.value!! }
            originalDelegates.set(resolved)
            return resolved
        }
    }

    /**
     * Whether this device IS a real 17 Ultra (device base == nezha). On a real flagship the
     * guard/identity delegation is a no-op anyway, so the fast-path skips the reflective
     * delegation work entirely (these getters run on hot paths).
     */
    private fun deviceIsNezha(): Boolean {
        deviceIsNezhaCache.get()?.let { return it }
        synchronized(deviceIsNezhaCache) {
            deviceIsNezhaCache.get()?.let { return it }
            val isNezha = readDeviceBaseName()?.equals("nezha", ignoreCase = true) == true
            deviceIsNezhaCache.set(isNezha)
            return isNezha
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
     * 1. `com.mi.device.<Capitalize(Build.DEVICE | Je/a.c)>` — e.g. Myron
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
     * Device base name used to resolve the ORIGINAL (non-impersonated) config class. The app's own
     * factory `Je/e.q()` reads it from the `Je/a` static lazy field `c` (a `Pu.n` lazy whose value
     * is keyed off `Build.DEVICE`, `Je/d.a`); the jadx alias `f8410c` does not exist in the dex and
     * previously threw `NoSuchFieldException: No field f8410c in class LJe/a`. `Build.DEVICE` is
     * the same value (verified on the device: myron == ro.product.device), so it is used directly;
     * the reflective lazy read is kept only as a silent best-effort fallback for builds where
     * `Build.DEVICE` is blank.
     */
    private fun readDeviceBaseName(): String? {
        val fromBuild = Build.DEVICE.takeIf { it.isNotBlank() }
        val fromLazy = runCatching {
            val jeA = "Je.a".toClassOrNull() ?: return@runCatching null
            val field = resolveField(jeA, "c", "f8410c")?.apply { isAccessible = true }
                ?: return@runCatching null
            val lazy = field.get(null) ?: return@runCatching null
            val getValue = lazy.javaClass.getMethod("getValue")
            (getValue.invoke(lazy) as? String)?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        return fromBuild ?: fromLazy
    }

    // ─── gate helpers ─────────────────────────────────────────────────────────────

    private fun enabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE, false)

    /**
     * Watermark keep is UNCONDITIONAL: the on-picture watermark brand + model always stays on
     * this device's own values (or the user custom override), independent of the impersonation
     * master. Gating it on the master read made the very first watermark reads (before
     * `Preferences` is initialized in the camera process) return the impersonated flagship's
     * strings, which the `S8.d` watermark-config singleton cached — the "17 Ultra" watermark
     * shown right after capture. Unconditional also never shows a wrong model on devices that
     * cannot resolve a real market name.
     */
    private fun keepModel(): Boolean = true

    private fun keepFocal(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_KEEP_FOCAL, true)

    private fun keepImaging(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_KEEP_IMAGING, true)

    private fun guardModes(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_GUARD_MODES, true)

    private fun guardCameraId(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_GUARD_CAMERA_ID, false)

    private fun targetIsK100Promax(): Boolean =
        Preferences.getString(Preferences.KEY_CAMERA_IMPERSONATE_TARGET, TARGET_K100PROMAX) ==
            TARGET_K100PROMAX

    private fun targetIsNezha(): Boolean = !targetIsK100Promax()

    private fun streetEnable(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_STREET_ENABLE, true)

    private fun leicaStyle(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_LEICA_STYLE, true)

    private fun masterliveTeleFallback(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_TELE_FALLBACK, true)

    private fun opModeSafe(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_MASTERLIVE_OPMODE_SAFE, false)
}
