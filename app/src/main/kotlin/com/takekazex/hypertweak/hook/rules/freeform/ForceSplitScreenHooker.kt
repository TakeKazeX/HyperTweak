package com.takekazex.hypertweak.hook.rules.freeform

import android.app.ActivityManager
import android.app.TaskInfo
import android.os.IBinder
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.SystemServerStartupRetry
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.FreeformSettingsKeys
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

private const val WM = "com.android.server.wm."
private const val WINDOWING_MODE_MULTI_WINDOW = 6
private val BOOL = Boolean::class.javaPrimitiveType!!
private val INT = Int::class.javaPrimitiveType!!

internal object ForceSplitScreenHooker : FreeformHooker() {
    override val hookerName = "ForceSplitScreen"

    private val forceSplit by hookSetting(false) {
        Preferences.getBoolean(FreeformSettingsKeys.KEY_FORCE_SPLIT, false)
    }
    private var refreshTaskInfo: (() -> Boolean)? by generationState { null }
    private val recovery by lazy { SystemServerStartupRetry(hookerName, action = { refreshTaskInfo?.invoke() ?: true }) }

    override fun onHook() = installFeature("split screen") { installSplitScreen() }

    fun refreshSettings() {
        if (refreshTaskInfo != null) recovery.schedule()
    }

    override fun recoverHotReloadHosts() = refreshSettings()
    override fun onPrepareHotReload() { recovery.cancel() }

    private fun installSplitScreen() {
        val task = host(WM + "Task")
        val container = host(WM + "WindowContainer")
        val config = host(WM + "ConfigurationContainer")
        val area = host(WM + "TaskDisplayArea")
        val fragment = host(WM + "TaskFragment")
        val supervisor = host(WM + "ActivityTaskSupervisor")
        val organizer = host(WM + "WindowOrganizerController")
        val hierarchyOp = host("android.window.WindowContainerTransaction\$HierarchyOp")
        val sosc = host(WM + "MiuiSoScManagerStub")
        val getSoSc = target(sosc.name, "get", sosc, static = true).accessible()
        val isStage = target(WM + "MiuiSoScManagerImpl", "isSoScRootTask", BOOL, container).accessible()
        val getMode = target(config.name, "getWindowingMode", INT).accessible()
        val isStandard = target(config.name, "isActivityTypeStandard", BOOL).accessible()
        val getArea = target(fragment.name, "getDisplayArea", area).accessible()
        val getNewParent = target(hierarchyOp.name, "getNewParent", IBinder::class.java).accessible()
        val isReparent = target(hierarchyOp.name, "isReparent", BOOL).accessible()
        val fromBinder = target(container.name, "fromBinder", container, IBinder::class.java, static = true).accessible()

        val inSplit = target(task.name, "supportsSplitScreenWindowingModeInDisplayArea", BOOL, area).accessible()
        val taskSplitCheck = target(task.name, "supportsMultiWindowInDisplayAreaForSplit", BOOL, area)
        val activitySplitCheck = target(WM + "ActivityRecordImpl", "supportsMultiWindowInDisplayAreaForSplit", BOOL, area)
        val sanitizeReparent = target(organizer.name, "sanitizeAndApplyHierarchyOpForTask", INT, task, hierarchyOp)
        val resolveReparent = target(supervisor.name, "getReparentTargetRootTask", task, task, task, BOOL)
        val fillInfo = target(task.name, "fillTaskInfo", Void.TYPE, TaskInfo::class.java, BOOL, area)
        val splitSupported = TaskInfo::class.java.getField("supportsSplitScreenMultiWindow")
        val multiWindowSupported = TaskInfo::class.java.getField("supportsMultiWindow")
        check(splitSupported.type == BOOL && multiWindowSupported.type == BOOL)
        val nonResizable = target(area.name, "supportsNonResizableMultiWindow", BOOL)
        val refresh = resolveTaskInfoRefresh(task, container, getSoSc, isStandard)

        val ready = AtomicBoolean(false)
        val areaInScope = ThreadLocal<Any>()
        fun Method.trackArea(id: String, getAreaForCall: (List<Any?>) -> Any?) {
            deoptimize(this)
            hook(id) {
                intercept { chain ->
                    val previous = areaInScope.get()
                    val current = if (ready.get() && forceSplit) {
                        HookFailurePolicy.open(hookerName, id, null) { getAreaForCall(chain.args) }
                    } else null
                    if (previous === current) return@intercept chain.proceed()
                    areaInScope.set(current)
                    try {
                        chain.proceed()
                    } finally {
                        if (previous == null) areaInScope.remove() else areaInScope.set(previous)
                    }
                }
            }
        }

        fun destinationArea(source: Any?, destination: Any?): Any? {
            if (!task.isInstance(source) || !task.isInstance(destination) ||
                getMode.invoke(destination) != WINDOWING_MODE_MULTI_WINDOW || isStandard.invoke(source) != true
            ) return null
            val manager = getSoSc.invoke(null) ?: return null
            if (isStage.invoke(manager, destination) != true) return null
            val destinationArea = getArea.invoke(destination) ?: return null
            return destinationArea.takeIf { inSplit.invoke(source, it) == true }
        }

        // Deoptimize the readers of the scoped leaf and the direct callers of the hooked methods.
        listOf(
            inSplit,
            target(task.name, "supportsSplitScreenWindowingModeInner", BOOL, area),
            target(fragment.name, "supportsMultiWindowInDisplayArea", BOOL, area),
            target(WM + "ActivityRecord", "supportsMultiWindowInDisplayAreaForSplit", BOOL, area),
            target(task.name, "reparent", BOOL, task, BOOL, INT, BOOL, BOOL, String::class.java),
            target(task.name, "fillTaskInfo", Void.TYPE, TaskInfo::class.java, BOOL),
            target(
                WM + "RecentTasks", "createRecentTaskInfo", ActivityManager.RecentTaskInfo::class.java,
                task, BOOL, BOOL,
            ),
        ).forEach(::deoptimize)

        taskSplitCheck.trackArea("force_split_task_check") { it[0] }
        activitySplitCheck.trackArea("force_split_activity_check") { it[0] }
        sanitizeReparent.trackArea("force_split_organizer_reparent") { args ->
            if (isReparent.invoke(args[1]) != true) return@trackArea null
            val token = getNewParent.invoke(args[1]) as? IBinder
            val parent = token?.let { fromBinder.invoke(null, it) }
            destinationArea(args[0], parent)
        }
        resolveReparent.trackArea("force_split_resolve_reparent") { args ->
            destinationArea(args[0], args[1])
        }
        deoptimize(nonResizable)
        nonResizable.hook("force_split_non_resizable_area") {
            before { param ->
                val activeArea = areaInScope.get()
                if (activeArea != null && activeArea === param.thisObject && ready.get() && forceSplit) {
                    param.result = true
                }
            }
        }
        deoptimize(fillInfo)
        fillInfo.hook("force_split_task_info") {
            after { param ->
                if (!ready.get() || !forceSplit) return@after
                val info = param.args[0] as? TaskInfo ?: return@after
                HookFailurePolicy.open(hookerName, "publish split task capability", Unit) {
                    // SoSc also reads the general bit on its task/transition snapshots.
                    if (splitSupported.getBoolean(info) && !multiWindowSupported.getBoolean(info) &&
                        isStandard.invoke(param.thisObject) == true
                    ) multiWindowSupported.setBoolean(info, true)
                }
            }
        }
        ready.set(true)
        refreshTaskInfo = refresh
    }

