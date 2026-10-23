package com.takekazex.hypertweak.hook.rules.securitycenter

import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.ThreadActivation
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

internal object SecurityCenterDockHost {
    const val PACKAGE = "com.miui.securitycenter"
    const val PROCESS = "$PACKAGE:ui"
}

private object SecurityCenterDockFeature : DexFeature {
    override val id = "securitycenter.dock"
    override val schema = 3
}

internal enum class DockItemKind { PINNED, PREDICTION, DIVIDER }

internal data class SecurityCenterDockSettings(
    val unlimitedPinned: Boolean = false,
    val unlimitedPredictions: Boolean = false,
    val predictions: Int = 8,
) {
    val budget get() = predictions.coerceIn(0, 15)
    fun slots(pinned: Int): Int = DockEditCapacity.slots(pinned, unlimitedPinned)
    fun <T> editState(native: T, blocked: T, allowed: T): T = if (unlimitedPinned && native == blocked) allowed else native
    fun <T> display(items: List<T>, kind: (T) -> DockItemKind): List<T> {
        val classified = items.map { it to kind(it) }
        val fixed = classified.count { it.second == DockItemKind.PINNED }
        val available = if (unlimitedPredictions) budget else minOf(budget, (16 - fixed).coerceAtLeast(0))
        var predictions = 0
        return classified.filter { (_, type) -> type != DockItemKind.PREDICTION || predictions++ < available }.map { it.first }
    }
}

/** Reserve real adapter destinations before native flying animations record their indices. */
internal object DockEditCapacity {
    fun slots(pinned: Int, unlimited: Boolean, pending: Int = 0, incoming: Int = 0): Int =
        maxOf(16, pinned + pending + incoming + if (unlimited) 1 else 0)

    fun <T> reserve(items: List<T>, pending: Int, incoming: Int,
                    isEditable: (T) -> Boolean, append: () -> Unit): Int {
        val needed = slots(items.count(isEditable), true, pending, incoming)
        while (items.size < needed) {
            val before = items.size
            append()
            check(items.size == before + 1) { "native adapter did not append one placeholder" }
        }
        return maxOf(needed, items.size)
    }
}

/** One budget shared by prediction and quick-reply passes; unavailable pinned apps cannot inflate it. */
internal class DockPredictionBudget(private val limit: Int) {
    private var initialSize: Int? = null
    fun permits(size: Int): Boolean {
        val base = initialSize ?: size.also { initialSize = it }
        return size - base < limit
    }
}

/** Quota changes keep the vendor model/filter/clone paths and never write global_dock_apps. */
object SecurityCenterDockHooker : DynamicHooker() {
    private const val TAG = "SecurityCenterDock"
    @Volatile private var retired = false
    @Volatile private var settings = SecurityCenterDockSettings()
    private val main = Handler(Looper.getMainLooper())
    private val layouts = WeakHashMap<View, Boolean>()
    private val editorHolders = WeakHashMap<Any, Boolean>()
    private var targets: Targets? = null
    @Volatile private var appliedSettings: SecurityCenterDockSettings? = null
    private var pendingSettings: SecurityCenterDockSettings? = null
    private val refreshGeneration = java.util.concurrent.atomic.AtomicLong()
    private class LoadFrame(val settings: SecurityCenterDockSettings) {
        val budget = DockPredictionBudget(settings.budget)
    }
    private val loadFrame = ThreadLocal<LoadFrame>()
    private val editorReadScope = ThreadActivation()

    private class Targets(val methods: Map<String, Method>, val fields: Map<String, Field>, val model: Class<*>, val divider: Class<*>, val placeholder: Class<*>) {
        val placeholderConstructor = placeholder.getDeclaredConstructor().apply { isAccessible = true }
        val editable = methods.getValue("editCopy").declaringClass
        val pinnable = requireNotNull(fields.getValue("pinnable").get(null))
        val unpinnable = requireNotNull(fields.getValue("unpinnable").get(null))
        val constructor = model.getDeclaredConstructor(String::class.java, String::class.java, Integer.TYPE, Integer.TYPE).apply { isAccessible = true }
        fun call(role: String, receiver: Any? = null, vararg args: Any?): Any? = methods.getValue(role).invoke(receiver, *args)
        fun read(role: String, receiver: Any): Any? = fields.getValue(role).get(receiver)
    }

