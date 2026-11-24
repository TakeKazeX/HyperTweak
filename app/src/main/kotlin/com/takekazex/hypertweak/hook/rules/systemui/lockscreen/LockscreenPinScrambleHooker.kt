package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.shared.DigitKeypadShuffle
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Shuffles the digit keys of the lock-screen PIN bouncer every time it appears.
 *
 * `KeyguardPINView.onFinishInflate()` builds two position matrices over its keys: `mViews`
 * (6 rows, drives the appear/disappear animation) and `mNumViews` (4 rows). Rows 1..3 of
 * `mViews` are the digit rows, shared as the same arrays with `mNumViews[0..2]`; the 0 key is
 * `mViews[4][1]`, also `mNumViews[3][1]`. The digit of each `NumPadKey` is its `mDigit`, and
 * the base view's button array is indexed by digit, so moving keys keeps input intact. Keys are
 * moved between their own slots with [DigitKeypadShuffle] and both matrices are rewritten, so the
 * host animates the shuffled positions. Delete, verify, emergency and cancel keys stay in place.
 *
 * A new order is drawn after inflation and before every `startAppearAnimation()`. Only the
 * numeric PIN bouncer is affected; pattern, password and SIM PIN use other views.
 *
 * Live: enabling shuffles living PIN views, disabling restores keypad order. Retirement restores
 * keypad order; the next generation recovers living views from the window snapshot. Keypad order
 * comes from the keys' digits, so a view left shuffled by a build that saved no state is repaired.
 */
object LockscreenPinScrambleHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_SCRAMBLE_PIN, false)
    }

    private const val TAG = "LockscreenPinScramble"
    private const val PIN_VIEW = "com.android.keyguard.KeyguardPINView"
    private const val NUM_PAD_KEY = "com.android.keyguard.NumPadKey"

    private class Contract(val pinView: Class<*>, val key: Class<*>, val digit: Field, val views: Field, val numViews: Field)

    init {
        hostRecovery(HostRecoveryPhase.WINDOWS) { scope -> recover(scope.views) }
    }

    private var contract: Contract? by generationState { null }
    @Volatile private var enabled = false
    @Volatile private var retiring = false

    /** PIN views seen by this generation; values are unused. */
    private val pinViews = generationMainResource(WeakHashMap<View, Boolean>()) { it.clear() }
    private val mainHandler = generationMainHandler()

    override fun onHook() {
        if (!isMainProcess) return
        retiring = false
        enabled = settingEnabled
        val pinView = PIN_VIEW.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, PIN_VIEW, "class not found")
            return
        }
        val resolved = resolveContract(pinView) ?: return
        val inflate = declaredNoArg(pinView, "onFinishInflate")
        val appear = declaredNoArg(pinView, "startAppearAnimation")
        if (inflate == null || appear == null) {
            DebugLog.hookSkipped(TAG, "$PIN_VIEW lifecycle", "onFinishInflate=${inflate != null} startAppearAnimation=${appear != null}")
            return
        }
        contract = resolved
        inflate.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "inflated PIN view", Unit) {
                    val view = param.thisObject as? View ?: return@open
                    if (retiring) return@open
                    pinViews[view] = true
                    if (enabled) apply(resolved, view, shuffle = true)
                }
            }
        }
        appear.hook {
            before { param ->
                HookFailurePolicy.open(TAG, "PIN view appears", Unit) {
                    val view = param.thisObject as? View ?: return@open
                    if (retiring) return@open
                    pinViews[view] = true
                    if (enabled) apply(resolved, view, shuffle = true)
                }
            }
        }
        DebugLog.d(TAG, "hooked ${pinView.simpleName} enabled=$enabled")
    }

    private fun declaredNoArg(owner: Class<*>, name: String): Method? = owner.declaredMethods.singleOrNull {
        it.name == name && it.parameterCount == 0 && it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
    }

    /** Both matrices are `View[][]` instance fields and every key carries an `int mDigit`. */
    private fun resolveContract(pinView: Class<*>): Contract? = runCatching {
        val matrix = Array<Array<View>>::class.java
        val key = NUM_PAD_KEY.toClassOrNull() ?: error("NumPadKey not found")
        require(View::class.java.isAssignableFrom(key)) { "NumPadKey is not a View" }
        val digit = HostAccess.requireDeclaredField(key, "mDigit", accessible = true)
        require(digit.type == Int::class.javaPrimitiveType) { "mDigit is ${digit.type}" }
        fun matrixField(name: String) = HostAccess.requireDeclaredField(pinView, name, accessible = true).also {
            require(it.type == matrix && !Modifier.isStatic(it.modifiers)) { "$name is ${it.type}" }
        }
        Contract(pinView, key, digit, matrixField("mViews"), matrixField("mNumViews"))
    }.onFailure { DebugLog.hookSkipped(TAG, "$PIN_VIEW contract", it.message ?: it.javaClass.simpleName) }.getOrNull()

    /**
     * Main thread only. [shuffle] false puts the digits back in keypad order. Any matrix or key
     * shape other than the verified one leaves the view untouched.
     */
    private fun apply(contract: Contract, view: View, shuffle: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val views = contract.views.get(view) as? Array<Array<View?>?> ?: return
        @Suppress("UNCHECKED_CAST")
        val numViews = contract.numViews.get(view) as? Array<Array<View?>?> ?: return
        val shaped = views.size == 6 && (1..4).all { views[it]?.size == 3 } && numViews.size == 4 &&
            numViews[3]?.size == 3 && (0..2).all { numViews[it] === views[it + 1] } &&
            numViews[3]!![1] === views[4]!![1]
        if (!shaped) {
            DebugLog.w(TAG, "PIN matrices have an unexpected shape; view left native")
            return
        }
        val occupants = (0 until 9).map { views[1 + it / 3]!![it % 3] } + views[4]!![1]
        val keys = occupants.map { key -> key?.takeIf { contract.key.isInstance(it) } }
        if (keys.any { it == null }) {
            DebugLog.w(TAG, "PIN slots hold non-digit keys; view left native")
            return
        }
        val byDigit = keys.filterNotNull().associateBy { contract.digit.getInt(it) }
        if (byDigit.keys != (0..9).toSet()) {
            DebugLog.w(TAG, "PIN digits are ${byDigit.keys.sorted()}; view left native")
            return
        }
        val digits = if (shuffle) DigitKeypadShuffle.shuffledDigits() else DigitKeypadShuffle.NATIVE
        val next = DigitKeypadShuffle.arrange(keys.filterNotNull(), (0..9).map { byDigit.getValue(it) }, digits) ?: run {
            DebugLog.w(TAG, "PIN keys are not attached to their rows; view left native")
            return
        }
        for (slot in 0 until 9) views[1 + slot / 3]!![slot % 3] = next[slot]
        views[4]!![1] = next[9]
        numViews[3]!![1] = next[9]
    }

    private fun recover(views: List<View>) {
        val resolved = contract ?: return
        if (retiring) return
        views.filter { resolved.pinView.isInstance(it) }.forEach { pinViews[it] = true }
        pinViews.keys.toList().forEach { view ->
            HookFailurePolicy.open(TAG, "recover PIN view", Unit) { apply(resolved, view, shuffle = enabled) }
        }
    }

    /** Accepted-snapshot refresh: shuffle living PIN views on enable, restore keypad order on disable. */
    fun refreshSettings() {
        val next = settingEnabled
        val changed = next != enabled
        enabled = next
        if (changed && !retiring && contract != null) mainHandler.post { recover(emptyList()) }
        DebugLog.i(TAG, "settings applied enabled=$next views=${pinViews.size}")
    }

    override fun onPrepareHotReload() {
        retiring = true
        val resolved = contract ?: return
        HostAccess.onMain {
            pinViews.keys.toList().forEach { view ->
                runCatching { apply(resolved, view, shuffle = false) }
                    .onFailure { DebugLog.w(TAG, "restore keypad order failed", it) }
            }
        }
    }
}
