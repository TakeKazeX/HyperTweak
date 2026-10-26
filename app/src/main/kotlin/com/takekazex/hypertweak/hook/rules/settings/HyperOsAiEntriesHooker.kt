package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.app.Activity
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
    private val pages = WeakHashMap<Any, Boolean>()
    private val methods = HashMap<Pair<Class<*>, String>, Method>()
    private val displays = HashMap<Class<*>, Method>()
    private var nativeLabelDisplay: Method? = null
    private var fragmentActivity: Class<*>? = null
    private var ready = false
    private var retired = false

    override fun onHook() {
        if (hookParam.packageName != PACKAGE || !isMainProcess) return
        retired = false
        fragmentActivity = hostClass("androidx.fragment.app.FragmentActivity")
        val screen = hostClass("androidx.preference.PreferenceScreen") ?: return
        hookAvailability(MODEL, "hyperos_ai:ondevice_model_entry", PlatformSettingsKeys.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY)
        hookAvailability(GEMINI, "hyperos_ai:gemini_entry", PlatformSettingsKeys.KEY_SETTINGS_GEMINI_ENTRY)
        listOf(MODEL, GEMINI).forEach { name ->
            val owner = hostClass(name) ?: return@forEach
            displays[owner] = owner.getMethod("displayPreference", screen)
        }
        // Native rendering also clears labels left by the previous APK after its unmatched
        // hook is retired. No label hook or module text is installed.
        nativeLabelDisplay = hostClass("com.android.settings.hyperosai.SuperXiaoAiController")
            ?.getMethod("displayPreference", screen)
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
                    if (!retired && Preferences.getBoolean(key, false)) param.result = 0
                }
            }
        }
    }

    private fun call(owner: Any, name: String): Any? {
        val method = methods.getOrPut(owner.javaClass to name) {
            generateSequence(owner.javaClass) { it.superclass }.mapNotNull { type ->
                type.declaredMethods.singleOrNull { it.name == name && it.parameterTypes.isEmpty() }
            }.first().apply { isAccessible = true }
        }
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
            val thread = owner.getMethod("currentActivityThread").invoke(null)
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
        ready = false
        pages.clear()
        methods.clear()
        displays.clear()
        nativeLabelDisplay = null
        fragmentActivity = null
    }
}