    override fun onHook() {
        if (hookParam.packageName != SecurityCenterDockHost.PACKAGE || hookParam.processName != SecurityCenterDockHost.PROCESS) return
        retired = false
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(name = SecurityCenterDockFeature.id, resolve = {
            DexRuntime.resolve(feature = SecurityCenterDockFeature, apkPath = apk, cacheDir = dexCacheDirectory,
                compute = { SecurityCenterDockResolver(CameraDexIndex.open(apk)).resolve() },
                decode = { values, _ -> decode(values) })
        }, install = ::install)
    }

    private fun decode(values: Map<String, String>): Targets? = runCatching {
        val methods = values.filterKeys { it.startsWith("m.") }.mapKeys { it.key.removePrefix("m.") }
            .mapValues { requireNotNull(MethodDescriptorCodec.method(it.value, classLoader)) }
        val fields = values.filterKeys { it.startsWith("f.") }.mapKeys { it.key.removePrefix("f.") }
            .mapValues { requireNotNull(MethodDescriptorCodec.field(it.value, classLoader)) }
        check(methods.keys.containsAll(listOf("append", "load", "reload", "instance", "data", "fill", "drag", "dragCount", "bind", "update", "publish", "notify", "refresh",
            "resolve", "resolveUser", "cloned", "alias", "canonical", "packageInfo", "user", "heightData", "itemModel", "editCopy", "appendSlot", "adapterData",
            "editState0", "editState1", "editStateBase", "holderBind", "holderUpdate", "editorTransition", "editorPinned", "editorMap0", "editorMap1", "editorMap2")))
        check(fields.keys.containsAll(listOf("apps", "pinned", "packageService", "pm", "packages", "layoutData", "modelKind", "holderModel", "childHolder", "pinnable", "unpinnable", "dragAdapter", "layoutAdapter", "layoutEditing", "layoutController")))
        val scopeCount = values.getValue("editorReadScopeCount").toInt()
        check(scopeCount in 4..32 && (0 until scopeCount).all { "editorReadScope$it" in methods })
        values.getValue("callers").lineSequence().filter(String::isNotBlank).forEach {
            deoptimize(requireNotNull(MethodDescriptorCodec.method(it, classLoader)))
        }
        Targets(methods, fields, CameraDexIndex.type(values.getValue("model"), classLoader), CameraDexIndex.type(values.getValue("divider"), classLoader), CameraDexIndex.type(values.getValue("placeholder"), classLoader))
    }.onFailure { DebugLog.w(TAG, "dock contract unavailable; preserving native behavior", it) }.getOrNull()

