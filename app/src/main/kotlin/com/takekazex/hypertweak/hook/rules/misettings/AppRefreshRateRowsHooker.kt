package com.takekazex.hypertweak.hook.rules.misettings

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SpinnerAdapter
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.ThreadActivation
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.AppRefreshRates
import com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Turns each app switch on MiSettings' "custom high refresh apps" page into a refresh-rate
 * drop-down.
 *
 * The switch is replaced by the host's own Miuix drop-down (the one `DropDownPreference` uses):
 * it shows the app's rate in the row, and tapping the row opens the HyperOS drop-down menu of the
 * panel's rates. The top rate and 60 Hz are Xiaomi's own on/off state and are applied through the
 * host's row click; rates in between are stored in [AppRefreshRates] for PowerKeeper and keep the
 * app switched on.
 */
internal object AppRefreshRateRowsHooker : StaticHooker() {
    private const val TAG = "AppRefreshRateRows"
    private const val VALUE_TAG = "hypertweak.app_refresh_rate"
    /** PowerKeeper's hard-coded rate for an app whose switch is off. */
    private const val OFF_RATE = 60

    private val enabled by hookSetting(false) {
        Preferences.getBoolean(MiSettingsSettingsKeys.KEY_PER_APP_REFRESH_RATES, false)
    }

    private class DropDown(
        val spinner: Constructor<*>,
        val setAdapter: Method,
        val preference: Constructor<*>,
        val content: Field,
        val entries: Field,
        val wrapper: Constructor<*>,
        val checkedCallback: Class<*>,
        val layout: Int,
    )

    private class Targets(
        val bind: Method, val click: Method,
        val item: Field, val button: Field, val row: Field,
        val pkg: Field, val checked: Field,
        val dropDown: DropDown,
    )

