package com.takekazex.hypertweak.hook.rules.securitycenter

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.LinearLayout
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.shared.DigitKeypadShuffle
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Shuffles the digit keys of Security Center's numeric PIN pad (App Lock).
 *
 * `com.miui.applicationlock.widget.MiuiNumericInputView` builds itself in its constructor: four
 * nested row views of three nested key views each, and a key array indexed by key code (0..9 are
 * the digits, 10 and 11 the two bottom-row action keys). Every digit key is shuffled once per pad
 * instance, right after construction; the key array, listeners and the two action keys stay as
 * the host built them. Every Security Center screen that uses this widget is affected (unlock,
 * set and change PIN); the separate `com.miui.auth` PIN widget (privacy password) is not.
 *
 * Live: enabling shuffles living pads, disabling puts their digits back in keypad order. Hot
 * reload restores keypad order on retirement; the next generation finds living pads through the
 * window inspector, which also repairs a pad left shuffled by a build that saved no state.
 */
object AppLockPinScrambleHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_APP_LOCK_SCRAMBLE_PIN, false)
    }

    private const val TAG = "AppLockPinScramble"
    private const val INPUT_VIEW = "com.miui.applicationlock.widget.MiuiNumericInputView"
    private const val KEY_CODES = 12

    private class Contract(val view: Class<*>, val keys: Field, val row: Class<*>)

    private var contract: Contract? by generationState { null }
    @Volatile private var enabled = false
    @Volatile private var retiring = false

    /** Pads seen by this generation; values are unused. */
    private val pads = generationMainResource(WeakHashMap<View, Boolean>()) { it.clear() }
    private val mainHandler = generationMainHandler()

    override fun onHook() {
        retiring = false
        enabled = settingEnabled
        val view = INPUT_VIEW.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, INPUT_VIEW, "class not found")
            return
        }
        val resolved = resolveContract(view) ?: return
        contract = resolved
        view.declaredConstructors.forEach { constructor ->
            constructor.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "new pad", Unit) {
                        val pad = param.thisObject as? View ?: return@open
                        if (retiring) return@open
                        pads[pad] = true
                        if (enabled) apply(resolved, pad, shuffle = true)
                    }
                }
            }
        }
        mainHandler.post { recoverLivingPads() }
        DebugLog.d(TAG, "hooked ${view.simpleName} constructors=${view.declaredConstructors.size} enabled=$enabled")
    }

    /**
     * The pad is a [LinearLayout] with one key array whose component type is a nested [View] class,
     * sized for 12 key codes once built, and four fields of one nested [ViewGroup] row class.
     * Anything else leaves the pad native.
     */
    private fun resolveContract(view: Class<*>): Contract? {
        val fields = view.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        val keyArrays = fields.filter { field ->
            val component = field.type.componentType
            component != null && component.enclosingClass == view && View::class.java.isAssignableFrom(component)
        }
        val rowFields = fields.filter { field ->
            field.type.enclosingClass == view && ViewGroup::class.java.isAssignableFrom(field.type)
        }
        val rowTypes = rowFields.map { it.type }.distinct()
        if (!LinearLayout::class.java.isAssignableFrom(view) || keyArrays.size != 1 ||
            rowFields.size != 4 || rowTypes.size != 1) {
            DebugLog.hookSkipped(TAG, "$INPUT_VIEW contract",
                "keyArrays=${keyArrays.size} rows=${rowFields.size} rowTypes=${rowTypes.size}")
            return null
        }
        return Contract(view, keyArrays[0].apply { isAccessible = true }, rowTypes[0])
    }

    /** Main thread only. [shuffle] false puts the digits back in keypad order. */
    private fun apply(contract: Contract, pad: View, shuffle: Boolean) {
        val group = pad as? ViewGroup ?: return
        val keys = (contract.keys.get(pad) as? Array<*>)?.takeIf { it.size == KEY_CODES } ?: run {
            DebugLog.w(TAG, "key array has an unexpected shape; pad left native")
            return
        }
        val keysByDigit = (0 until DigitKeypadShuffle.SLOTS).map { keys[it] as? View ?: return }
        if (group.childCount != 4) return
        val rows = (0 until 4).map { index ->
            (group.getChildAt(index) as? ViewGroup)?.takeIf { contract.row.isInstance(it) && it.childCount == 3 } ?: run {
                DebugLog.w(TAG, "row $index has an unexpected shape; pad left native")
                return
            }
        }
        // Slots 0..8 are the digit rows; slot 9 is the middle of the bottom row, between the
        // two action keys (key codes 10 and 11), which stay in place.
        val occupants = (0 until 9).map { rows[it / 3].getChildAt(it % 3) } + rows[3].getChildAt(1)
        val actions = setOf(rows[3].getChildAt(0), rows[3].getChildAt(2))
        if (actions != setOf(keys[10], keys[11])) {
            DebugLog.w(TAG, "bottom row has no action key; pad left native")
            return
        }
        val digits = if (shuffle) DigitKeypadShuffle.shuffledDigits() else DigitKeypadShuffle.NATIVE
        if (DigitKeypadShuffle.arrange(occupants, keysByDigit, digits) == null) {
            DebugLog.w(TAG, "digit keys do not match their slots; pad left native")
        }
    }

    private fun recoverLivingPads() {
        val resolved = contract ?: return
        if (retiring) return
        HookFailurePolicy.open(TAG, "recover living pads", Unit) {
            val found = ArrayList<View>()
            WindowInspector.getGlobalWindowViews().forEach { collect(it, resolved.view, found) }
            found.forEach { pads[it] = true }
            pads.keys.toList().forEach { apply(resolved, it, shuffle = enabled) }
        }
    }

    private fun collect(view: View, target: Class<*>, out: MutableList<View>) {
        if (target.isInstance(view)) { out += view; return }
        if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index), target, out)
    }

    /** Accepted-snapshot refresh: shuffle living pads on enable, restore keypad order on disable. */
    fun refreshSettings() {
        val next = settingEnabled
        val changed = next != enabled
        enabled = next
        if (changed && !retiring) mainHandler.post { recoverLivingPads() }
        DebugLog.i(TAG, "settings applied enabled=$next pads=${pads.size}")
    }

    override fun onPrepareHotReload() {
        retiring = true
        val resolved = contract ?: return
        HostAccess.onMain {
            pads.keys.toList().forEach { pad ->
                runCatching { apply(resolved, pad, shuffle = false) }
                    .onFailure { DebugLog.w(TAG, "restore keypad order failed", it) }
            }
        }
    }
}