    private fun install(t: Targets) {
        // Resolve the complete contract before registering any of the three coupled hooks.
        settings = readSettings()
        t.methods.getValue("load").hook {
            intercept { chain ->
                val previous = loadFrame.get()
                loadFrame.set(LoadFrame(settings))
                try { chain.proceed() }
                finally { if (previous == null) loadFrame.remove() else loadFrame.set(previous) }
            }
        }
        t.methods.getValue("append").hook {
            before { param ->
                val frame = loadFrame.get() ?: LoadFrame(settings)
                if (!retired) {
                    val host = param.thisObject
                    // Replaces only the quota-bearing loop. All host helper calls/constructors are cached.
                    // No original fallback after mutation: replaying it could duplicate partial work.
                    synchronized(host) {
                        try { append(t, host, param.args, frame.budget) }
                        catch (error: Throwable) { DebugLog.e(TAG, "prediction append failed", error) }
                    }
                    param.result = null
                }
            }
        }
        t.methods.getValue("fill").hook {
            before { param ->
                if (!retired) {
                    val list = param.args[0] as? List<*> ?: return@before
                    runCatching {
                        // Normal input also contains predictions, notes and a divider. Only fixed models
                        // consume editing slots; disabling must retain pins already beyond the old limit.
                        val pinned = list.count { t.editable.isInstance(it) && classify(t, it) == DockItemKind.PINNED }
                        maxOf(param.args[1] as Int, settings.slots(pinned))
                    }.onSuccess { param.args[1] = it }
                        .onFailure { DebugLog.w(TAG, "editing capacity classification failed", it) }
                }
            }
        }
        t.methods.getValue("drag").hook {
            before { param ->
                if (!retired && settings.unlimitedPinned) {
                    val list = param.args[0] as? List<*> ?: return@before
                    runCatching {
                        val controller = param.args[1] ?: error("missing native edit controller")
                        val adapter = requireNotNull(t.read("dragAdapter", controller))
                        check(t.call("adapterData", adapter) === list) { "drag list does not belong to native adapter" }
                        val pending = t.call("dragCount", controller) as Int
                        // Changing only the numeric limit is insufficient: native add uses List.set(index).
                        // Include every in-flight animation and this incoming app, then leave a spare slot.
                        reserveSlots(t, adapter, list, pending, incoming = 1)
                    }.onSuccess { param.args[3] = it }
                        .onFailure { DebugLog.w(TAG, "native add capacity reservation failed", it) }
                }
            }
        }
        // Restrict interpretation to the verified plus renderer, its predicates and click dispatch.
        // Model equality/hash/copy and all native state records retain their original enum value.
        val editorScopes = t.methods.filterKeys { it.startsWith("editorReadScope") }.values.toSet()
        val bindings = listOf("holderBind", "holderUpdate").map { t.methods.getValue(it) }.toSet()
        (editorScopes + bindings).forEach { method ->
            // One managed handle per executable, including the update method shared by both roles.
            method.hook {
                if (method in editorScopes) intercept { chain ->
                    if (!retired && settings.unlimitedPinned) editorReadScope.within { chain.proceed() }
                    else chain.proceed()
                }
                if (method in bindings) after { param ->
                    if (!retired) synchronized(editorHolders) { editorHolders[param.thisObject] = true }
                }
            }
        }
        listOf("editState0", "editState1").forEach { role ->
            t.methods.getValue(role).hook {
                after { param ->
                    if (!retired && editorReadScope.active) param.result = settings.editState(param.result, t.unpinnable, t.pinnable)
                }
            }
        }
        // Both normal presentation and its height calculation use the same independent prediction policy.
        listOf("data", "heightData").forEach { role ->
            t.methods.getValue(role).hook {
                after { param ->
                    if (!retired) {
                        val list = param.result as? List<*> ?: return@after
                        runCatching { settings.display(list) { classify(t, it) } }
                            .onSuccess { param.result = it }
                            .onFailure { DebugLog.w(TAG, "prediction display classification failed", it) }
                    }
                }
            }
        }
        val layoutType = t.methods.getValue("bind").declaringClass
        layoutType.hookAllConstructors {
            after { param -> (param.thisObject as? View)?.let { synchronized(layouts) { layouts[it] = true } } }
        }
        t.methods.getValue("bind").hook {
            after { param -> (param.thisObject as? View)?.let { synchronized(layouts) { layouts[it] = true } } }
        }
        // The base edit-state getter is an abstract contract; only concrete bodies can be
        // inlined. Its App/Shortcut implementations and their callers are deoptimized here.
        t.methods.values.filterNot { Modifier.isAbstract(it.modifiers) }.forEach(::deoptimize)
        targets = t
        refreshSettings()
        DebugLog.i(TAG, "native dock quota and edit capacity hooks installed")
    }

