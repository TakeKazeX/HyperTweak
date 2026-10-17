package com.takekazex.hypertweak.hook.rules.systemui.island

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.util.DebugLog
import java.io.File
import java.lang.reflect.Field
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses the font resource and axes declared by the native narrowFont renderer. */
class IslandTimeFontHooker : DynamicHooker() {
    private data class Font(val typeface: Typeface?, val variation: String?) {
        fun matches(view: TextView) = typeface === view.typeface && variation == view.fontVariationSettings
        fun restore(view: TextView) {
            view.typeface = typeface
            view.fontVariationSettings = variation
        }
    }
    private data class Lease(val original: Font, val applied: Font)
    private data class Slot(val field: Field, val numeric: Boolean)
    private val main = Handler(Looper.getMainLooper())
    private val holders = WeakHashMap<Any, Boolean>()
    private val islandOwners = WeakHashMap<Any, Boolean>()
    private var discover: ((List<Any>) -> List<Any>)? = null
    private val narrowGlyphs = WeakHashMap<TextView, Boolean>()
    private val leases = WeakHashMap<TextView, Lease>()
    @Volatile private var retiring = false
    private var update: ((Any) -> Unit)? = null

    fun snapshotHolders(): List<Any> = synchronized(holders) { holders.keys.toList() }
    fun restoreHolders(values: List<Any>) {
        synchronized(holders) { values.forEach { holders[it] = true } }
        refreshSettings()
    }
    fun restoreIslandOwners(values: List<Any>) {
        synchronized(islandOwners) { values.forEach { islandOwners[it] = true } }
        refreshSettings()
    }
    private val refresh = Runnable {
        if (!retiring) {
            HookFailurePolicy.open(TAG, "recover existing timer holders", Unit) {
                val owners = synchronized(islandOwners) { islandOwners.keys.toList() }
                val existing = discover?.invoke(owners).orEmpty()
                synchronized(holders) { existing.forEach { holders[it] = true } }
            }
            val existing = snapshotHolders()
            existing.forEach { holder ->
                HookFailurePolicy.open(TAG, "refresh timer font", Unit) { update?.invoke(holder) }
            }
            DebugLog.i(TAG, "settings applied enabled=${Preferences.getBoolean(Preferences.KEY_ISLAND_NARROW_TIME_FONT, false)} holders=${existing.size} narrowViews=${leases.size}")
        }
    }
    fun refreshSettings() {
        main.removeCallbacks(refresh)
        if (!retiring) main.post(refresh)
    }

