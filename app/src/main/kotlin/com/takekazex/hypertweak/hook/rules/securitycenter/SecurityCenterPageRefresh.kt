package com.takekazex.hypertweak.hook.rules.securitycenter

import android.app.Activity
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HookFailurePolicy

/** Rebuild only verified preference pages whose native initializers remove or retain rows. */
internal object SecurityCenterPageRefresh {
    private val privacyPages = setOf(
        "com.miui.permcenter.privacycenter.PrivacyFragment",
        "com.miui.dock.settings.DockSettingsFragment",
        "com.miui.gamebooster.beauty.BeautyManageFragment",
    )
    private val batteryPages = setOf("com.miui.powercenter.nightcharge.ChargeProtectFragment")

    fun refreshPrivacy() = refresh(privacyPages)
    fun refreshBattery() = refresh(batteryPages)

    private fun refresh(names: Set<String>) = HostAccess.onMain {
        HookFailurePolicy.open("SecurityCenterPageRefresh", "refresh preference pages", Unit) {
            val owner = Class.forName("android.app.ActivityThread")
            val thread = HostAccess.requirePublicMethod(owner, "currentActivityThread", accessible = false)
                .invoke(null) ?: return@open
            val records = HostAccess.read(thread, "mActivities") as? Map<*, *> ?: return@open
            records.values.filterNotNull().forEach { record ->
                HookFailurePolicy.open("SecurityCenterPageRefresh", "refresh activity", Unit) activity@{
                    val activity = HostAccess.read(record, "activity") as? Activity ?: return@activity
                    if (activity.isFinishing || activity.isDestroyed || activity.packageName != "com.miui.securitycenter") return@activity
                    val manager = HostAccess.method(activity, "getSupportFragmentManager")?.invoke(activity) ?: return@activity
                    if (containsPage(manager, names, 0)) activity.recreate()
                }
            }
        }
    }

    private fun containsPage(manager: Any, names: Set<String>, depth: Int): Boolean {
        if (depth > 8) return false
        val listMethod = HostAccess.cached(manager.javaClass, "security-center:fragment-list", java.lang.reflect.Method::class.java) {
            SecurityCenterFragmentListContract.resolve(manager.javaClass)
        } ?: return false
        val fragmentClass = Class.forName("androidx.fragment.app.Fragment", false, manager.javaClass.classLoader)
        val fragments = SecurityCenterFragmentListContract.validate(listMethod.invoke(manager), fragmentClass) ?: return false
        return fragments.any { fragment ->
            if (fragment.javaClass.name in names) true
            else if (HostAccess.method(fragment, "isAdded")?.invoke(fragment) == true) {
                HostAccess.method(fragment, "getChildFragmentManager")?.invoke(fragment)
                    ?.let { containsPage(it, names, depth + 1) } == true
            } else false
        }
    }
}