    @Suppress("UNCHECKED_CAST")
    private fun append(t: Targets, host: Any, args: Array<out Any?>, budget: DockPredictionBudget) {
        val output = t.read("apps", host) as? MutableList<Any> ?: return
        if (!budget.permits(output.size)) return
        val sources = args[4] as? List<*> ?: return
        val exclusions = (1..3).map { args[it] as? List<*> ?: return }
        val kind = args[5] as? Int ?: return
        val ctx = args[0] ?: return
        val pm = t.read("pm", host)
        val packageService = t.read("packageService", host)
        val packages = t.read("packages", host)
        fun add(info: ResolveInfo?, pkg: String) {
            if (!budget.permits(output.size)) return
            val activity = info?.activityInfo ?: return
            val app = activity.applicationInfo ?: return
            val model = t.constructor.newInstance(pkg, activity.name, app.uid, kind)
            if (!output.contains(model)) output.add(model)
        }
        for (entry in sources) {
            if (!budget.permits(output.size)) break
            var pkg = entry as? String ?: continue
            if (pkg.isEmpty()) continue
            if (!exclusions[0].contains(pkg)) add(t.call("resolve", null, ctx, pkg, pm) as? ResolveInfo, pkg)
            val cloned = t.call("cloned", null, packageService, pkg) == true
            val alias = kind == 1 && t.call("alias", null, pkg) == true
            if (alias) pkg = t.call("canonical", null, pkg) as? String ?: continue
            if ((cloned || alias) && !exclusions[1].contains(pkg)) {
                add(t.call("resolveUser", null, ctx, pkg, pm, 999) as? ResolveInfo, pkg)
            }
            val info = t.call("packageInfo", null, pkg, packages) as? PackageInfo
            val app = info?.applicationInfo
            if (app != null && !exclusions[2].contains(pkg)) {
                val user = t.call("user", null, app.uid) as? Int ?: continue
                add(t.call("resolveUser", null, ctx, pkg, pm, user) as? ResolveInfo, pkg)
            }
        }
    }

    private fun reserveSlots(t: Targets, adapter: Any, list: List<*>, pending: Int, incoming: Int): Int =
        DockEditCapacity.reserve(list, pending, incoming, t.editable::isInstance) {
            t.call("appendSlot", adapter, t.placeholderConstructor.newInstance())
        }

    private fun refreshLayout(t: Targets, view: View, data: List<*>) {
        if (t.read("layoutEditing", view) == true) {
            // The native adapter owns unconfirmed additions/removals and reserved animation indices.
            // F/Q reconstruct from persisted state and would discard those edits or end edit styling.
            if (settings.unlimitedPinned) {
                val adapter = requireNotNull(t.read("layoutAdapter", view))
                val list = requireNotNull(t.call("adapterData", adapter) as? List<*>)
                val controller = t.read("layoutController", view)
                val pending = if (controller == null) 0 else t.call("dragCount", controller) as Int
                reserveSlots(t, adapter, list, pending, incoming = 0)
            }
        } else {
            t.call("bind", view, data) // F already binds; Q would schedule another unfenced fetch.
        }
    }

    private fun classify(t: Targets, item: Any?): DockItemKind {
        if (item != null && t.divider.isInstance(item)) return DockItemKind.DIVIDER
        val getter = t.methods.getValue("itemModel")
        if (item == null || !getter.declaringClass.isInstance(item)) return DockItemKind.PINNED
        val model = getter.invoke(item) ?: return DockItemKind.PINNED
        return if (t.read("modelKind", model) == 0) DockItemKind.PINNED else DockItemKind.PREDICTION
    }