    override fun onHook() {
        val base = "miui.systemui.dynamicisland.module.BaseIslandModuleViewHolder".toClassOrNull() ?: return
        val template = classLoader.loadClass("miui.systemui.dynamicisland.model.IslandTemplate")
        val data = classLoader.loadClass("com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData")
        val currentData = base.getMethod("getDynamicIslandData")
        check(currentData.returnType == data)
        val extras = data.getMethod("getExtras")
        check(extras.returnType == Bundle::class.java)
        val fixedFont = base.getDeclaredMethod("setFixedFont", TextView::class.java)
        val source = IslandCondensedFontSource.resolve(File("/product/etc/mi_fonts_customization.xml"), File("/product/fonts"))
        val narrow = requireNotNull(Typeface.Builder(source)
            .setFontVariationSettings(IslandCondensedFontSource.AXES).build()) { "native condensed font could not load" }
        DebugLog.i(TAG, "native font source=$source axes=${IslandCondensedFontSource.AXES} namedFamilyFallsBack=${Typeface.create(IslandCondensedFontSource.FAMILY, Typeface.NORMAL) == Typeface.DEFAULT}")
        // These named Kotlin model/holder APIs are checked as a complete typed contract.
        // Numeric holders also cover blank chronometers before their first tick and paused digits.
        val slots = listOf(
            "IslandSameWidthDigitViewHolder" to mapOf("sameWidthDigit" to true, "title" to true),
            "IslandFixedWidthDigitViewHolder" to mapOf("fixedWidthDigit" to true),
            "IslandIconFixedWidthTextHolder" to mapOf("title" to false),
            "IslandTextViewHolder" to mapOf("title" to false, "frontTitle" to false, "content" to false),
            "IslandRightTextViewHolder" to mapOf("title" to false, "frontTitle" to false, "content" to false),
            "IslandTextOverIconHolder" to mapOf("title" to false)
        ).associate { (name, fields) ->
            val owner = classLoader.loadClass("miui.systemui.dynamicisland.module.$name")
            check(base.isAssignableFrom(owner))
            owner to fields.map { (field, numeric) ->
                Slot(owner.getDeclaredField(field).apply {
                    isAccessible = true
                    check(TextView::class.java.isAssignableFrom(type))
                }, numeric)
            }
        }
        // Hot reload does not rerun bind on an already visible island. Recover those holders
        // from the native factory's four builder maps, including its fake measurement views.
        val coordinator = classLoader.loadClass("miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator")
        val getWindow = coordinator.getMethod("getWindowView")
        val getContents = getWindow.returnType.getMethod("getContentViews")
        val content = classLoader.loadClass("miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView")
        val getController = content.getMethod("getController")
        val getFactory = getController.returnType.getMethod("getIslandTemplateFactory")
        val factory = classLoader.loadClass("miui.systemui.dynamicisland.template.IslandTemplateFactory")
        check(getFactory.returnType == factory)
        val builder = classLoader.loadClass("miui.systemui.dynamicisland.template.IslandTemplateBuilder")
        val adapter = classLoader.loadClass("miui.systemui.dynamicisland.module.IslandModuleViewHolderAdapter")
        val builders = listOf("bigIslandBuilderMap", "smallIslandBuilderMap", "fakeBigIslandBuilderMap", "fakeSmallIslandBuilderMap")
            .map { name -> factory.getDeclaredField(name).apply {
                isAccessible = true
                check(Map::class.java.isAssignableFrom(type))
            } }
        val getAdapter = builder.declaredFields.single { it.type == adapter }.apply { isAccessible = true }
        val nativeHolders = adapter.getDeclaredField("holders").apply {
            isAccessible = true
            check(Map::class.java.isAssignableFrom(type))
        }
        discover = { owners ->
            val factories = owners.filter(coordinator::isInstance).flatMap { owner ->
                val window = getWindow.invoke(owner)
                (getContents.invoke(window) as? List<*>)?.filterNotNull().orEmpty()
            }.filter(content::isInstance).mapNotNull { view ->
                getController.invoke(view)?.let { getFactory.invoke(it) }
            }.distinct()
            factories.flatMap { owner ->
                builders.flatMap { field ->
                    (field.get(owner) as? Map<*, *>)?.values?.toList().orEmpty()
                }.filter(builder::isInstance).flatMap { item ->
                    val moduleAdapter = getAdapter.get(item)
                    (nativeHolders.get(moduleAdapter) as? Map<*, *>)?.values?.filterNotNull().orEmpty()
                }
            }.filter { it.javaClass in slots }.distinct()
        }
        val binds = slots.keys.map { it.getDeclaredMethod("bind", template, data) }
        fun holderSlots(holder: Any) = slots[holder.javaClass].orEmpty()
        fun apply(holder: Any, view: TextView, slot: Slot) {
            val packageName = (currentData.invoke(holder)?.let { extras.invoke(it) } as? Bundle)
                ?.getString("miui.pkg.name")
            if (retiring || !Preferences.getBoolean(Preferences.KEY_ISLAND_NARROW_TIME_FONT, false) ||
                !IslandTimeFontPolicy.eligible(packageName, slot.numeric, view.text)) {
                release(view)
                return
            }
            val previous = leases[view]
            if (previous?.applied?.matches(view) == true) return
            leases.remove(view)
            val original = Font(view.typeface, view.fontVariationSettings)
            try {
                val textSize = view.textSize
                view.typeface = narrow
                check(view.setFontVariationSettings(IslandCondensedFontSource.AXES)) { "native condensed axes rejected" }
                check(view.textSize == textSize) { "timer font changed text size" }
                leases[view] = Lease(original, Font(view.typeface, view.fontVariationSettings))
                if (narrowGlyphs[view] != true) {
                    refreshGlyphs(view)
                    narrowGlyphs[view] = true
                    DebugLog.d(TAG, "glyphs rebound widget=${view.javaClass.name} textSize=$textSize axes=${view.fontVariationSettings}")
                }
                IslandLayoutInvalidation.request(view)
            } catch (failure: Throwable) {
                leases.remove(view)
                narrowGlyphs.remove(view)
                original.restore(view)
                throw failure
            }
        }
        fun applyHolder(holder: Any) {
            holderSlots(holder).forEach { slot ->
                (slot.field.get(holder) as? TextView)?.let { apply(holder, it, slot) }
            }
        }
        update = ::applyHolder
        // Native updateWidth reapplies FIXED_FONT before measuring. Override at that boundary,
        // so its measurement uses the same font as the real/fake rendered timer.
        deoptimize(fixedFont)
        fixedFont.hook {
            before { param ->
                if (Looper.myLooper() == main.looper) HookFailurePolicy.open(TAG, "release fixed font", Unit) {
                    (param.args.firstOrNull() as? TextView)?.let { release(it, resetGlyphs = false) }
                }
            }
            after { param ->
                if (retiring || Looper.myLooper() != main.looper) return@after
                HookFailurePolicy.open(TAG, "native timer font", Unit) {
                    val view = param.args.firstOrNull() as? TextView ?: return@open
                    val slot = holderSlots(param.thisObject).singleOrNull { it.field.get(param.thisObject) === view }
                        ?: return@open
                    apply(param.thisObject, view, slot)
                }
            }
        }
        binds.forEach { bind ->
            deoptimize(bind)
            bind.hook {
                before { param ->
                    if (Looper.myLooper() != main.looper) return@before
                    HookFailurePolicy.open(TAG, "release previous timer font", Unit) {
                        holderSlots(param.thisObject).forEach { slot ->
                            (slot.field.get(param.thisObject) as? TextView)?.let { release(it, resetGlyphs = false) }
                        }
                    }
                }
                after { param ->
                    if (retiring) return@after
                    val holder = param.thisObject
                    synchronized(holders) { holders[holder] = true }
                    val action = Runnable {
                        if (!retiring) HookFailurePolicy.open(TAG, "bind timer font", Unit) { applyHolder(holder) }
                    }
                    if (Looper.myLooper() == main.looper) action.run() else main.post(action)
                }
            }
        }
        slots.keys.forEach { owner ->
            owner.declaredMethods.filter { it.name == "updatePartial" || it.name == "updateWidth" }
                .forEach { method ->
                    deoptimize(method)
                    if (method.name == "updatePartial") method.hook {
                        after { param ->
                            if (!retiring && Looper.myLooper() == main.looper) {
                                HookFailurePolicy.open(TAG, "update timer font", Unit) { applyHolder(param.thisObject) }
                            }
                        }
                    }
                }
        }
        DebugLog.hookRegistered(TAG, "native Clock/Recorder condensed timer digits")
    }

