package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Applies the five-state slot policy to classic [IconManager] block lists.
 *
 * The OS4 modern pipeline does not pass `blocked` to mobile/Wi-Fi holders, so this hook does not
 * pretend that the classic manager alone hides those views. [IconPositionHooker] applies the same
 * policy to `MiuiStatusIconContainer.ignoredSlots`. Both hooks retain the host list they received
 * before adding module-owned decisions, which keeps hot reload and a second module fail-open.
 */
object IconManagerHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val UTILS_CLASS = "com.android.systemui.statusbar.phone.MiuiIconManagerUtils"
    private const val ICON_MANAGER_CLASS = "com.android.systemui.statusbar.phone.ui.IconManager"

    private data class ManagerState(
        var pristine: List<String>,
        var lastApplied: List<String>? = null
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = WeakHashMap<Any, ManagerState>()
    private val listSurfaces = IdentityHashMap<Any, IconSurface>()
    private val pristineLists = IdentityHashMap<Any, List<String>>()
    private val stateLock = Any()

    @Volatile
    private var options = IconTunerOptions.snapshot()

    @Volatile
    private var restoring = false

    private var setBlockListMethod: java.lang.reflect.Method? = null

    override fun onPrepareHotReload() {
        // `setBlockList` asserts the main thread. Preparing a replacement generation runs on the
        // Xposed binder thread, so only request the restore here.
        val pending = synchronized(stateLock) {
            states.entries.map { (manager, state) -> manager to state.pristine.toList() }
        }
        mainHandler.post {
            restoring = true
            try {
                pending.forEach { (manager, pristine) ->
                    runCatching {
                        setBlockListMethod?.invoke(manager, ArrayList(pristine))
                    }.onFailure { DebugLog.w(TAG, "failed to restore IconManager block list", it) }
                }
            } finally {
                restoring = false
                synchronized(stateLock) {
                    states.clear()
                }
            }
        }
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        options = IconTunerOptions.snapshot()
        registerHostLists()

        val managerClass = ICON_MANAGER_CLASS.toClassOrNull()
        if (managerClass == null) {
            DebugLog.hookSkipped(TAG, ICON_MANAGER_CLASS, "class not found")
            return
        }
        val method = managerClass.findMethodOrNull {
            name("setBlockList")
            paramCount(1)
        }
        if (method == null) {
            DebugLog.hookSkipped(TAG, "$ICON_MANAGER_CLASS#setBlockList", "method not found")
            return
        }
        setBlockListMethod = method
        method.hook {
            before { param ->
                if (restoring || LeftContainerHooker.isApplyingBlockList(param.thisObject)) {
                    return@before
                }
                applyPolicy(param.thisObject, param.args.getOrNull(0)) { merged ->
                    param.args[0] = ArrayList(merged)
                }
            }
        }
        DebugLog.i(
            TAG,
            "IconManager policy installed position=${options.policy.position} " +
                "reorderHidden=${options.policy.reorderHidden}"
        )
    }

    /** Returns the host list captured for a static OS4 block-list object, if known. */
    fun pristineFor(list: Any?): List<String>? = synchronized(stateLock) {
        list?.let { pristineLists[it]?.toList() }
    }

    /** Returns the verified role of a static OS4 block-list object, if known. */
    fun surfaceFor(list: Any?): IconSurface? = synchronized(stateLock) {
        list?.let { listSurfaces[it] }
    }

    /** Returns the manager's host-only block list for cooperating placement hooks. */
    fun pristineForManager(manager: Any?): List<String>? = synchronized(stateLock) {
        manager?.let { states[it]?.pristine?.toList() }
    }

    private fun registerHostLists() {
        val utilsClass = UTILS_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, UTILS_CLASS, "class not found")
            return
        }
        registerHostList(utilsClass, "RIGHT_BLOCK_LIST", IconSurface.STATUS_BAR)
        registerHostList(utilsClass, "CONTROL_CENTER_BLOCK_LIST", IconSurface.CONTROL_CENTER)
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerHostList(clazz: Class<*>, fieldName: String, surface: IconSurface) {
        runCatching {
            val field = clazz.getDeclaredField(fieldName).apply { isAccessible = true }
            val list = field.get(null) as? List<*> ?: return@runCatching
            synchronized(stateLock) {
                listSurfaces.putIfAbsent(list, surface)
                pristineLists.putIfAbsent(list, stableStrings(list))
            }
        }.onFailure { DebugLog.w(TAG, "failed to capture $UTILS_CLASS#$fieldName", it) }
    }

    private fun applyPolicy(manager: Any, raw: Any?, publish: (List<String>) -> Unit) {
        val incoming = raw as? List<*> ?: return
        val incomingStrings = stableStrings(incoming)
        val location = readLocation(manager)
        val surface = IconSlotPolicy.surfaceForHostLocation(location).let { resolved ->
            if (resolved != IconSurface.UNKNOWN) resolved
            else surfaceFor(incoming) ?: IconSurface.UNKNOWN
        }

        val state = synchronized(stateLock) {
            states[manager] ?: ManagerState(
                pristine = pristineFor(incoming) ?: incomingStrings
            ).also { states[manager] = it }
        }
        val leftOwned = options.policy.leftSlots
        val incomingWithoutLeft = incomingStrings.filterNot(leftOwned::contains)
        val hostPristine = pristineFor(incoming)
            ?: if (state.lastApplied == incomingStrings || state.lastApplied == incomingWithoutLeft) {
                // LeftContainer adds its own slots when it re-applies the manager list. Those
                // slots are an owner overlay, not a new host pristine list.
                state.pristine
            } else {
                incomingStrings
            }
        state.pristine = hostPristine
        val merged = IconSlotPolicy.blockedFor(surface, hostPristine, options.policy)
        state.lastApplied = merged
        publish(merged)
    }

    private fun readLocation(manager: Any): String? = runCatching {
        hierarchyField(manager.javaClass, "mLocation")?.get(manager)?.toString()
    }.getOrNull()

    private fun hierarchyField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun stableStrings(values: List<*>): List<String> {
        val result = LinkedHashSet<String>()
        values.forEach { value ->
            if (value is String && value.isNotBlank()) result += value
        }
        return result.toList()
    }
}