    private fun readSettings() = SecurityCenterDockSettings(
        Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PINNED, false),
        Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PREDICTIONS, false),
        Preferences.getInt(Preferences.KEY_SECURITY_CENTER_DOCK_PREDICT_LIMIT, 8).coerceIn(0, 15))

    /** Rebuild even on default/first install: a preceding generation may have no saved feature state. */
    @Synchronized fun refreshSettings(): Boolean {
        if (retired) return true
        Preferences.invalidateRuntimeReadCache()
        val next = readSettings()
        if (next != settings) { appliedSettings = null; pendingSettings = null }
        settings = next
        val t = targets ?: return false
        val snapshot = settings
        if (appliedSettings == snapshot) return true
        if (pendingSettings == snapshot) return false
        pendingSettings = snapshot
        val generation = refreshGeneration.incrementAndGet()
        // Serialize native rebuilds off the UI thread.
        refreshWorker.execute {
            if (retired || generation != refreshGeneration.get()) return@execute
            runCatching {
                val host = requireNotNull(t.call("instance")) { "dock singleton unavailable" }
                t.call("reload", host)
                t.call("refresh")
                val data = requireNotNull(t.call("data", host) as? List<*>) { "dock models unavailable" }
                check(main.post {
                    if (retired || generation != refreshGeneration.get()) return@post
                    runCatching {
                        recoverLayouts(t)
                        synchronized(layouts) { layouts.keys.toList() }.forEach { view -> refreshLayout(t, view, data) }
                        synchronized(editorHolders) { editorHolders.keys.toList() }.forEach { holder ->
                            val model = t.read("holderModel", holder) ?: return@forEach
                            t.call("holderUpdate", holder, model)
                        }
                        appliedSettings = snapshot
                    }.onFailure {
                        synchronized(this) { if (generation == refreshGeneration.get()) pendingSettings = null }
                        DebugLog.w(TAG, "living dock repaint failed", it)
                    }
                }) { "dock UI queue rejected refresh" }
            }.onFailure {
                synchronized(this) { if (generation == refreshGeneration.get()) pendingSettings = null }
                DebugLog.w(TAG, "dock rebuild failed", it)
            }
        }
        return false
    }

    private val refreshWorker = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(1),
        { task -> Thread(task, "HyperTweak-DockRefresh").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    private fun recoverLayouts(t: Targets) {
        // Includes service windows born before this feature/module generation was installed.
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getDeclaredMethod("getInstance").invoke(null)
        val roots = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as? List<*> ?: return
        val type = t.methods.getValue("bind").declaringClass
        fun visit(view: View) {
            if (type.isInstance(view)) synchronized(layouts) { layouts[view] = true }
            val params = view.layoutParams
            val holderField = t.fields.getValue("childHolder")
            if (params != null && holderField.declaringClass.isInstance(params)) {
                val holder = holderField.get(params)
                if (holder != null && t.methods.getValue("holderUpdate").declaringClass.isInstance(holder)) {
                    synchronized(editorHolders) { editorHolders[holder] = true }
                }
            }
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        roots.toList().filterIsInstance<View>().forEach(::visit)
    }

    override fun saveHotReloadState(): Any = arrayOf(
        synchronized(layouts) { layouts.keys.toTypedArray() },
        synchronized(editorHolders) { editorHolders.keys.toTypedArray() },
    )
    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? Array<*> ?: return
        val views = (saved.getOrNull(0) as? Array<*>) ?: saved // beta-551 carried only Views.
        views.filterIsInstance<View>().forEach { synchronized(layouts) { layouts[it] = true } }
        (saved.getOrNull(1) as? Array<*>)?.filterNotNull()?.forEach { holder ->
            if (targets?.methods?.get("holderUpdate")?.declaringClass?.isInstance(holder) == true) {
                synchronized(editorHolders) { editorHolders[holder] = true }
            }
        }
    }
    override fun recoverHotReloadHosts() { appliedSettings = null; refreshSettings() }
    override fun onPrepareHotReload() {
        retired = true
        refreshWorker.shutdownNow()
        check(refreshWorker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) { "dock refresh retirement timed out" }
        synchronized(layouts) { layouts.clear() }
        synchronized(editorHolders) { editorHolders.clear() }
    }
}