    private val rows = generationMainResource(WeakHashMap<View, Any>()) { it.clear() }
    private val toggling = ThreadActivation()

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(TAG, resolve = {
            val index = DexRuntime.operands(apk)
            val c = AppRefreshRateRowsContract.resolve(index) ?: return@prepareDexTargets null
            val d = MiuixDropDownContract.resolve(index) ?: return@prepareDexTargets null
            fun field(ref: org.jf.dexlib2.iface.reference.FieldReference) = index.field(ref, classLoader)?.apply { isAccessible = true }
            val spinner = DexOperandIndex.type(MiuixDropDownContract.SPINNER, classLoader)
            val wrapper = DexOperandIndex.type(d.wrapper.definingClass, classLoader)
            val callback = DexOperandIndex.type(d.checkedCallback, classLoader)
            val dropDown = DropDown(
                spinner = spinner.getConstructor(Context::class.java),
                setAdapter = spinner.getMethod("setAdapter", SpinnerAdapter::class.java),
                preference = DexOperandIndex.type(MiuixDropDownContract.PREFERENCE, classLoader).getConstructor(Context::class.java),
                content = field(d.content) ?: return@prepareDexTargets null,
                entries = field(d.entries) ?: return@prepareDexTargets null,
                wrapper = wrapper.getDeclaredConstructor(Context::class.java, Int::class.javaPrimitiveType,
                    ArrayAdapter::class.java, callback).apply { isAccessible = true },
                checkedCallback = callback,
                layout = field(d.layout)?.getInt(null) ?: return@prepareDexTargets null,
            )
            Targets(
                bind = index.method(c.bind, classLoader) ?: return@prepareDexTargets null,
                click = index.method(c.click, classLoader) ?: return@prepareDexTargets null,
                item = field(c.item) ?: return@prepareDexTargets null,
                button = field(c.button) ?: return@prepareDexTargets null,
                row = field(c.row) ?: return@prepareDexTargets null,
                pkg = field(c.pkg) ?: return@prepareDexTargets null,
                checked = field(c.checked) ?: return@prepareDexTargets null,
                dropDown = dropDown,
            )
        }) { targets ->
            targets.bind.hook("misettings_app_refresh_rate_bind") {
                after { param ->
                    val holder = param.thisObject ?: return@after
                    if (generationRetired) return@after
                    HookFailurePolicy.open(TAG, "render app refresh rate", Unit) { render(targets, holder) }
                }
            }
            targets.click.hook("misettings_app_refresh_rate_click") {
                intercept { chain ->
                    val row = chain.args.firstOrNull() as? View
                    val holder = row?.let { rows[it] }
                    if (generationRetired || !enabled || holder == null || toggling.active) return@intercept chain.proceed()
                    val spinner = (targets.button.get(holder) as? View)?.parent?.let { it as? ViewGroup }
                        ?.findViewWithTag<View>(VALUE_TAG)
                    if (spinner == null || spinner.visibility != View.VISIBLE) return@intercept chain.proceed()
                    spinner.performClick()
                    null
                }
            }
        }
    }

    private fun render(targets: Targets, holder: Any) {
        val row = targets.row.get(holder) as? View ?: return
        val button = targets.button.get(holder) as? View ?: return
        val parent = button.parent as? ViewGroup ?: return
        val existing = parent.findViewWithTag<AdapterView<*>>(VALUE_TAG)
        if (!enabled) {
            rows.remove(row)
            button.visibility = View.VISIBLE
            existing?.visibility = View.GONE
            return
        }
        val item = targets.item.get(holder) ?: return
        val pkg = targets.pkg.get(item) as? String ?: return
        val rates = panelRates(row.context).takeIf { it.size >= 2 } ?: return
        val spinner = existing?.takeIf { it.count == rates.size }
            ?: dropDown(targets.dropDown, row.context, rates)?.also { created ->
                existing?.let(parent::removeView)
                parent.addView(created, parent.indexOfChild(button) + 1,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            } ?: return
        rows[row] = holder
        spinner.onItemSelectedListener = null
        spinner.setSelection(rates.indexOf(rateOf(row.context, targets, item, pkg, rates)))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(view: AdapterView<*>?, selected: View?, position: Int, id: Long) {
                val current = rows[row] ?: return
                HookFailurePolicy.open(TAG, "apply app refresh rate", Unit) { select(targets, current, rates, position) }
            }
            override fun onNothingSelected(view: AdapterView<*>?) = Unit
        }
        spinner.visibility = View.VISIBLE
        button.visibility = View.GONE
    }

    /** The host's Miuix drop-down: a spinner over its checkable double-line adapter. */
    private fun dropDown(d: DropDown, context: Context, rates: List<Int>): AdapterView<*>? {
        val spinner = d.spinner.newInstance(context) as? AdapterView<*> ?: return null
        val content = d.content.get(d.preference.newInstance(context)) as? ArrayAdapter<*> ?: return null
        d.entries.set(content, rates.map { label(it) }.toTypedArray<CharSequence>())
        val checked = Proxy.newProxyInstance(d.checkedCallback.classLoader, arrayOf(d.checkedCallback)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "HyperTweakRateChecked"
                else -> (args?.getOrNull(0) as? Int) == spinner.selectedItemPosition
            }
        }
        d.setAdapter.invoke(spinner, d.wrapper.newInstance(context, d.layout, content, checked))
        spinner.tag = VALUE_TAG
        // As in DropDownPreference: the row owns the touch and opens the menu.
        spinner.isClickable = false
        spinner.isLongClickable = false
        spinner.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return spinner
    }

    private fun select(targets: Targets, holder: Any, rates: List<Int>, position: Int) {
        val row = targets.row.get(holder) as? View ?: return
        val item = targets.item.get(holder) ?: return
        val pkg = targets.pkg.get(item) as? String ?: return
        val rate = rates.getOrNull(position) ?: return
        if (rate == rateOf(row.context, targets, item, pkg, rates)) return
        val resolver = row.context.contentResolver
        val stored = AppRefreshRates.parse(Settings.Secure.getString(resolver, AppRefreshRates.SECURE_KEY)).toMutableMap()
        if (rate == rates.first() || rate == OFF_RATE) stored.remove(pkg) else stored[pkg] = rate
        Settings.Secure.putString(resolver, AppRefreshRates.SECURE_KEY, AppRefreshRates.format(stored))
        val on = rate != OFF_RATE
        if (targets.checked.getBoolean(item) != on) toggling.within { row.performClick() }
        DebugLog.i(TAG, "$pkg refresh rate set to $rate Hz")
    }

    private fun rateOf(context: Context, targets: Targets, item: Any, pkg: String, rates: List<Int>): Int {
        if (!targets.checked.getBoolean(item)) return OFF_RATE
        val stored = AppRefreshRates.parse(Settings.Secure.getString(context.contentResolver, AppRefreshRates.SECURE_KEY))
        return stored[pkg]?.takeIf { it in rates } ?: rates.first()
    }

    /** Panel rates at the current resolution, highest first, always including the host's 60 Hz. */
    private fun panelRates(context: Context): List<Int> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return emptyList()
        val current = display.mode
        return (display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate.roundToInt() } + OFF_RATE)
            .filter { it >= OFF_RATE }.distinct().sortedDescending()
    }

    private fun label(rate: Int) = "$rate Hz"
}
