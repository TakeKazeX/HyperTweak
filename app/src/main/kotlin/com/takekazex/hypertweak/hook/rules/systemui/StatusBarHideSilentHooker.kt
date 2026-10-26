package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.base.cachedDeclaredField
import com.takekazex.hypertweak.hook.base.cachedDeclaredMethod
import com.takekazex.hypertweak.hook.base.cachedPublicField
import com.takekazex.hypertweak.hook.base.cachedPublicMethod
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationIconFilterContract
import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationModelCopy
import com.takekazex.hypertweak.settings.contract.NotificationIconPolicy

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Native silent-icon repair plus an explicit status-bar-only importance threshold.
 * Channel settings, notification sound, AOD and shelf policies remain host-owned.
 */
object StatusBarHideSilentHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingNotificationMoreSettings by hookSetting(false) { Preferences.notificationMoreSettings() }
    private val settingNotificationIconFilterMode by hookSetting(0) {
        Preferences.getInt(SystemUiSettingsKeys.KEY_NOTIFICATION_ICON_FILTER_MODE, 0)
    }

    init {
        hostRecovery(HostRecoveryPhase.WINDOWS) {
            recoverExistingHosts(it.component)
        }
    }

    private const val TAG = "HideSilent"
    private const val BUILDER =
        "com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsStoreBuilder"
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val pipelines = instanceTracker()
    private var threshold by generationState { -1 }
    private var ready by generationState { false }
    @Volatile
    private var retired = false
    init { generationCleanup { retired = true } }
    private var copier: NotificationModelCopy? by generationState { null }
    private var builderField: Field? by generationState { null }
    private var schedule: Method? by generationState { null }
    private val calls = ThreadLocal.withInitial { java.util.ArrayDeque<Pair<Any, Boolean>>() }

    private var enabled by generationState { false }

    private var rankingField: Field? by generationState { null }
    private var getImportance: Method? by generationState { null }
    private var isSilentField: Field? by generationState { null }

    override fun onPrepareHotReload() {
        retired = true
        main.removeCallbacksAndMessages(null)
        calls.remove()
    }

    override fun onHook() {
        if (!isMainProcess) return
        retired = false
        enabled = settingNotificationMoreSettings
        threshold = NotificationIconPolicy.threshold(settingNotificationIconFilterMode)
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
        builderField = pipeline?.cachedDeclaredField("mShadeListBuilder", accessible = true)
        schedule = builderField?.type?.cachedDeclaredMethod("scheduleRebuild", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, accessible = true)
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
            val invoke = HostAccess.requireDeclaredMethod(owner, target.method, Any::class.java, accessible = false)
            val ambient = HostAccess.requireDeclaredField(owner, target.ambientFlag, accessible = true)
            val silent = HostAccess.requireDeclaredField(owner, target.silentFlag, accessible = true)
            target.callers.forEach { (type, name) -> deoptimize(type.toClass().cachedDeclaredMethod(name, Any::class.java, accessible = false)) }
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
        enabled = settingNotificationMoreSettings
        threshold = NotificationIconPolicy.threshold(settingNotificationIconFilterMode)
        main.post { if (!retired) requestRebuild() }
    }

    fun recoverExistingHosts(component: Any?) {
        HookFailurePolicy.open(TAG, "recover notification pipeline", Unit) {
            val provider = component?.let { com.takekazex.hypertweak.hook.base.HostAccess.read(it, "notifPipelineProvider") }
            provider?.javaClass?.cachedPublicMethod("get", accessible = true)?.invoke(provider)?.let(pipelines::add)
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
        rankingField = runCatching { toModel.parameterTypes[0].cachedPublicField("mRanking", accessible = false) }.getOrNull()
        getImportance = rankingField?.let { field ->
            runCatching { HostAccess.requirePublicMethod(field.type, "getImportance", accessible = false) }.getOrNull()
        }
        isSilentField = runCatching { HostAccess.requirePublicField(toModel.returnType, "isSilent", accessible = false) }.getOrNull()
        copier = isSilentField?.let { NotificationModelCopy(toModel.returnType, it) }
        return rankingField != null && getImportance != null && isSilentField != null
    }
}
