package com.takekazex.hypertweak.hook.rules.misettings

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.ThreadActivation
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * Adds the panel's other real refresh rates to the MiSettings refresh-rate drop-down.
 *
 * The device feature list usually carries only the lowest and highest rate. Rates come from the
 * default display's modes at the current resolution and never exceed the native maximum, so the
 * page's "maximum refresh rate" label stays on the same value. Only the page's own read is
 * changed; search, restore and AI-action readers keep the native list.
 */
internal object RefreshRateListHooker : StaticHooker() {
    private const val TAG = "RefreshRateList"
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(MiSettingsSettingsKeys.KEY_MORE_REFRESH_RATES, false)
    }

    private data class Targets(val rates: Method, val page: Method)

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(TAG, resolve = {
            val index = DexRuntime.operands(apk)
            val contract = RefreshRateListContract.resolve(index) ?: return@prepareDexTargets null
            Targets(
                rates = index.method(contract.rates, classLoader) ?: return@prepareDexTargets null,
                page = index.method(contract.page, classLoader) ?: return@prepareDexTargets null,
            )
        }) { (rates, page) ->
            val building = ThreadActivation()
            page.hook("misettings_refresh_rate_page") {
                intercept { chain ->
                    if (enabled && !generationRetired) building.within { chain.proceed() } else chain.proceed()
                }
            }
            rates.hook("misettings_refresh_rate_list") {
                after { param ->
                    if (generationRetired || !enabled || !building.active) return@after
                    val native = param.result as? IntArray ?: return@after
                    HookFailurePolicy.open(TAG, "extend refresh rates", Unit) {
                        param.result = withPanelRates(native)
                    }
                }
            }
        }
    }

    internal fun merge(native: IntArray, panel: Collection<Int>): IntArray {
        val ceiling = native.maxOrNull() ?: return native
        val extra = panel.filter { it in 1..ceiling && it !in native }
        return if (extra.isEmpty()) native else (native.toList() + extra).distinct().toIntArray()
    }

    private fun withPanelRates(native: IntArray): IntArray {
        val context = currentApplication() ?: return native
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return native
        val current = display.mode
        val panel = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate.roundToInt() }
        return merge(native, panel).also {
            DebugLog.d(TAG) { "refresh rates: native=${native.toList()} panel=$panel shown=${it.toList()}" }
        }
    }

    private val currentApplicationMethod by lazy {
        Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
    }

    private fun currentApplication(): Context? = currentApplicationMethod.invoke(null) as? Context
}
