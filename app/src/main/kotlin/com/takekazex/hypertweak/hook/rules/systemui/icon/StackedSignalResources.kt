package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.SparseIntArray
import com.takekazex.hypertweak.util.DebugLog

/**
 * Drawable injection for the stacked signal, ported from Flux Decor 2.0.3's `ModuleResourceHooks`
 * + `StatusBarDualSimSignalHook.registerSignalDrawables` (see FLUX_DECOR_STACKED_SIGNAL_PLAN.md
 * §2.4 / §4.1).
 *
 * Module vector drawables (`statusbar_signal_{row}_{level}(_dark|_tint)`) are registered under fake
 * resource ids in the 0x7E package range that SystemUI resources never use. The hooker redirects
 * `Resources.getDrawable`/`getDrawableForDensity` for fake ids to the module's own drawables, and
 * the light/dark/tint variants are wired into `com.miui.systemui.statusbar.Icons`' static maps
 * (keyed by fake id), so SystemUI's own `MiuiStatusBarIconViewHelper.transformResId` picks the
 * variant and the whole dark/light/tint chain is reused unchanged.
 *
 * This object only keeps state and resolves names; the actual hook installation lives in
 * [StackedSignalHooker] (it owns the hook DSL).
 */
object StackedSignalResources {
    private const val TAG = "IconTuner"
    private const val MODULE_PACKAGE = "com.takekazex.hypertweak"

    /** Base package id for fake drawable ids (0x7E000000), same scheme as upstream. */
    private const val FAKE_ID_BASE = 0x7E000000

    /** Fixed id for the synthetic sub-SIM ImageView; 0x7F is outside every real resource id. */
    const val SUB_MOBILE_ID = 0x7F000001

    /** Fixed view-tag key marking a view as force-hidden by the stacked signal feature. */
    const val TAG_FORCE_GONE = 0x7F000002

    private val fakes = SparseIntArray()

    @Volatile
    private var moduleContext: Context? = null

    @Volatile
    private var registered = false

    /** The module's own context (module resources). Set from SystemUI's package-ready callback. */
    fun setModuleContext(context: Context) {
        moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()
    }

    fun moduleContext(): Context? = moduleContext

    fun isReady(): Boolean = moduleContext != null

    fun isRegistered(): Boolean = registered

    fun fakeId(name: String): Int = (name.hashCode() and 0xFFFFFF) or FAKE_ID_BASE

    fun isFakeId(id: Int): Boolean = (id and 0xFF000000.toInt()) == FAKE_ID_BASE

    fun has(name: String): Boolean = fakes.indexOfKey(fakeId(name)) >= 0

    /** Module drawable id registered for a fake id, or 0. */
    fun resolveFake(fakeId: Int): Int = fakes.get(fakeId, 0)

    /** Loads a module drawable directly, avoiding fake-id resolution in SystemUI Resources. */
    fun drawable(name: String): Drawable? {
        val ctx = moduleContext ?: return null
        val id = ctx.resources.getIdentifier(name, "drawable", MODULE_PACKAGE)
        if (id == 0) return null
        return runCatching { ctx.resources.getDrawable(id, null) }.getOrNull()
    }

    /**
     * Registers the stacked-signal drawables for [styleSuffix] (e.g. "" or "_ios27") from the
     * module resources. Idempotent per registration; call again after a style change.
     */
    fun register(styleSuffix: String, @Suppress("UNUSED_PARAMETER") hostClassLoader: ClassLoader) {
        val ctx = moduleContext ?: return
        val res = ctx.resources
        if (registered) return
        registered = true

        var count = 0
        for (row in 1..2) {
            for (level in 0..5) {
                val base = "statusbar_signal_${row}_$level$styleSuffix"
                val moduleId = res.getIdentifier(base, "drawable", MODULE_PACKAGE)
                if (moduleId == 0) continue
                val fake = fakeId(base)
                fakes.put(fake, moduleId)
                count++
            }
        }
        DebugLog.i(TAG, "StackedSignalResources registered $count drawables (style='$styleSuffix')")
    }

    /** Resets registration state so a style change (or hot reload) can re-register. */
    fun resetForReload() {
        registered = false
        fakes.clear()
    }
}