    private fun resolveTaskInfoRefresh(
        task: Class<*>,
        container: Class<*>,
        getSoSc: Method,
        isStandard: Method,
    ): () -> Boolean {
        val implementation = host(WM + "MiuiSoScManagerImpl")
        val atms = host(WM + "ActivityTaskManagerService")
        val root = host(WM + "RootWindowContainer")
        val controller = host(WM + "TaskChangeNotificationController")
        val serviceField = implementation.instanceField("mAtms", atms)
        val rootField = atms.instanceField("mRootWindowContainer", root)
        val getLock = target(atms.name, "getGlobalLock", host(WM + "WindowManagerGlobalLock")).accessible()
        val getController = target(atms.name, "getTaskChangeNotificationController", controller).accessible()
        val notifyStack = target(controller.name, "notifyTaskStackChanged", Void.TYPE).accessible()
        val forEachLeaf = target(container.name, "forAllLeafTasks", Void.TYPE, Consumer::class.java, BOOL).accessible()
        val dispatchInfo = target(task.name, "dispatchTaskInfoChangedIfNeeded", Void.TYPE, BOOL).accessible()
        return refresh@{
            val manager = getSoSc.invoke(null) ?: return@refresh false
            if (!implementation.isInstance(manager)) return@refresh false
            val service = serviceField.get(manager) ?: return@refresh false
            val lock = getLock.invoke(service) ?: return@refresh false
            synchronized(lock) {
                val rootContainer = rootField.get(service) ?: return@refresh false
                val notifier = getController.invoke(service) ?: return@refresh false
                forEachLeaf.invoke(rootContainer, Consumer<Any> {
                    if (isStandard.invoke(it) == true) dispatchInfo.invoke(it, true)
                }, true)
                notifyStack.invoke(notifier)
            }
            true
        }
    }
}
