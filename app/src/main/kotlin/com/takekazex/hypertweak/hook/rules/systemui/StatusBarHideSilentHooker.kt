package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Native silent-icon repair plus an explicit status-bar-only importance threshold.
 * Channel settings, notification sound, AOD and shelf policies remain host-owned.
 */
object StatusBarHideSilentHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    private const val TAG = "HideSilent"
    private const val BUILDER =
        "com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsStoreBuilder"
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val pipelines = instanceTracker()
    @Volatile private var threshold = -1
    @Volatile private var ready = false
    @Volatile private var retired = false
    private var copier: NotificationModelCopy? = null
    private var builderField: Field? = null
    private var schedule: Method? = null
    private val calls = ThreadLocal.withInitial { java.util.ArrayDeque<Pair<Any, Boolean>>() }


    @Volatile
    private var enabled = false

    private var rankingField: Field? = null
    private var getImportance: Method? = null
    private var isSilentField: Field? = null

    override fun onPrepareHotReload() {
        retired = true
        enabled = false
        ready = false
        main.removeCallbacksAndMessages(null)
        calls.remove()
        copier = null
        rankingField = null
        getImportance = null
        isSilentField = null
    }

    override fun onHook() {
        if (!isMainProcess) return
        retired = false
        enabled = Preferences.notificationMoreSettings()
        threshold = NotificationIconPolicy.threshold(Preferences.getInt(Preferences.KEY_NOTIFICATION_ICON_FILTER_MODE, 0))
        val builderClass = BUILDER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BUILDER, "class not found")
            return
        }
        // toModel has two single-argument overloads (GroupEntry and NotificationEntry); matching on
        // the parameter count alone can pick the group one, whose return type has no isSilent field.
        val toModel = builderClass.declaredMethods.firstOrNull {
            it.name == "toModel" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name.endsWith("NotificationEntry")
        }?.apply { isAccessible = true } ?: run {
            DebugLog.hookSkipped(TAG, "$BUILDER#toModel(NotificationEntry)", "method not found")
            return
        }
        if (!resolveReflection(toModel)) {
            DebugLog.hookSkipped(TAG, "$BUILDER#toModel isSilent wiring", "resolve failed")
            return
        }

        toModel.hook("notification_more_settings_silent_filter") {
            after { param ->
                if (retired || (!enabled && !(ready && threshold >= 0))) return@after
                HookFailurePolicy.open(TAG, "toModel.isSilent", Unit) {
                    val entry = param.args.getOrNull(0) ?: return@open
                    val model = param.result ?: return@open
                    // mRanking is null for synthetic/placeholder entries. Keep the host's own value
                    // instead of treating it as importance 0, which would hide the icon.
                    val ranking = rankingField?.get(entry) ?: return@open
                    val importance = getImportance?.invoke(ranking) as? Int ?: return@open
                    val silent = NotificationIconPolicy.silent(importance, if (ready) threshold else -1, enabled) ?: return@open
                    param.result = copier?.withFlag(model, silent) ?: model
                }
            }
        }
        val pipeline = "com.android.systemui.statusbar.notification.collection.NotifPipeline".toClassOrNull()
        builderField = pipeline?.getDeclaredField("mShadeListBuilder")?.apply { isAccessible = true }
        schedule = builderField?.type?.getDeclaredMethod("scheduleRebuild", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)?.apply { isAccessible = true }
        observeInstances("com.android.systemui.statusbar.notification.collection.NotifPipeline", pipelines)
        prepareDexTargets("notification icon threshold", resolve = {
            val apk = requireNotNull(hookParam.appInfo?.sourceDir)
            DexRuntime.resolve(
                feature = object : DexFeature { override val id = "systemui.notification_icon_filter"; override val schema = 1 },
                apkPath = apk, cacheDir = dexCacheDirectory,
                compute = { DexRuntime.operands(apk).let(NotificationIconFilterContract::resolve)
                    ?.let(NotificationIconFilterContract::encode).orEmpty() },
                decode = { values, _ -> NotificationIconFilterContract.decode(values, classLoader) },
            )
        }, install = { target ->
            val owner = target.owner.toClass()
            val invoke = owner.getDeclaredMethod(target.method, Any::class.java)
            val ambient = owner.getDeclaredField(target.ambientFlag).apply { isAccessible = true }
            val silent = owner.getDeclaredField(target.silentFlag).apply { isAccessible = true }
            target.callers.forEach { (type, name) -> deoptimize(type.toClass().getDeclaredMethod(name, Any::class.java)) }
            invoke.hook("notification_icon_threshold_predicate") {
                before { param ->
                    HookFailurePolicy.open(TAG, "scope status-bar filter", Unit) {
                        if (!retired && threshold >= 0 && !ambient.getBoolean(param.thisObject)) {
                            val original = silent.getBoolean(param.thisObject)
                            val stack = calls.get() ?: return@open
                            stack.push(param.thisObject to original)
                            silent.setBoolean(param.thisObject, false)
                        }
                    }
                }
                after { param ->
                    HookFailurePolicy.open(TAG, "restore filter flag", Unit) {
                        val stack = calls.get() ?: return@open
                        if (stack.peek()?.first === param.thisObject) {
                            silent.setBoolean(param.thisObject, stack.pop().second)
                            if (stack.isEmpty()) calls.remove()
                        }
                    }
                }
            }
            ready = true
            refreshSettings()
            DebugLog.hookRegistered(TAG, "native status-bar threshold predicate")
        })
    }


    fun refreshSettings() {
        Preferences.invalidateRuntimeReadCache()
        enabled = Preferences.notificationMoreSettings()
        threshold = NotificationIconPolicy.threshold(Preferences.getInt(Preferences.KEY_NOTIFICATION_ICON_FILTER_MODE, 0))
        main.post { if (!retired) requestRebuild() }
    }

    fun recoverExistingHosts(component: Any?) {
        HookFailurePolicy.open(TAG, "recover notification pipeline", Unit) {
            val provider = component?.let { com.takekazex.hypertweak.hook.base.HostAccess.read(it, "notifPipelineProvider") }
            provider?.javaClass?.getMethod("get")?.apply { isAccessible = true }?.invoke(provider)?.let(pipelines::add)
            requestRebuild()
        }
    }

    private fun requestRebuild() {
        HookFailurePolicy.open(TAG, "rebuild current notification icons", Unit) {
            val field = builderField ?: return@open
            val method = schedule ?: return@open
            pipelines.snapshot().forEach { pipeline -> field.get(pipeline)?.let { method.invoke(it, 0, false) } }
        }
    }

    /** Caches the reflective members used on the hot path; false when any of them is missing. */
    private fun resolveReflection(toModel: Method): Boolean {
        rankingField = runCatching { toModel.parameterTypes[0].getField("mRanking") }.getOrNull()
        getImportance = rankingField?.let { field ->
            runCatching { field.type.getMethod("getImportance") }.getOrNull()
        }
        isSilentField = runCatching { toModel.returnType.getField("isSilent") }.getOrNull()
        copier = isSilentField?.let { NotificationModelCopy(toModel.returnType, it) }
        return rankingField != null && getImportance != null && isSilentField != null
    }
}
