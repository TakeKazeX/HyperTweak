package com.takekazex.hypertweak.hook.rules.systemui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHostAccess
import com.takekazex.hypertweak.util.DebugLog
import java.util.WeakHashMap

/** Restores the framework notification typefaces after MIUI's notification bindings run. */
object NotificationFontWeightHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    private const val TAG = "NotificationFontWeight"
    private const val HYBRID_VIEW_CLASS =
        "com.android.systemui.statusbar.notification.row.HybridNotificationView"
    private const val HYBRID_CONVERSATION_VIEW_CLASS =
        "com.android.systemui.statusbar.notification.row.HybridConversationNotificationView"
    private const val NATIVE_TEMPLATE_WRAPPER_CLASS =
        "com.android.systemui.statusbar.notification.row.wrapper.NotificationTemplateViewWrapper"
    private const val BIG_TEXT_WRAPPER_CLASS =
        "com.android.systemui.statusbar.notification.row.wrapper.MiuiNotificationBigTextViewWrapper"
    private const val TEMPLATE_WRAPPER_CLASS =
        "com.android.systemui.statusbar.notification.row.wrapper.MiuiNotificationTemplateViewWrapper"

    private const val TITLE_WEIGHT = 700
    private const val BODY_WEIGHT = 400
    private const val ACTION_WEIGHT = 500

    private val baseTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val titleTypeface = Typeface.create(baseTypeface, TITLE_WEIGHT, false)
    private val bodyTypeface = Typeface.create(baseTypeface, BODY_WEIGHT, false)
    private val actionTypeface = Typeface.create(baseTypeface, ACTION_WEIGHT, false)

    @Volatile private var retiring = false
    private val originals = WeakHashMap<TextView, Typeface?>()
    private val applied = WeakHashMap<TextView, Int>()

    override fun saveHotReloadState(): Any = StatusIconHostAccess.onMain {
        applied.entries.map { (view, weight) -> listOf(view, weight) }
    }
    override fun restoreHotReloadState(state: Any?) {
        StatusIconHostAccess.onMain {
            if (!isEnabled()) return@onMain
            (state as? List<*>)?.filterIsInstance<List<*>>()?.forEach { item ->
                val view = item.getOrNull(0) as? TextView ?: return@forEach
                val weight = item.getOrNull(1) as? Int ?: return@forEach
                applyTypeface(view, Typeface.create(baseTypeface, weight, false), weight)
            }
        }
    }
    override fun onPrepareHotReload() {
        retiring = true
        StatusIconHostAccess.onMain {
            originals.forEach { (view, typeface) -> view.typeface = typeface }
            originals.clear()
            applied.clear()
        }
    }
    internal fun recoverHybrid(view: View) {
        if (isEnabled() && generateSequence<Class<*>>(view.javaClass) { it.superclass }
            .any { it.name == HYBRID_VIEW_CLASS }) forceHybridTypeface(view)
    }
    private fun applyTypeface(view: TextView, typeface: Typeface, weight: Int) {
        if (!isEnabled()) return
        if (!originals.containsKey(view)) originals[view] = view.typeface
        applied[view] = weight
        view.typeface = typeface
    }

    override fun onHook() {
        retiring = false
        var hookCount = 0
        hookCount += hookAfter(HYBRID_VIEW_CLASS, "bind", 3, "notification_font_hybrid_bind") { target, _ ->
            forceHybridTypeface(target)
        }
        hookCount += hookAfter(
            HYBRID_CONVERSATION_VIEW_CLASS,
            "onFinishInflate",
            0,
            "notification_font_conversation_inflate"
        ) { target, _ ->
            forceHybridTypeface(target)
        }
        hookCount += hookAfter(
            NATIVE_TEMPLATE_WRAPPER_CLASS,
            "onContentUpdated",
            1,
            "notification_font_native_template_updated"
        ) { target, _ ->
            forceNativeWrapperTypeface(target)
        }
        hookCount += hookAfter(
            BIG_TEXT_WRAPPER_CLASS,
            "onContentUpdated",
            1,
            "notification_font_miui_big_text_updated"
        ) { target, _ ->
            forceMiuiWrapperTypeface(target)
        }
        hookCount += hookAfter(
            TEMPLATE_WRAPPER_CLASS,
            "onContentUpdated",
            1,
            "notification_font_miui_template_updated"
        ) { target, _ ->
            forceMiuiWrapperTypeface(target)
        }
        DebugLog.d(TAG, "notification native typeface hooks installed=$hookCount")
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

    private fun forceHybridTypeface(target: Any?) {
        val view = target as? View ?: return
        invokeTextViewGetter(target, "getTitleView")?.let { applyTypeface(it, titleTypeface, TITLE_WEIGHT) }
        invokeTextViewGetter(target, "getTextView")?.let { applyTypeface(it, bodyTypeface, BODY_WEIGHT) }
        invokeTextViewGetter(target, "getConversationSenderNameView")?.let { applyTypeface(it, bodyTypeface, BODY_WEIGHT) }
        // MIUI's summarization branch explicitly applies Typeface style=2 (italic) to content.
        view.invalidate()
    }

    private fun forceNativeWrapperTypeface(target: Any?) {
        if (target == null) return
        setFieldTypeface(target, "mTitle", titleTypeface)
        setFieldTypeface(target, "mAltTitle", titleTypeface)
        setFieldTypeface(target, "mText", bodyTypeface)
        setFieldTypeface(target, "mBigtext", bodyTypeface)
        (readField(target, "mActions") as? ViewGroup)?.let {
            setTextTypefaces(it, actionTypeface)
        }
    }

    private fun forceMiuiWrapperTypeface(target: Any?) {
        if (target == null) return
        setFieldTypeface(target, "mTitle", titleTypeface)
        setFieldTypeface(target, "mBigText", bodyTypeface)
        setFieldTypeface(target, "mText", bodyTypeface)
        setFieldTypeface(target, "mSubText", bodyTypeface)
        setFieldTypeface(target, "mTime", bodyTypeface)
        (readField(target, "mActionsContainer") as? ViewGroup)?.let {
            setTextTypefaces(it, actionTypeface)
        }
    }

    private fun invokeTextViewGetter(target: Any, name: String): TextView? =
        runCatching { target.javaClass.getMethod(name).invoke(target) as? TextView }.getOrNull()

    private fun setFieldTypeface(target: Any, name: String, typeface: Typeface) {
        (readField(target, name) as? TextView)?.let { applyTypeface(it, typeface, typeface.weight) }
    }

    private fun setTextTypefaces(view: View, typeface: Typeface) {
        if (view is TextView) {
            applyTypeface(view, typeface, typeface.weight)
        } else if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setTextTypefaces(view.getChildAt(index), typeface)
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

    private fun isEnabled(): Boolean =
        !retiring && Preferences.getBoolean(Preferences.KEY_NOTIFICATION_FONT_WEIGHT, false)
}
