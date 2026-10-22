package com.takekazex.hypertweak.hook.rules.securitycenter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.view.View
import android.widget.ImageView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHostAccess
import com.takekazex.hypertweak.util.DebugLog
import java.util.WeakHashMap

/** The host title has no stock click listener. Only its icon gains a launcher action. */
object AppDetailsIconLaunchHooker : DynamicHooker() {
    private const val TAG = "AppDetailsIconLaunch"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val TITLE = "com.miui.appmanager.widget.AppDetailTitlePreference"
    private const val DETAILS = "com.miui.appmanager.ApplicationsDetailsActivity"
    private data class Original(val clickable: Boolean, val focusable: Int)
    private val icons = WeakHashMap<ImageView, Original>()
    private val userHandle by lazy { UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType) }
    private var retired = false
    private var ready = false

    override fun onHook() {
        if (hookParam.processName != PACKAGE) return
        retired = false
        ready = false
        val title = TITLE.toClassOrNull() ?: return
        // The public override survives holder obfuscation; no holder class/member names are pinned.
        val bind = title.declaredMethods.singleOrNull {
            it.name == "onBindViewHolder" && it.parameterCount == 1 && it.returnType == Void.TYPE
        } ?: return
        val itemView = bind.parameterTypes.single().getField("itemView")
        val fragment = "com.miui.appmanager.fragment.ApplicationsDetailsFragment".toClassOrNull() ?: return
        val resume = fragment.getDeclaredMethod("onResume")
        val getActivity = fragment.getMethod("getActivity")
        bind.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "bind icon", Unit) {
                    val root = itemView.get(param.args[0]) as? View ?: return@open
                    bindRoot(root)
                }
            }
        }
        resume.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "resume icon", Unit) {
                    val host = getActivity.invoke(param.thisObject) as? Activity ?: return@open
                    bindRoot(host.window.decorView)
                }
            }
        }
        ready = true
        DebugLog.hookRegistered(TAG, "app details icon")
    }

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(16) {
            if (current is Activity) return current
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }

    private fun bindRoot(root: View) {
        if (retired || !ready) return
        val host = activity(root.context)?.takeIf { it.javaClass.name == DETAILS } ?: return
        val id = root.resources.getIdentifier("app_manager_details_appicon", "id", PACKAGE)
        if (id == 0) return
        val icon = root.findViewById<View>(id) as? ImageView ?: return
        val enabled = Preferences.getBoolean(Preferences.KEY_APP_DETAILS_ICON_LAUNCH, false)
        if (!enabled) {
            restore(icon)
            return
        }
        // Refuse a changed host contract instead of replacing a newly added vendor action.
        if (icon !in icons && icon.hasOnClickListeners()) return
        if (launchTarget(host) == null) { restore(icon); return }
        if (icon !in icons) icons[icon] = Original(icon.isClickable, icon.focusable)
        icon.setOnClickListener {
            HookFailurePolicy.open(TAG, "launch application", Unit) {
                if (retired || !Preferences.getBoolean(Preferences.KEY_APP_DETAILS_ICON_LAUNCH, false)) return@open
                // Re-resolve at click time: an app can be disabled/uninstalled while this page lives.
                val current = launchTarget(host) ?: return@open
                host.getSystemService(LauncherApps::class.java)
                    ?.startMainActivity(current.first, current.second, null, null)
            }
        }
        icon.isFocusable = true
    }

    private fun launchTarget(host: Activity): Pair<android.content.ComponentName, UserHandle>? {
        val intent = host.intent ?: return null
        val packageName = intent.getStringExtra("package_name")?.takeIf { it.isNotBlank() } ?: return null
        // This extra is a user ID, including XSpace 999, not an application UID.
        val userId = intent.getIntExtra("miui.intent.extra.USER_ID", Process.myUid() / 100000)
        if (userId < 0) return null
        val user = userHandle.invoke(null, userId) as? UserHandle ?: return null
        val launcher = host.getSystemService(LauncherApps::class.java) ?: return null
        val entry = launcher.getActivityList(packageName, user).firstOrNull() ?: return null
        return entry.componentName to user
    }

    private fun restore(icon: ImageView) {
        val original = icons.remove(icon) ?: return
        icon.setOnClickListener(null)
        icon.isClickable = original.clickable
        icon.focusable = original.focusable
    }

    fun refreshSettings() = StatusIconHostAccess.onMain {
        if (retired || !ready || hookParam.processName != PACKAGE) return@onMain
        Preferences.invalidateRuntimeReadCache()
        icons.keys.toList().forEach { icon ->
            HookFailurePolicy.open(TAG, "refresh icon", Unit) { bindRoot(icon) }
        }
        // Also covers upgrading from an APK without this feature or any saved host state.
        HookFailurePolicy.open(TAG, "recover details activity", Unit) {
            val owner = Class.forName("android.app.ActivityThread")
            val thread = owner.getMethod("currentActivityThread").invoke(null) ?: return@open
            val records = StatusIconHostAccess.read(thread, "mActivities") as? Map<*, *> ?: return@open
            records.values.filterNotNull().forEach { record ->
                val host = StatusIconHostAccess.read(record, "activity") as? Activity ?: return@forEach
                if (!host.isDestroyed && host.javaClass.name == DETAILS) bindRoot(host.window.decorView)
            }
        }
    }

    override fun recoverHotReloadHosts() = refreshSettings()

    override fun onPrepareHotReload() = StatusIconHostAccess.onMain {
        retired = true
        icons.keys.toList().forEach(::restore)
    }
}
