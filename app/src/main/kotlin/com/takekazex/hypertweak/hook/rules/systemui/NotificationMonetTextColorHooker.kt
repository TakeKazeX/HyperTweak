package com.takekazex.hypertweak.hook.rules.systemui

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps framework-generated notification text neutral instead of following the Monet accent.
 *
 * The framework starts notification palette resolution in
 * `android.app.Notification$Colors.resolvePalette`, but MIUI's SystemUI applies its own
 * Monet-backed notification resources to the final standard notification TextViews later.
 * Both layers are covered here. Colorized and custom notifications are deliberately left
 * untouched because they own their own contrast.
 */
object NotificationMonetTextColorHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "NotificationMonetTextColor"
    private const val COLORS_CLASS = "android.app.Notification\$Colors"
    private const val RESOLVE_PALETTE = "resolvePalette"
    private const val FIELD_TEXT_COLOR = "mTextColor"
    private const val HYBRID_VIEW_CLASS =
        "com.android.systemui.statusbar.notification.row.HybridNotificationView"
    private const val HYBRID_INJECTOR_CLASS =
        "com.android.systemui.statusbar.notification.row.HybridNotificationViewInjectorImpl"
    private const val HYBRID_CONVERSATION_INJECTOR_CLASS =
        "com.android.systemui.statusbar.notification.row.HybridConversationNotificationViewInjectorImpl"
    private const val BIG_TEXT_WRAPPER_CLASS =
        "com.android.systemui.statusbar.notification.row.wrapper.MiuiNotificationBigTextViewWrapper"
    private const val TEMPLATE_WRAPPER_CLASS =
        "com.android.systemui.statusbar.notification.row.wrapper.MiuiNotificationTemplateViewWrapper"

    // Match MIUI's neutral primary text: #ff000000 in light mode and #ffffffff in dark mode.
    private const val LIGHT_TEXT = -16777216
    private const val DARK_TEXT = -1
    private const val LIGHT_SECONDARY_TEXT = 0x99000000.toInt()
    private const val DARK_SECONDARY_TEXT = 0xB3FFFFFF.toInt()

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

        var viewHookCount = 0
        viewHookCount += hookAfter(
            HYBRID_VIEW_CLASS,
            "bind",
            3,
            "notification_neutral_hybrid_bind"
        ) { target, _ ->
            forceHybridText(target)
        }
        viewHookCount += hookAfter(
            HYBRID_INJECTOR_CLASS,
            "updateTextColor",
            2,
            "notification_neutral_hybrid_update"
        ) { _, args ->
            forceHybridText(args.getOrNull(1))
        }
        viewHookCount += hookAfter(
            HYBRID_CONVERSATION_INJECTOR_CLASS,
            "updateTextColor",
            2,
            "notification_neutral_conversation_update"
        ) { _, args ->
            forceHybridText(args.getOrNull(1))
        }
        viewHookCount += hookAfter(
            BIG_TEXT_WRAPPER_CLASS,
            "updateTransparentBgAndTextColor",
            2,
            "notification_neutral_big_text_wrapper"
        ) { target, args ->
            forceWrapperText(target, args.getOrNull(0))
        }
        viewHookCount += hookAfter(
            TEMPLATE_WRAPPER_CLASS,
            "updateTransparentBgAndTextColor",
            2,
            "notification_neutral_template_wrapper"
        ) { target, args ->
            forceWrapperText(target, args.getOrNull(0))
        }

        DebugLog.d(
            TAG,
            "notification text forced to static neutral; framework=true, viewHooks=$viewHookCount"
        )
    }

    private fun hookAfter(
        className: String,
        methodName: String,
        parameterCount: Int,
        hookId: String,
        action: (Any?, Array<Any?>) -> Unit
    ): Int {
        val targetClass = className.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "$className#$methodName", "class not found")
            return 0
        }
        val method = targetClass.declaredMethods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == parameterCount
        }?.apply { isAccessible = true } ?: run {
            DebugLog.hookSkipped(TAG, "$className#$methodName/$parameterCount", "method not found")
            return 0
        }
        method.hook(hookId) {
            after { param ->
                HookFailurePolicy.open(TAG, "$hookId after", Unit) {
                    if (!isEnabled()) return@open
                    action(param.thisObject, param.args)
                }
            }
        }
        return 1
    }

    private fun forceHybridText(target: Any?) {
        val view = target as? View ?: return
        val primary = neutralFor(view.context)
        val secondary = secondaryFor(view.context)
        invokeTextViewGetter(target, "getTitleView")?.setTextColor(primary)
        invokeTextViewGetter(target, "getTextView")?.setTextColor(secondary)
        invokeTextViewGetter(target, "getConversationSenderNameView")?.setTextColor(secondary)
    }

    private fun forceWrapperText(target: Any?, entry: Any?) {
        if (target == null || isColorized(entry)) return
        val context = readField(target, "mContext") as? Context
            ?: findTextView(target)?.context
            ?: return
        val transparentBackground = readField(target, "mIsTransparentBg") as? Boolean == true
        val primary = if (transparentBackground) DARK_TEXT else neutralFor(context)
        val secondary = if (transparentBackground) DARK_SECONDARY_TEXT else secondaryFor(context)

        setFieldTextColor(target, "mTitle", primary)
        setFieldTextColor(target, "mBigText", secondary)
        setFieldTextColor(target, "mText", secondary)
        setFieldTextColor(target, "mSubText", secondary)
        setFieldTextColor(target, "mTime", secondary)
        (readField(target, "mActionsContainer") as? ViewGroup)?.let {
            setTextColors(it, secondary)
        }
    }

    private fun neutralFor(context: Context): Int = if (isNight(context)) DARK_TEXT else LIGHT_TEXT

    private fun secondaryFor(context: Context): Int =
        if (isNight(context)) DARK_SECONDARY_TEXT else LIGHT_SECONDARY_TEXT

    private fun invokeTextViewGetter(target: Any, name: String): TextView? =
        runCatching { target.javaClass.getMethod(name).invoke(target) as? TextView }.getOrNull()

    private fun setFieldTextColor(target: Any, name: String, color: Int) {
        (readField(target, name) as? TextView)?.setTextColor(color)
    }

    private fun findTextView(target: Any): TextView? =
        target.javaClass.declaredFields.asSequence()
            .mapNotNull { field ->
                runCatching {
                    field.apply { isAccessible = true }.get(target) as? TextView
                }.getOrNull()
            }
            .firstOrNull()

    private fun setTextColors(view: View, color: Int) {
        if (view is TextView) {
            view.setTextColor(color)
        } else if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setTextColors(view.getChildAt(index), color)
            }
        }
    }

    private fun readField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val currentType = type
            val value = runCatching {
                currentType.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }.getOrNull()
            if (value != null) return value
            type = currentType.superclass
        }
        return null
    }

    private fun isColorized(entry: Any?): Boolean {
        val statusBarNotification = entry?.let { readField(it, "mSbn") } ?: return false
        val notification = runCatching {
            statusBarNotification.javaClass.getMethod("getNotification")
                .invoke(statusBarNotification) as? Notification
        }.getOrNull() ?: return false
        val colorized = runCatching {
            notification.javaClass.getMethod("isColorized").invoke(notification) as? Boolean
        }.getOrDefault(false)
        return colorized == true && notification.color != 0
    }

    private fun isNight(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun isEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_NOTIFICATION_MONET_TEXT_COLOR, false)
}
