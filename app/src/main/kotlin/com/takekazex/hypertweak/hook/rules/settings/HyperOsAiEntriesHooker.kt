package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.hook.base.cachedPublicMethod
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.app.Activity
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Settings.apk SHA-256 2ad1f138457c5f60b045cf41e18babc7e8bcc36c123526f7ddc6ca15b906d655:
 * AbstractPreferenceController.displayPreference keeps the XML nodes and calls setVisible.
 * Replaying only these two native controllers refreshes existing pages without rebuilding the
 * screen, touching AI capability storage, or changing unrelated controllers.
 */
object HyperOsAiEntriesHooker : StaticHooker() {
    private const val TAG = "HyperOsAiEntries"
    private const val PACKAGE = "com.android.settings"
    private const val FRAGMENT = "com.android.settings.XiaomiHyperOSAiFragment"
    private const val MODEL = "com.android.settings.hyperosai.ModelManagementController"
    private const val GEMINI = "com.android.settings.hyperosai.GeminiController"
    private val pages = generationMainResource(WeakHashMap<Any, Boolean>()) { it.clear() }
    private val displays = generationMainResource(HashMap<Class<*>, Method>()) { it.clear() }
    private var nativeLabelDisplay: Method? by generationState { null }
    private var fragmentActivity: Class<*>? by generationState { null }
    private var ready by generationState { false }
    private var retired = false
    init { generationCleanup { retired = true } }

    override fun onHook() {
        if (hookParam.packageName != PACKAGE || !isMainProcess) return
        retired = false
        fragmentActivity = hostClass("androidx.fragment.app.FragmentActivity")
        val screen = hostClass("androidx.preference.PreferenceScreen") ?: return
        hookAvailability(MODEL, "hyperos_ai:ondevice_model_entry", PlatformSettingsKeys.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY)
        hookAvailability(GEMINI, "hyperos_ai:gemini_entry", PlatformSettingsKeys.KEY_SETTINGS_GEMINI_ENTRY)
        listOf(MODEL, GEMINI).forEach { name ->
            val owner = hostClass(name) ?: return@forEach
            displays[owner] = HostAccess.requirePublicMethod(owner, "displayPreference", screen, accessible = false)
        }
        // Native rendering also clears labels left by the previous APK after its unmatched
        // hook is retired. No label hook or module text is installed.
        nativeLabelDisplay = hostClass("com.android.settings.hyperosai.SuperXiaoAiController")
            ?.cachedPublicMethod("displayPreference", screen, accessible = false)
        val fragment = hostClass(FRAGMENT) ?: return
        val resume = fragment.declaredMethods.singleOrNull {
            it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
        } ?: run {
            DebugLog.hookSkipped(TAG, "$FRAGMENT#onResume()V", "method missing or ambiguous")
            return
        }
        deoptimize(resume)
        resume.hook("hyperos_ai:page_resume") {
            after { param ->
                if (!retired) param.thisObject.let { page ->
                    pages[page] = true
                    HookFailurePolicy.open(TAG, "resume AI page", Unit) { refreshPage(page) }
                }
            }
        }
        ready = true
        DebugLog.d(TAG, "AI entry controllers and page refresh armed")
    }

    private fun hostClass(name: String): Class<*>? = name.toClassOrNull().also {
        if (it == null) DebugLog.hookSkipped(TAG, name, "class not found")
    }

    private fun hookAvailability(className: String, hookId: String, key: String) {
        val enabled by hookSetting(false) { Preferences.getBoolean(key, false) }
        val owner = hostClass(className) ?: return
        val availability = owner.declaredMethods.singleOrNull {
            it.name == "getAvailabilityStatus" && it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType
        } ?: run {
            DebugLog.hookSkipped(TAG, "$className#getAvailabilityStatus()I", "method missing or ambiguous")
            return
        }
        deoptimize(availability)
        availability.hook(hookId) {
            after { param ->
                HookFailurePolicy.open(TAG, "entry availability", Unit) {
                    if (!retired && enabled) param.result = 0
                }
            }
        }
    }

    private fun call(owner: Any, name: String): Any? {
        val method = HostAccess.cached(owner.javaClass, "settings-ai:declared-noargs:$name", java.lang.reflect.Method::class.java) {
            generateSequence(owner.javaClass) { it.superclass }.mapNotNull { type ->
                type.declaredMethods.singleOrNull { it.name == name && it.parameterTypes.isEmpty() }
            }.first().apply { isAccessible = true }
        } ?: throw NoSuchElementException("Missing native Settings callback: $name")
        return method.invoke(owner)
    }

    private fun refreshPage(page: Any, recoverLabels: Boolean = false) {
        val screen = call(page, "getPreferenceScreen") ?: return
        // getPreferenceControllers returns Map<Class, List<Controller>>.values, not a flat list.
        val groups = call(page, "getPreferenceControllers") as? Collection<*> ?: return
        groups.filterIsInstance<Collection<*>>().flatten().filterNotNull().forEach { controller ->
            displays[controller.javaClass]?.invoke(controller, screen)
            if (recoverLabels && nativeLabelDisplay?.declaringClass?.isInstance(controller) == true) {
                nativeLabelDisplay?.invoke(controller, screen)
            }
        }
    }

    /** Shared dispatcher calls on main; return failures so its bounded retry remains effective. */
    fun refreshSettings(): Boolean = HostAccess.onMain {
        if (retired || !ready || !isMainProcess) return@onMain true
        Preferences.invalidateRuntimeReadCache()
        refreshHosts()
    }

    private fun refreshHosts(recoverLabels: Boolean = true): Boolean {
        val discovered = HookFailurePolicy.open(TAG, "recover AI pages", false) {
            val owner = Class.forName("android.app.ActivityThread")
            val thread = HostAccess.requirePublicMethod(owner, "currentActivityThread", accessible = false).invoke(null)
            val records = thread?.let { HostAccess.read(it, "mActivities") } as? Map<*, *>
            records?.values?.filterNotNull()?.forEach { record ->
                val activity = HostAccess.read(record, "activity") as? Activity ?: return@forEach
                if (!activity.isDestroyed && fragmentActivity?.isInstance(activity) == true) {
                    visitFragments(call(activity, "getSupportFragmentManager"))
                }
            }
            true
        }
        var applied = discovered
        pages.keys.toList().forEach { page ->
            val result = HookFailurePolicy.open(TAG, "refresh AI page", false) {
                refreshPage(page, recoverLabels)
                true
            }
            applied = applied && result
        }
        return applied
    }

    private fun visitFragments(manager: Any?) {
        if (manager == null) return
        val fragments = call(manager, "getFragments") as? List<*> ?: return
        fragments.filterNotNull().forEach { fragment ->
            if (fragment.javaClass.name == FRAGMENT) pages[fragment] = true
            if (call(fragment, "isAdded") == true) visitFragments(call(fragment, "getChildFragmentManager"))
        }
    }

    override fun recoverHotReloadHosts() = HostAccess.onMain {
        if (ready && !retired && isMainProcess) refreshHosts(recoverLabels = true)
        Unit
    }

    override fun onPrepareHotReload() = HostAccess.onMain {
        // Keep the old handles alive during restoration, but make their overrides inert. Native
        // controllers restore visibility before replacement.
        retired = true
        if (ready) refreshHosts(recoverLabels = false)
        pages.clear()
        displays.clear()
        fragmentActivity = null
    }
}
