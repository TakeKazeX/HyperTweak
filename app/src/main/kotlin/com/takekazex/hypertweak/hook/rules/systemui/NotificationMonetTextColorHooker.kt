package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.res.Configuration
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps framework-generated notification text neutral (black in light mode, white in dark mode)
 * instead of following the Monet accent color.
 *
 * ## Root cause (verified on the OS4.0.0.24 build)
 *
 * Every framework-standard notification text view is colored through the single source
 * `android.app.Notification$Colors.getTextColor()` → the `mTextColor` field, which is assigned in
 * `resolvePalette(Context,int,boolean,boolean)`. On the standard (non-colorized) branch that method
 * does:
 *
 *     this.mTextColor = ctx.getColor(R.color.materialColorOnSurface);
 *
 * At runtime SystemUI's Monet overlay (`com.android.systemui.monet.DynamicColors.generateSysUINames`)
 * overrides the `system_on_surface_*` resources that back **both** `materialColorOnSurface` and
 * `notification_primary_text_color_current`. Redirecting one resource to the other is therefore a
 * no-op — the text keeps the wallpaper-derived accent, which is what this hook must avoid.
 *
 * ## Approach
 *
 * Inject a genuinely static, configuration-aware neutral directly into `mTextColor` right after
 * `resolvePalette` recomputes the palette. The after-hook runs on every invocation (including the
 * cached early return that `resolvePalette` applies), so the neutral always wins over the Monet
 * lookup. `getTextColor()` returns `mTextColor` unchanged, so the single authoritative point is
 * enough — no context wrapper, no forged resource table, no separate builder/binder channel.
 *
 * The night-mode decision is taken from the exact context the framework used to resolve
 * `materialColorOnSurface`, so the injected black/white always matches the light/dark rendering the
 * framework itself selected (including MIUI's dark-wrapped heads-up/public context).
 *
 * Colorized and custom notifications are deliberately left untouched — they own their own contrast
 * and would otherwise risk unreadable text.
 */
object NotificationMonetTextColorHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "NotificationMonetTextColor"
    private const val COLORS_CLASS = "android.app.Notification\$Colors"
    private const val RESOLVE_PALETTE = "resolvePalette"
    private const val FIELD_TEXT_COLOR = "mTextColor"

    // Match MIUI's own neutral notification text (R.color.notification_primary_text_color_light):
    // #ff000000 in light mode, #ffffffff in dark mode.
    private const val LIGHT_TEXT = -16777216 // 0xFF000000
    private const val DARK_TEXT = -1 // 0xFFFFFFFF

    @Volatile
    private var textColorField: java.lang.reflect.Field? = null

    private val redirectLogCount = AtomicInteger()

    override fun onPrepareHotReload() {
        textColorField = null
        redirectLogCount.set(0)
    }

    override fun onHook() {
        val colorsClass = COLORS_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, COLORS_CLASS, "class not found")
            return
        }
        val resolvePalette = colorsClass.declaredMethods.firstOrNull { method ->
            method.name == RESOLVE_PALETTE && method.parameterTypes.contentEquals(
                arrayOf(
                    Context::class.java,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
            )
        }?.apply { isAccessible = true } ?: run {
            DebugLog.hookSkipped(TAG, "$COLORS_CLASS#$RESOLVE_PALETTE", "method not found")
            return
        }

        textColorField = colorsClass.declaredFields.firstOrNull {
            it.name == FIELD_TEXT_COLOR && it.type == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true } ?: run {
            DebugLog.hookSkipped(TAG, "$COLORS_CLASS#$FIELD_TEXT_COLOR", "field not found")
            return
        }

        deoptimize(resolvePalette)
        resolvePalette.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "$COLORS_CLASS#$RESOLVE_PALETTE after", Unit) {
                    if (!isEnabled()) return@open
                    // Keep colorized palettes (and the app-supplied contrast they compute) intact.
                    if (param.args.getOrNull(2) as? Boolean == true) return@open
                    val context = param.args.getOrNull(0) as? Context ?: return@open
                    val neutral = neutralFor(context)
                    textColorField?.setInt(param.thisObject, neutral)
                    if (redirectLogCount.getAndIncrement() < 3) {
                        DebugLog.d(
                            TAG,
                            "resolvePalette forced neutral text=0x${neutral.toUInt().toString(16)} " +
                                "night=${isNight(context)}"
                        )
                    }
                }
            }
        }

        DebugLog.d(TAG, "framework notification text forced to static neutral (black/white by configuration)")
    }

    private fun neutralFor(context: Context): Int = if (isNight(context)) DARK_TEXT else LIGHT_TEXT

    private fun isNight(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun isEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_NOTIFICATION_MONET_TEXT_COLOR, false)
}