    private fun refreshGlyphs(view: TextView) {
        // Native setText clears effect-span paints/bitmaps even when the content is unchanged.
        // Keep the timer's base, running state, value, colors and animation-enable flag intact.
        view.text = view.text
        view.invalidate()
    }

    private fun release(view: TextView, resetGlyphs: Boolean = true) {
        val lease = leases.remove(view)
        if (lease?.applied?.matches(view) == true) {
            lease.original.restore(view)
            IslandLayoutInvalidation.request(view)
        }
        if (resetGlyphs && narrowGlyphs.remove(view) == true) refreshGlyphs(view)
    }

    override fun saveHotReloadState(): Any = listOf(snapshotHolders(),
        synchronized(islandOwners) { islandOwners.keys.toList() })
    override fun restoreHotReloadState(state: Any?) {
        val values = state as? List<*> ?: return
        restoreHolders((values.getOrNull(0) as? List<*>)?.filterNotNull().orEmpty())
        restoreIslandOwners((values.getOrNull(1) as? List<*>)?.filterNotNull().orEmpty())
    }

    override fun onPrepareHotReload() {
        retiring = true
        main.removeCallbacksAndMessages(null)
        val cleanup = Runnable {
            (leases.keys.toList() + narrowGlyphs.keys.toList()).distinct().forEach { release(it) }
            synchronized(holders) { holders.clear() }
            synchronized(islandOwners) { islandOwners.clear() }
            discover = null
            update = null
        }
        if (Looper.myLooper() == main.looper) cleanup.run() else {
            val done = CountDownLatch(1)
            main.post { try { cleanup.run() } finally { done.countDown() } }
            check(done.await(5, TimeUnit.SECONDS)) { "island timer font restoration timed out" }
        }
    }

    private companion object { const val TAG = "IslandTimeFont" }
}
