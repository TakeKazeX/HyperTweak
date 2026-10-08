package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHostAccess
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Neutralize native notification text at palette and final content/color binding boundaries. */
object NotificationMonetTextColorHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE
    private const val TAG = "NotificationMonetTextColor"
    private const val COLORS = "android.app.Notification\$Colors"
    private const val ROW = "com.android.systemui.statusbar.notification.row."
    private const val WRAPPER = ROW + "wrapper."
    private const val HYBRID = ROW + "HybridNotificationView"

    @Volatile private var retiring = false
    private var textColorField: Field? = null
    private var binderField: Field? = null
    private var darkContextMethod: Method? = null
    private var fixContextMethod: Method? = null
    private var materialLogCount = 0
    private data class TextOverride(val original: ColorStateList, val color: Int)
    private data class PaletteOverride(val original: Int, val color: Int)
    private val texts = WeakHashMap<TextView, TextOverride>() // Native view callbacks run on main.
    private val hybrids = WeakHashMap<View, Boolean>()
    private val wrappers = WeakHashMap<Any, WeakReference<View>>()
    private val palettes = WeakHashMap<Any, PaletteOverride>()
    private val rowEffects = WeakHashMap<View, Any>()
    private val paletteLock = Any()
    private data class FieldKey(val type: Class<*>, val name: String)
    private val fields = HashMap<FieldKey, Field?>()
    private val getters = HashMap<FieldKey, Method?>()
    private val resourceIds = WeakHashMap<Resources, List<Int>>()

    // Keep host objects, not old theme-derived colors. Replay through the same eligibility path.
    override fun saveHotReloadState(): Any = StatusIconHostAccess.onMain {
        listOf(hybrids.keys.toList(), wrappers.entries.mapNotNull { (wrapper, row) ->
            row.get()?.let { listOf(wrapper, it) }
        }, synchronized(paletteLock) { palettes.keys.toList() })
    }

    override fun restoreHotReloadState(state: Any?) {
        StatusIconHostAccess.onMain {
            val saved = state as? List<*> ?: return@onMain
            (saved.getOrNull(0) as? List<*>)?.filterIsInstance<View>()?.forEach { view ->
                HookFailurePolicy.open(TAG, "hybrid restore", Unit) { recoverHybrid(view) }
            }
            (saved.getOrNull(1) as? List<*>)?.filterIsInstance<List<*>>()?.forEach { item ->
                val wrapper = item.getOrNull(0) ?: return@forEach
                val row = item.getOrNull(1) as? View ?: return@forEach
                HookFailurePolicy.open(TAG, "wrapper restore", Unit) { recoverWrapper(wrapper, row) }
            }
            synchronized(paletteLock) {
                (saved.getOrNull(2) as? List<*>)?.filterNotNull()?.forEach { palette ->
                    HookFailurePolicy.open(TAG, "palette restore", Unit) {
                        val night = read(palette, "mPaletteIsForNightMode") as? Boolean ?: return@open
                        if (read(palette, "mPaletteIsForColorized") != false) return@open
                        if (isEnabled()) applyPalette(palette, night)
                    }
                }
            }
        }
    }

    override fun onPrepareHotReload() {
        retiring = true
        StatusIconHostAccess.onMain {
            texts.forEach { (view, state) ->
                // Do not overwrite a later native/app write while retiring this generation.
                HookFailurePolicy.open(TAG, "text retire", Unit) {
                    if (isApplied(view.textColors, state.color)) view.setTextColor(state.original)
                }
            }
            texts.clear()
            hybrids.clear()
            wrappers.clear()
            rowEffects.clear()
        }
        synchronized(paletteLock) {
            palettes.forEach { (palette, state) ->
                HookFailurePolicy.open(TAG, "palette retire", Unit) {
                    textColorField?.let { if (it.getInt(palette) == state.color) it.setInt(palette, state.original) }
                }
            }
            palettes.clear()
            textColorField = null
        }
        synchronized(fields) { fields.clear() }
        synchronized(getters) { getters.clear() }
        resourceIds.clear()
        binderField = null
        darkContextMethod = null
        fixContextMethod = null
        materialLogCount = 0
    }

    internal fun recoverHybrid(view: View) {
        if (isEnabled() && hierarchy(view).contains(HYBRID)) forceHybridText(view)
    }

    internal fun recoverWrapper(wrapper: Any, row: View) {
        if (!isEnabled()) return
        forceWrapperText(wrapper, row)
    }

    override fun onHook() {
        retiring = false
        HookFailurePolicy.open(TAG, "native material capability", Unit) {
            binderField = (WRAPPER + "NotificationHeaderViewWrapperInjector").toClassOrNull()
                ?.getDeclaredField("sNotificationRowContentBinderInjector")?.apply { isAccessible = true }
            val materialClass = "com.android.systemui.statusbar.notification.style.domain.NotificationMaterialStateInteractor".toClassOrNull()
            fixContextMethod = materialClass?.declaredMethods?.singleOrNull {
                it.name == "getFixUiModeContext" && it.parameterCount == 2 &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType && it.returnType == Context::class.java
            }?.apply { isAccessible = true }
            darkContextMethod = materialClass?.getDeclaredMethod("wrapDarkContext", Context::class.java, Boolean::class.javaPrimitiveType)
                ?.apply { isAccessible = true }
        }
        // Failure of the framework capability must not suppress independent SystemUI hooks.
        val paletteHooks = HookFailurePolicy.open(TAG, "palette install", 0) { hookPalette() }
        var viewHooks = hookAfter(HYBRID, "bind", listOf(
            CharSequence::class.java, CharSequence::class.java, CharSequence::class.java
        )) { target, _ -> (target as? View)?.let(::forceHybridText) }
        listOf("HybridNotificationViewInjectorImpl", "HybridConversationNotificationViewInjectorImpl").forEach { name ->
            val hybridClass = HYBRID.toClassOrNull() ?: return@forEach
            viewHooks += hookAfter(ROW + name, "updateTextColor", listOf(Context::class.java, hybridClass)) { _, args ->
                (args.getOrNull(1) as? View)?.let(::forceHybridText)
            }
        }
        val rowClass = (ROW + "ExpandableNotificationRow").toClassOrNull()
        val entryClass = "com.android.systemui.statusbar.notification.collection.NotificationEntry".toClassOrNull()
        if (rowClass != null) {
            // Hook each declaration after its own child resolution, including inherited inbox paths.
            (NotificationTextColorPolicy.standardWrappers + (WRAPPER + "MiuiNotificationViewWrapper")).forEach { name ->
                viewHooks += hookAfter(name, "onContentUpdated", listOf(rowClass)) { target, args ->
                    val row = args.firstOrNull() as? View ?: return@hookAfter
                    target?.let { forceWrapperText(it, row) }
                }
            }
        }
        if (entryClass != null) {
            listOf("MiuiNotificationBigTextViewWrapper", "MiuiNotificationTemplateViewWrapper").forEach { name ->
                viewHooks += hookAfter(WRAPPER + name, "updateTransparentBgAndTextColor",
                    listOf(entryClass, Boolean::class.javaPrimitiveType!!)) { target, _ ->
                    val row = target?.let { read(it, "mRow") } as? View ?: return@hookAfter
                    forceWrapperText(target, row)
                }
            }
        }
        val binderClass = "com.android.systemui.statusbar.notification.style.view.ExpandableNotificationRowViewBinder".toClassOrNull()
        val effectUpdate = binderClass?.declaredMethods?.singleOrNull {
            it.name == "bindInternal\$updateEffect" && it.parameterCount == 5 &&
                it.parameterTypes[1] == rowClass && it.returnType == Void.TYPE &&
                java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        if (effectUpdate != null) viewHooks += HookFailurePolicy.open(TAG, "native effect update install", 0) {
            deoptimize(effectUpdate)
            effectUpdate.hook("notification_neutral_effect_updated") {
                after { param -> HookFailurePolicy.open(TAG, "native effect updated", Unit) {
                    if (!isEnabled()) return@open
                    val row = param.args.getOrNull(1) as? View ?: return@open
                    val key = param.args.getOrNull(2)?.let { read(it, "element") } ?: return@open
                    if (rowEffects[row] != key) {
                        rowEffects[row] = key
                        recoverRowText(row)
                    }
                } }
            }
            1
        }
        DebugLog.i(TAG, "neutral native text hooks palette=$paletteHooks content=$viewHooks")
    }

    private fun recoverRowText(row: View) {
        listOf("mPrivateLayout", "mPublicLayout").mapNotNull { read(row, it) }.forEach { content ->
            listOf("mContractedWrapper", "mExpandedWrapper", "mHeadsUpWrapper").forEach { name ->
                read(content, name)?.let { forceWrapperText(it, row) }
            }
            fun recover(view: View) {
                if (hierarchy(view).contains(HYBRID)) forceHybridText(view)
                else if (view is ViewGroup) for (i in 0 until view.childCount) recover(view.getChildAt(i))
            }
            (content as? View)?.let(::recover)
        }
    }

    private fun hookPalette(): Int {
        val type = COLORS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, COLORS, "class not found")
            return 0
        }
        val method = type.declaredMethods.singleOrNull { it.name == "resolvePalette" &&
            it.parameterTypes.contentEquals(arrayOf(Context::class.java, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)) } ?: run {
            DebugLog.hookSkipped(TAG, "$COLORS#resolvePalette", "signature not found")
            return 0
        }
        textColorField = type.declaredFields.singleOrNull { it.name == "mTextColor" &&
            it.type == Int::class.javaPrimitiveType }?.apply { isAccessible = true } ?: run {
            DebugLog.hookSkipped(TAG, "$COLORS#mTextColor", "field not found")
            return 0
        }
        deoptimize(method)
        method.hook("notification_neutral_palette") {
            after { param ->
                HookFailurePolicy.open(TAG, "resolvePalette", Unit) {
                    if (!isEnabled()) return@open
                    synchronized(paletteLock) {
                        if (!isEnabled()) return@open
                        // The boolean represents actual background colorization, including color=0.
                        if (param.args.getOrNull(2) == true) {
                            releasePalette(param.thisObject)
                        } else {
                            val night = param.args.getOrNull(3) as? Boolean ?: return@open
                            applyPalette(param.thisObject, night)
                        }
                    }
                }
            }
        }
        return 1
    }

    private fun applyPalette(palette: Any, night: Boolean) {
        val field = textColorField ?: return
        val current = field.getInt(palette)
        val old = palettes[palette]
        val original = NotificationTextColorPolicy.original(current, old?.original, old?.color)
        val color = NotificationTextColorPolicy.neutral(night)
        palettes[palette] = PaletteOverride(original, color)
        field.setInt(palette, color)
    }

    private fun releasePalette(palette: Any) {
        val old = palettes.remove(palette) ?: return
        textColorField?.let { if (it.getInt(palette) == old.color) it.setInt(palette, old.original) }
    }

    private fun hookAfter(name: String, methodName: String, parameters: List<Class<*>>,
                          action: (Any?, Array<Any?>) -> Unit): Int {
        val type = name.toClassOrNull() ?: return 0
        val method = type.declaredMethods.singleOrNull { it.name == methodName &&
            it.parameterTypes.toList() == parameters } ?: return 0
        return HookFailurePolicy.open(TAG, "$name#$methodName install", 0) {
            method.hook("notification_neutral_${name}_$methodName") {
                after { param -> HookFailurePolicy.open(TAG, "$name#$methodName", Unit) {
                    if (isEnabled()) action(param.thisObject, param.args)
                } }
            }
            1
        }
    }

    private fun forceHybridText(view: View) {
        if (!isEnabled()) return
        val row = generateSequence(view.parent) { it.parent }.filterIsInstance<View>()
            .firstOrNull { it.javaClass.name == ROW + "ExpandableNotificationRow" }
        if (row != null) {
            val notification = notificationFor(row) ?: return
            if (getterBoolean(notification, "isColorized") != false) {
                releaseTree(view)
                hybrids.remove(view)
                return
            }
        }
        hybrids[view] = true
        val textContext = nativeTextContext(row, false) ?: return
        val night = textContext.night
        getter(view, "getTitleView")?.let { applyText(it, NotificationTextColorPolicy.neutral(night)) }
        listOf("getTextView", "getConversationSenderNameView").forEach { name ->
            getter(view, name)?.let { applyText(it, NotificationTextColorPolicy.secondary(night)) }
        }
    }

    private fun forceWrapperText(wrapper: Any, row: View) {
        val root = read(wrapper, "mView") as? View ?: return
        if (!NotificationTextColorPolicy.ownsTemplate(hierarchy(wrapper))) return
        val notification = notificationFor(row) ?: return
        val colorized = getterBoolean(notification, "isColorized") ?: return
        if (colorized) {
            releaseTree(root)
            wrappers.remove(wrapper)
            return
        }
        wrappers[wrapper] = WeakReference(row)
        val textContext = nativeTextContext(row, read(wrapper, "mIsTransparentBg") == true) ?: return
        val context = textContext.context
        val night = textContext.night
        val primary = NotificationTextColorPolicy.neutral(night)
        val secondary = NotificationTextColorPolicy.secondary(night)
        val boundFields = HashSet<TextView>()
        listOf("mTitle", "mAltTitle").forEach {
            (read(wrapper, it) as? TextView)?.let { view ->
                boundFields.add(view)
                applyText(view, primary)
            }
        }
        listOf("mText", "mBigText", "mBigtext", "mSubText", "mTime").forEach {
            (read(wrapper, it) as? TextView)?.let { view ->
                boundFields.add(view)
                applyText(view, secondary)
            }
        }
        // Inbox, picture, progress and single-line layouts have no template text fields.
        // Resolve stock resources on every native update: overlays/configuration can change.
        val stock = stockTextColors(context)
        visitText(root) { view ->
            if (view in boundFields) return@visitText
            val owned = texts[view]?.takeIf { isApplied(view.textColors, it.color) }
            val original = owned?.original ?: view.textColors
            // Preserve stateful/app-defined colors; do not flatten disabled action colors.
            if (!original.isStateful) {
                val before = original.defaultColor
                val after = if (owned != null) NotificationTextColorPolicy.neutral(night, before ushr 24)
                    else NotificationTextColorPolicy.replaceStock(before, stock, night)
                if (owned != null || before != after) applyText(view, after)
                else texts.remove(view)
            } else texts.remove(view)
        }
    }

    private data class NativeTextContext(val context: Context, val night: Boolean)

    private fun nativeTextContext(row: View?, transparent: Boolean): NativeTextContext? {
        // This is the same material producer used by changeToDarkOrLightContext. A wrapper's
        // mContext describes the host, not necessarily the context that inflated its RemoteViews.
        val binder = binderField?.get(null) ?: return null
        val material = read(binder, "notificationMaterialStateInteractor") ?: return null
        val glass = getterBoolean(material, "isGlassMaterialType") ?: return null
        val entry = row?.let { StatusIconHostAccess.invoke(it, "getEntry") }
        val sbn = entry?.let { read(it, "mSbn") }
        val fullAod = sbn?.let { read(it, "showingFullAodStyle") } == true
        val stateFlow = read(material, "materialViewState\$delegate")
            ?.let { invokeGetter(it, "getValue") } ?: return null
        val state = invokeGetter(stateFlow, "getValue") ?: return null
        val style = read(state, "notifStyle") ?: return null
        val viewModel = row?.let { invokeGetter(it, "getInjector") }?.let { read(it, "viewModel") }
        fun flag(name: String): Boolean = viewModel?.let { read(it, name) }
            ?.let { read(it, "flow") }?.let { invokeGetter(it, "getValue") } == true
        val headsUp = NotificationTextColorPolicy.isHeadsUp(
            flag("headUpState"), flag("hasCalledHUNDragDownDisappear"))
        // Delegate the GLASS_DARK + light-mode heads-up exception to the native producer.
        val nativeContext = fixContextMethod?.invoke(material, style, headsUp) as? Context ?: return null
        val night = NotificationTextColorPolicy.usesDarkTextContext(isNight(nativeContext), fullAod, transparent)
        val resolved = if (night && !isNight(nativeContext)) {
            darkContextMethod?.invoke(null, nativeContext, true) as? Context ?: return null
        } else nativeContext
        if (materialLogCount++ < 6) DebugLog.i(TAG,
            "native text context glass=$glass headsUp=$headsUp style=$style " +
                "fullAod=$fullAod transparent=$transparent night=$night")
        return NativeTextContext(resolved, night)
    }

    private fun stockTextColors(context: Context): Set<Int> {
        val resources = context.resources
        val ids = resourceIds.getOrPut(resources) {
            listOf("notification_primary_text_color_light", "notification_secondary_text_color_light",
                "notification_primary_text_color_current", "notification_secondary_text_color_current",
                "notification_time_color", "notification_action_text_color",
                "notification_title_color_with_bg_dark", "notification_text_color_with_bg_dark",
                "notification_time_color_with_bg_dark").mapNotNull { name ->
                resources.getIdentifier(name, "color", "com.android.systemui").takeIf { it != 0 }
            } + listOf("materialColorOnSurface", "materialColorShadow").mapNotNull { name ->
                resources.getIdentifier(name, "color", "android").takeIf { it != 0 }
            }
        }
        return ids.mapNotNull { runCatching { context.getColor(it) }.getOrNull() }.toSet()
    }

    private fun notificationFor(row: View): android.app.Notification? {
        val entry = StatusIconHostAccess.invoke(row, "getEntry") ?: return null
        return (read(entry, "mSbn") as? StatusBarNotification)?.notification
    }

    private fun applyText(view: TextView, color: Int) {
        if (!isEnabled()) return
        val current = view.textColors
        val old = texts[view]
        val original = if (old != null && isApplied(current, old.color)) old.original else current
        texts[view] = TextOverride(original, color)
        if (!isApplied(current, color)) view.setTextColor(color)
    }

    private fun isApplied(colors: ColorStateList, color: Int): Boolean =
        !colors.isStateful && colors.defaultColor == color

    private fun releaseTree(root: View) = visitText(root) { view ->
        val old = texts.remove(view)
        if (old != null && isApplied(view.textColors, old.color)) view.setTextColor(old.original)
    }

    private fun visitText(view: View, action: (TextView) -> Unit) {
        if (view is TextView) action(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) visitText(view.getChildAt(i), action)
    }

    private fun hierarchy(owner: Any): List<String> =
        generateSequence<Class<*>>(owner.javaClass) { it.superclass }.map { it.name }.toList()

    private fun read(owner: Any, name: String): Any? {
        val field = synchronized(fields) {
            val key = FieldKey(owner.javaClass, name)
            if (!fields.containsKey(key)) fields[key] = run {
                generateSequence<Class<*>>(owner.javaClass) { it.superclass }.mapNotNull { type ->
                    runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                }.firstOrNull()
            }
            fields[key]
        }
        return field?.get(owner)
    }

    private fun invokeGetter(owner: Any, name: String): Any? {
        val method = synchronized(getters) {
            val key = FieldKey(owner.javaClass, name)
            if (!getters.containsKey(key)) getters[key] = run {
                runCatching { owner.javaClass.getMethod(name).apply { isAccessible = true } }.getOrNull()
            }
            getters[key]
        }
        return method?.invoke(owner)
    }
    private fun getter(owner: Any, name: String): TextView? = invokeGetter(owner, name) as? TextView
    private fun getterBoolean(owner: Any, name: String): Boolean? = invokeGetter(owner, name) as? Boolean
    private fun isNight(context: Context): Boolean = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun isEnabled(): Boolean = !retiring &&
        Preferences.getBoolean(Preferences.KEY_NOTIFICATION_MONET_TEXT_COLOR, false)
}
