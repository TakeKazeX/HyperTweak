package com.takekazex.hypertweak.hook.rules.settings

import android.app.Activity
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostActivityRefresh
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker

/** Rebuild removed preference trees with native saved-state restoration on returning to Settings. */
object ExperimentalSettingsRefreshHooker : StaticHooker() {
    private val pages = generationMainResource(HostActivityRefresh()) { it.clear() }

    override fun onHook() {
        val resume = HostAccess.requireDeclaredMethod(Activity::class.java, "onPostResume", accessible = true)
        resume.hook {
            after { param ->
                val activity = param.thisObject as? Activity ?: return@after
                HookFailurePolicy.open(hookerName, "resume Settings capability pages", Unit) { pages.resume(activity) }
            }
        }
    }

    fun refreshSettings(): Boolean = HostAccess.onMain {
        HookFailurePolicy.open(hookerName, "queue Settings capability pages", false) {
            pages.request { activity ->
                activity.packageName == "com.android.settings" && containsCapabilityPage(activity)
            }
            true
        }
    }

    override fun recoverHotReloadHosts() { refreshSettings() }

    private fun containsCapabilityPage(activity: Activity): Boolean {
        fun matches(name: String) = name == "com.android.settings.MiuiDisplaySettings" ||
            name.contains("VisualPerception") || name.contains("AonGesture") || name.contains("AonUpDown")
        if (matches(activity.javaClass.name) || matches(activity.intent?.getStringExtra(":settings:show_fragment").orEmpty())) return true
        fun visit(manager: Any?): Boolean {
            val fragments = manager?.let { HostAccess.invoke(it, "getFragments") } as? List<*> ?: return false
            return fragments.filterNotNull().any { fragment ->
                matches(fragment.javaClass.name) ||
                    (HostAccess.invoke(fragment, "isAdded") == true && visit(HostAccess.invoke(fragment, "getChildFragmentManager")))
            }
        }
        return visit(HostAccess.method(activity, "getSupportFragmentManager")?.invoke(activity))
    }
}
