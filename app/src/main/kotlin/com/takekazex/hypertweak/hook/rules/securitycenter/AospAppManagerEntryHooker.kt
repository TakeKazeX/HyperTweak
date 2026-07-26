package com.takekazex.hypertweak.hook.rules.securitycenter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Adds an overflow-menu entry to Security Center's app manager that opens Settings' AOSP "All apps"
 * list — SPA route `AllAppList`, served by `com.android.settings.spa.app.AllAppListPageProvider`.
 *
 * The hook is on miuix's `AppCompatActivity.onOptionsMenuViewAdded(Menu, Menu)` rather than on the
 * activity itself, because that is where the end menu is populated; the activity class is checked
 * inside so nothing else in Security Center picks up the entry.
 *
 * **Unverified off-device**: there is no `com.miui.securitycenter` artifact in the
 * reverse-engineering workspace, so every lookup is null-checked and the hooker fails silently.
 */
object AospAppManagerEntryHooker : StaticHooker() {
    private const val TAG = "AospAppManagerEntry"

    private const val APPCOMPAT_ACTIVITY = "miuix.appcompat.app.AppCompatActivity"
    private const val APP_MANAGER_ACTIVITY = "com.miui.appmanager.AppManagerMainActivity"

    private const val MENU_TITLE = "All apps (AOSP)"

    /** miuix's own group id for the end menu; falls back to [Menu.NONE] when absent. */
    private const val END_MENU_GROUP_RES = "miuix_action_end_menu_group"

    /** Arbitrary, only has to be stable and not collide with Security Center's own ids. */
    private const val MENU_ITEM_ID = 0x48545F41

    override val hotReloadMode = HotReloadMode.RECREATE

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_AOSP_APP_MANAGER_ENTRY, false)) {
            DebugLog.hookSkipped(TAG, "app manager menu", "disabled")
            return
        }

        val appCompat = APPCOMPAT_ACTIVITY.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, APPCOMPAT_ACTIVITY, "class not found")
            return
        }

        val method = CompatibleMethodResolver.find(
            appCompat,
            "onOptionsMenuViewAdded",
            parameterTypes = listOf(Menu::class.java, Menu::class.java)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$APPCOMPAT_ACTIVITY#onOptionsMenuViewAdded(Menu,Menu)",
                "method not found"
            )
            return
        }

        runCatching {
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "onOptionsMenuViewAdded", Unit) {
                        val activity = param.thisObject as? Activity ?: return@open
                        if (activity.javaClass.name != APP_MANAGER_ACTIVITY) return@open
                        val endMenu = param.args.getOrNull(1) as? Menu ?: return@open
                        addEntry(activity, endMenu)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$APPCOMPAT_ACTIVITY#onOptionsMenuViewAdded(Menu,Menu)", it)
        }
    }

    private fun addEntry(activity: Activity, menu: Menu) {
        // An empty end menu means miuix has not populated it yet; adding now would be replaced.
        if (menu.size() == 0 || hasEntry(menu)) return

        menu.add(endMenuGroupId(activity), MENU_ITEM_ID, Menu.NONE, MENU_TITLE).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                runCatching { activity.startActivity(createAllAppsIntent()) }
                    .onFailure { DebugLog.w(TAG, "failed to open the AOSP app list", it) }
                true
            }
        }
    }

    private fun hasEntry(menu: Menu): Boolean = (0 until menu.size()).any { index ->
        val item = menu.getItem(index)
        item.itemId == MENU_ITEM_ID || item.title?.toString() == MENU_TITLE
    }

    private fun endMenuGroupId(context: Context): Int = runCatching {
        context.resources.getIdentifier(END_MENU_GROUP_RES, "id", context.packageName)
    }.getOrNull()?.takeIf { it != 0 } ?: Menu.NONE

    private fun createAllAppsIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        setClassName("com.android.settings", "com.android.settings.spa.SpaActivity")
        putExtra("spaActivityDestination", "AllAppList")
        putExtra("sessionSource", "browse")
    }
}
