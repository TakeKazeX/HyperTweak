package com.takekazex.hypertweak.hook.rules.powerkeeper

import android.content.Context
import android.provider.Settings
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.AppRefreshRates
import com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Applies the per-app refresh rates chosen in MiSettings when an app comes to the foreground.
 *
 * Only custom mode is affected (the active per-app map is the user's custom map, which PowerKeeper
 * selects only at the highest global rate), and only for apps whose Xiaomi switch is on (absent
 * from that map). For that one foreground decision the chosen rate is placed in the map, so every
 * scene that natively wins over the per-app map (games, video, camera, input...) still wins, and
 * PowerKeeper's own cap at the global rate still applies. The map is restored before returning.
 */
internal object AppRefreshRateHooker : StaticHooker() {
    private const val TAG = "AppRefreshRate"
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(MiSettingsSettingsKeys.KEY_PER_APP_REFRESH_RATES, false)
    }

    private data class Targets(val foreground: Method, val active: Field, val custom: Field, val pkg: Field)

    @Volatile private var cached: Pair<String?, Map<String, Int>> = null to emptyMap()

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(TAG, resolve = {
            val index = DexRuntime.operands(apk)
            val contract = AppRefreshRateContract.resolve(index) ?: return@prepareDexTargets null
            Targets(
                foreground = index.method(contract.foreground, classLoader)?.apply { isAccessible = true } ?: return@prepareDexTargets null,
                active = index.field(contract.active, classLoader)?.apply { isAccessible = true } ?: return@prepareDexTargets null,
                custom = index.field(contract.custom, classLoader)?.apply { isAccessible = true } ?: return@prepareDexTargets null,
                pkg = index.field(contract.foregroundPackage, classLoader)?.apply { isAccessible = true } ?: return@prepareDexTargets null,
            )
        }) { targets ->
            targets.foreground.hook("powerkeeper_app_refresh_rate") {
                intercept { chain ->
                    val setting = chain.thisObject
                    val info = chain.args.firstOrNull()
                    if (generationRetired || !enabled || setting == null || info == null) return@intercept chain.proceed()
                    val placed = HookFailurePolicy.open(TAG, "place app refresh rate", null) { place(targets, setting, info) }
                        ?: return@intercept chain.proceed()
                    try {
                        chain.proceed()
                    } finally {
                        HookFailurePolicy.open(TAG, "restore app refresh rate", Unit) { placed.first.remove(placed.second) }
                    }
                }
            }
        }
    }

    /** Returns the map and package to restore, or null when this decision stays native. */
    private fun place(targets: Targets, setting: Any, info: Any): Pair<MutableMap<Any?, Any?>, String>? {
        val pkg = targets.pkg.get(info) as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val active = targets.active.get(setting) as? MutableMap<Any?, Any?> ?: return null
        if (active !== targets.custom.get(setting) || active.containsKey(pkg)) return null
        val rate = rates()[pkg] ?: return null
        active[pkg] = rate
        DebugLog.d(TAG) { "foreground $pkg uses $rate Hz" }
        return active to pkg
    }

    private fun rates(): Map<String, Int> {
        val context = currentApplication() ?: return emptyMap()
        val raw = Settings.Secure.getString(context.contentResolver, AppRefreshRates.SECURE_KEY)
        val last = cached
        if (last.first == raw) return last.second
        return AppRefreshRates.parse(raw).also { cached = raw to it }
    }

    private val currentApplicationMethod by lazy {
        Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
    }

    private fun currentApplication(): Context? = currentApplicationMethod.invoke(null) as? Context
}
