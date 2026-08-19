package com.takekazex.hypertweak.hook.rules.camera

import android.os.Build
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.StaticFieldWriter
import java.util.concurrent.atomic.AtomicReference

/**
 * Impersonates the camera app's per-device capability config as a flagship, so every
 * capability gate (mode visibility, MIVI pipeline switches, `instanceof`-based flagship
 * branches, ...) opens on any device — while leaving the **watermark device model** on this
 * device's own brand + model.
 *
 * Architecturally (reverse-engineering notes in
 * `cache/camera-5cd70925b1646cdf/CAMERA_UNLOCK_EVALUATION.md`):
 *
 * 1. The whole capability surface funnels through one object: `Je.c.b.f8427a.f8420e`,
 *    created by the single static factory `Je/e.q()` (`Class.forName("com.mi.device."+device)`).
 *    Hooking `Je/e#q` to return a flagship instance (`com.mi.device.Nezha`, the 17 Ultra
 *    config — loaded through the host's own name-rewrite wrapper `Uf.c.a`, so the obfuscated
 *    class name is resolved exactly as the app does) unlocks everything at once.
 * 2. The watermark brand/model strings come from `Je/c#x()` (=`v()[0]`, brand logo) and
 *    `Je/c#v()` (brand + model array). The EXIF `Model` tag is `ro.product.marketname`
 *    directly (`Je.d.f8434h`), never the capability config, so EXIF stays correct. Both
 *    hooks below force x()/v() back to THIS device's brand + market name, so the on-picture
 *    watermark keeps the local model ("keep model").
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

    private val flagshipInstance = AtomicReference<Any?>(null)

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        installHooks()
    }

    private fun installHooks() {
        hookConfigFactory()
        hookWatermarkKeep()
        hookLccTheme()
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

    // ─── 2. Keep this device's brand + model on the watermark ─────────────────────

    private fun hookWatermarkKeep() {
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
                param.result = keepBrand()
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
                param.result = arrayOf(keepBrand(), keepModelName())
            }
        }
        DebugLog.d(TAG, "watermark keep hooked on ${clazz.name}#x()/#v()")
    }

    // ─── 3. (Optional) fake the LCC theme so LCC-gated flagships branches open too ──

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

    // ─── gate helpers ─────────────────────────────────────────────────────────────

    private fun enabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_CAMERA_IMPERSONATE, false)

    private fun keepModel(): Boolean =
        enabled() && Preferences.getBoolean(Preferences.KEY_CAMERA_WM_KEEP_MODEL, true)

    /** Brand as the camera's classic-watermark logo wants it (XIAOMI / REDMI / POCO). */
    private fun keepBrand(): String {
        val brand = Build.BRAND
        return when (brand?.lowercase()) {
            "xiaomi", "redmi", "poco" -> brand.uppercase()
            else -> brand?.uppercase() ?: "XIAOMI"
        }
    }

    /** This device's real market name (same source as the EXIF `Model` tag). */
    private fun keepModelName(): String {
        return runCatching {
            val cls = "Je.d".toClassOrNull() ?: return@runCatching null
            val field = runCatching { cls.getDeclaredField("f8434h") }.getOrNull()
                ?: return@runCatching null
            field.isAccessible = true
            (field.get(null) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: Build.MODEL
    }
}
