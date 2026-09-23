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

    override fun saveHotReloadState(): Any = synchronized(stateLock) {
        states.entries.map { (manager, state) -> listOf(manager, ArrayList(state.pristine)) }
    }

    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? List<*> ?: return
        mainHandler.post {
            synchronized(stateLock) {
                saved.forEach { entry ->
                    val row = entry as? List<*> ?: return@forEach
                    val manager = row.getOrNull(0) ?: return@forEach
                    val pristine = (row.getOrNull(1) as? List<*>)?.filterIsInstance<String>()
                        ?: return@forEach
                    states[manager] = ManagerState(pristine)
                }
            }
            republishMergedLists()
        }
    }

    internal fun recoverManagers(managers: List<Any>) {
        managers.forEach { manager ->
            val known = synchronized(stateLock) { states.containsKey(manager) }
            if (!known) {
                val current = hierarchyField(manager.javaClass, "mBlockList")?.get(manager) as? List<*>
                    ?: return@forEach
                synchronized(stateLock) { states[manager] = ManagerState(stableStrings(current)) }
            }
        }
        republishMergedLists()
    }

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

    private data class RefreshContext(val manager: Any, val minimalism: Boolean)
    private val refreshContexts = ThreadLocal.withInitial { ArrayList<RefreshContext>() }

    private fun finalBlocked(manager: Any, slot: String, hostBlocked: Boolean, minimalism: Boolean = false): Boolean {
        val location = readLocation(manager)
        val surface = IconSlotPolicy.surfaceForHostLocation(location)
        val owned = IconSlotPolicy.ownedSlotsFor(
            location,
            LeftContainerHooker.homeOwnedSlots(),
            LeftContainerHooker.keyguardOwnedSlots()
        )
        if (surface == IconSurface.CONTROL_CENTER &&
            ControlCenterHeaderHooker.secondRowStatusIconsEnabled()
        ) {
            return IconSlotPolicy.classicBlockedForTwoLineControlCenter(
                slot, hostBlocked, options.policy, owned, minimalism
            )
        }
        return IconSlotPolicy.classicBlocked(
            slot, surface, hostBlocked, options.policy, owned, minimalism
        )
    }

    private fun hookFinalVisibility(managerClass: Class<*>) {
        // addHolder receives tuner blocking before the first observer refresh.
        managerClass.findMethodOrNull { name("addHolder"); paramCount(4) }?.hook {
            before { param ->
                val manager = param.thisObject
                val slot = param.args.getOrNull(1) as? String ?: return@before
                val blocked = param.args.getOrNull(2) as? Boolean ?: return@before
                runCatching { param.args[2] = finalBlocked(manager, slot, blocked) }
                    .onFailure { DebugLog.w(TAG, "initial icon policy failed", it) }
            }
        }
        val controller = "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl".toClassOrNull() ?: return
        val minimalismField = hierarchyField(controller, "mMinimalismModeController") ?: return
        val minimalismMethod = minimalismField.type.methods.singleOrNull {
            it.name == "isMininalismModeOn" && it.parameterCount == 0
        } ?: return
        val icon = "com.android.systemui.statusbar.StatusBarIconView".toClassOrNull() ?: return
        val slotField = hierarchyField(icon, "mSlot") ?: return
        controller.findMethodOrNull { name("refreshIconGroup"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    val manager = param.args[0] ?: return@before
                    val minimalism = runCatching {
                        minimalismMethod.invoke(minimalismField.get(param.thisObject)) as? Boolean
                    }.getOrNull() ?: true
                    refreshContexts.get()?.add(RefreshContext(manager, minimalism))
                }
                after {
                    refreshContexts.get()?.let { stack ->
                        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                        if (stack.isEmpty()) refreshContexts.remove()
                    }
                }
            }
        }
        icon.findMethodOrNull { name("setBlocked"); paramCount(1) }?.let { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    val context = refreshContexts.get()?.lastOrNull() ?: return@before
                    runCatching {
                        val slot = slotField.get(param.thisObject) as? String ?: return@runCatching
                        val blocked = param.args[0] as? Boolean ?: return@runCatching
                        param.args[0] = finalBlocked(context.manager, slot, blocked, context.minimalism)
                    }.onFailure { DebugLog.w(TAG, "final icon policy failed", it) }
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
        hookFinalVisibility(managerClass)
        setBlockListMethod = method
        method.hook {
            before { param ->
                // No caller is excluded any more: the left container used to write its own overlay
                // through this method and skip the merge, which silently bypassed the slot modes.
                if (restoring) return@before
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
        val hostPristine = pristineFor(incoming)
            ?: if (state.lastApplied == incomingStrings) {
                // The host echoed our own merged list back (it is not one of the static lists this
                // hook captured), so keep the pristine list instead of polluting it.
                state.pristine
            } else {
                incomingStrings
            }
        state.pristine = hostPristine
        // The slot modes decide first and always win; the left container's overlay is layered on top
        // for the one row that would otherwise draw the same icon a second time. Everything else —
        // including a host re-emission that would otherwise drop the overlay — goes through here,
        // because this is the single place a host block-list emission is merged.
        val blocked = if (surface == IconSurface.CONTROL_CENTER &&
            ControlCenterHeaderHooker.secondRowStatusIconsEnabled()
        ) {
            IconSlotPolicy.blockedForTwoLineControlCenter(options.policy)
        } else {
            IconSlotPolicy.blockedFor(surface, hostPristine, options.policy)
        }
        val merged = IconSlotPolicy.withOwnedSlots(
            blocked,
            IconSlotPolicy.ownedSlotsFor(
                location,
                LeftContainerHooker.homeOwnedSlots(),
                LeftContainerHooker.keyguardOwnedSlots()
            )
        )
        state.lastApplied = merged
        publish(merged)
    }

    /**
     * Re-publishes the pristine host list for every tracked manager so [applyPolicy] re-merges it.
     *
     * A manager takes its block list when the host sets it (`onAttachedToWindow` for the control
     * center rows, and once at attach for the status bar / keyguard), so a change in the left
     * container's owned slots has to be pushed out — otherwise an attached row keeps a list that
     * predates the change. [LeftContainerHooker] calls this from its main-thread ticker whenever the
     * owned sets change.
     *
     * The pristine list is republished, never a merged one: [applyPolicy] derives the result again,
     * so this carries no policy state of its own.
     */
    fun republishMergedLists() {
        // `IconManager.setBlockList` asserts the main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { republishMergedLists() }
            return
        }
        val method = setBlockListMethod ?: return
        val pending = synchronized(stateLock) {
            states.entries.map { (manager, state) -> manager to ArrayList(state.pristine) }
        }
        if (pending.isEmpty()) return
        pending.forEach { (manager, pristine) ->
            runCatching { method.invoke(manager, pristine) }
                .onFailure { DebugLog.w(TAG, "failed to re-apply a merged block list", it) }
        }
    }

    private val locationFields = HashMap<Class<*>, Field?>()

    private fun readLocation(manager: Any): String? = runCatching {
        locationFields.getOrPut(manager.javaClass) { hierarchyField(manager.javaClass, "mLocation") }?.get(manager)?.toString()
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
