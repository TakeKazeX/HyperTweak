package com.takekazex.hypertweak.hook.rules.intentresolver

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Bypass only the grouped-channel overflow gate; never mutate the user's native expansion flag. */
object SharePanelHooker : DynamicHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingSharePanelShowAll by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_SHARE_PANEL_SHOW_ALL, false)
    }

    private const val TAG = "SharePanel"
    private const val PACKAGE = "com.android.intentresolver"
    private val grids = generationMainResource(WeakHashMap<Any, Boolean>()) { it.clear() }
    @Volatile
    private var retired = false
    init { generationCleanup { retired = true } }
    private var targets: Targets? by generationState { null }

    private data class Targets(
        val gate: Method,
        val gridField: Field,
        val gridType: Class<*>,
        val recyclerType: Class<*>,
        val adapterField: Field,
        val notifyChanged: Method,
        val activityType: Class<*>,
    )

    override fun onHook() {
        if (hookParam.packageName != PACKAGE || hookParam.processName !in setOf(PACKAGE, "$PACKAGE:chooser")) return
        retired = false
        val resolved = HookFailurePolicy.open<Targets?>(TAG, "resolve grouped channel contract", null) {
            val group = "$PACKAGE.adapter.GroupTargetAdapter".toClassOrNull() ?: return@open null
            val grid = "$PACKAGE.grid.ChooserGridAdapter".toClassOrNull() ?: return@open null
            val recycler = "androidx.recyclerview.widget.RecyclerView".toClassOrNull() ?: return@open null
            val activity = "$PACKAGE.ChooserActivity".toClassOrNull() ?: return@open null
            val gate = HostAccess.requireDeclaredMethod(group, "isCollapsedWithOverflow", accessible = true)
            check(gate.returnType == Boolean::class.javaPrimitiveType)
            val gridField = HostAccess.requireDeclaredField(group, "mChooserGridAdapter", accessible = true)
            check(gridField.type == grid)
            val notify = HostAccess.requireDeclaredMethod(grid, "notifyDataSetChangedSafely", accessible = true)
            check(notify.returnType == Void.TYPE)
            // R8 removed RecyclerView.getAdapter() from the installed host; its backing field remains.
            val adapterField = HostAccess.requireDeclaredField(recycler, "mAdapter", accessible = true)
            check(adapterField.type.isAssignableFrom(grid))
            Targets(gate, gridField, grid, recycler, adapterField, notify, activity)
        } ?: run {
            DebugLog.hookSkipped(TAG, "show all share channels", "grouped channel contract unavailable")
            return
        }
        targets = resolved
        deoptimize(resolved.gate)
        // These are the verified count, bind and deferred-update callers in the current APK.
        // Deoptimize their classes as well so ART cannot retain an inlined copy of the gate.
        listOf("$PACKAGE.adapter.GroupTargetAdapter", "$PACKAGE.adapter.MiuiChooserGridAdapter",
            "$PACKAGE.adapter.MiuiChooserGridAdapter\$\$ExternalSyntheticLambda0").forEach { name ->
            name.toClassOrNull()?.declaredMethods?.forEach { method ->
                if (!java.lang.reflect.Modifier.isAbstract(method.modifiers)) deoptimize(method)
            }
        }
        resolved.gate.hook {
            before { param ->
                HookFailurePolicy.open(TAG, "grouped channel overflow", Unit) {
                    if (retired) return@open
                    resolved.gridField.get(param.thisObject)?.let { grid ->
                        synchronized(grids) { grids[grid] = true }
                    }
                    if (settingSharePanelShowAll) param.result = false
                }
            }
        }
        DebugLog.hookRegistered(TAG, "native grouped channel overflow")
        refreshSettings()
    }

    /** Rebind both tracked profile grids and hosts created before this feature was installed. */
    fun refreshSettings() = HostAccess.onMain {
        if (retired) return@onMain
        val resolved = targets ?: return@onMain
        Preferences.invalidateRuntimeReadCache()
        HookFailurePolicy.open(TAG, "recover living chooser", Unit) {
            val owner = Class.forName("android.app.ActivityThread")
            val thread = HostAccess.requirePublicMethod(owner, "currentActivityThread", accessible = true).invoke(null) ?: return@open
            val records = HostAccess.read(thread, "mActivities") as? Map<*, *> ?: return@open
            records.values.filterNotNull().forEach { record ->
                val host = HostAccess.read(record, "activity") as? Activity ?: return@forEach
                if (!host.isDestroyed && resolved.activityType.isInstance(host)) {
                    collectGrids(host.window.decorView, resolved)
                }
            }
        }
        synchronized(grids) { grids.keys.toList() }.forEach { grid ->
            HookFailurePolicy.open(TAG, "refresh channel grid", Unit) { resolved.notifyChanged.invoke(grid) }
        }
    }

    private fun collectGrids(view: View, resolved: Targets) {
        if (resolved.recyclerType.isInstance(view)) {
            resolved.adapterField.get(view)?.takeIf(resolved.gridType::isInstance)?.let { grid ->
                synchronized(grids) { grids[grid] = true }
            }
        }
        if (view is ViewGroup) repeat(view.childCount) { collectGrids(view.getChildAt(it), resolved) }
    }

    override fun saveHotReloadState(): Any = synchronized(grids) { grids.keys.toTypedArray() }

    override fun restoreHotReloadState(state: Any?) {
        val resolved = targets ?: return
        (state as? Array<*>)?.filterNotNull()?.filter(resolved.gridType::isInstance)?.forEach { grid ->
            synchronized(grids) { grids[grid] = true }
        }
    }

    override fun recoverHotReloadHosts() = refreshSettings()

    override fun onPrepareHotReload() = HostAccess.onMain {
        retired = true
        synchronized(grids) { grids.clear() }
    }
}
