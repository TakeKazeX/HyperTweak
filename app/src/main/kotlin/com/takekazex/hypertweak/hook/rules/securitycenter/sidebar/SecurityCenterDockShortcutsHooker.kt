package com.takekazex.hypertweak.hook.rules.securitycenter.sidebar

import android.app.ActivityOptions
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.UserHandle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import androidx.core.view.isVisible
import com.takekazex.hypertweak.util.ignoreFailure

private object SecurityCenterDockShortcutsFeature : DexFeature {
    override val id = "securitycenter.dock_shortcuts"
    override val schema = 2
}

/**
 * Launcher ordering: manifest shortcuts before dynamic ones, each by rank, enabled only. No count
 * cap: ShortcutService already bounds each activity, and a card taller than the space scrolls.
 */
internal object DockShortcutSelection {
    fun <T> select(items: List<T>, enabled: (T) -> Boolean, manifest: (T) -> Boolean, rank: (T) -> Int): List<T> =
        items.filter(enabled).sortedWith(compareBy<T>({ !manifest(it) }, { rank(it) }))
}

/**
 * Adds a card of the long-pressed app's App Shortcuts under the native full-screen/split card.
 *
 * Security Center runs as UID 1000, for which `checkPermission(ACCESS_SHORTCUTS)` is granted, so
 * LauncherApps works in-process without any system_server change. The native card, its gesture
 * and its dismiss paths are untouched; the extra card mirrors the native card's visibility and
 * animation from a pre-draw listener.
 */
object SecurityCenterDockShortcutsHooker : DynamicHooker() {
    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_APP_SHORTCUTS, false)
    }
    private val settingFreeform by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_APP_SHORTCUTS_FREEFORM, false)
    }

    private const val TAG = "SecurityCenterDockShortcuts"
    private const val CARD_TAG = "hypertweak:dock_app_shortcuts"
    private const val CARD_CLASS = "androidx.cardview.widget.CardView"
    private val main = generationMainHandler()

    @Volatile private var retired = false
    init { generationCleanup { retired = true } }
    private var targets: Targets? by generationState { null }

    private class Targets(val dismiss: Method, val collapse: Method, val menu: Field, val appGetter: Method, val uid: Field, val pkg: Field)
    private class Entry(val info: ShortcutInfo, val label: CharSequence, val icon: Drawable?)

    private val cache = generationResource(ConcurrentHashMap<String, List<Entry>>()) { it.clear() }
    // Reusable: a settings reload retires and reinstalls this same object.
    private val worker = generationWorker("HyperTweak-DockShortcuts")
    private val cards = generationMainResource(WeakHashMap<ViewGroup, ShortcutCard>()) { map ->
        map.values.toList().forEach { ignoreFailure { it.detach() } }
        map.clear()
    }
    private var appsCallback: AutoCloseable? by generationState { null }

    override fun onHook() {
        if (hookParam.packageName != SecurityCenterDockHost.PACKAGE || hookParam.processName != SecurityCenterDockHost.PROCESS) return
        retired = false
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(name = SecurityCenterDockShortcutsFeature.id, resolve = {
            DexRuntime.resolve(feature = SecurityCenterDockShortcutsFeature, apkPath = apk, cacheDir = dexCacheDirectory,
                compute = { SecurityCenterDockShortcutsResolver(DexRuntime.operands(apk)).resolve() },
                decode = { values, _ -> decode(values) })
        }, install = ::install)
    }

    private fun decode(values: Map<String, String>): Pair<Method, Targets>? = runCatching {
        fun method(role: String) = requireNotNull(MethodDescriptorCodec.method(values.getValue(role), classLoader)) { role }
        fun field(role: String) = requireNotNull(MethodDescriptorCodec.field(values.getValue(role), classLoader)) { role }
        val show = method("show")
        check(show.parameterTypes.size == 5 && show.parameterTypes[2] == View::class.java && show.parameterTypes[4] == java.lang.Boolean.TYPE)
        val menu = field("menu")
        check(ViewGroup::class.java.isAssignableFrom(menu.type))
        val uid = field("uid")
        check(uid.type == Integer.TYPE && field("pkg").type == String::class.java)
        values.getValue("callers").lineSequence().filter(String::isNotBlank).forEach {
            deoptimize(requireNotNull(MethodDescriptorCodec.method(it, classLoader)) { it })
        }
        show to Targets(method("dismiss"), method("collapse"), menu, method("appGetter"), uid, field("pkg"))
    }.onFailure { DebugLog.w(TAG, "sidebar shortcut menu contract unavailable; retaining native menu", it) }.getOrNull()

    private fun install(resolved: Pair<Method, Targets>) {
        val (show, t) = resolved
        show.hook {
            after { param ->
                if (retired) return@after
                runCatching { onMenuShown(t, param.thisObject, param.args) }
                    .onFailure { DebugLog.w(TAG, "app shortcut card failed; native menu unchanged", it) }
            }
        }
        targets = t
        DebugLog.hookRegistered(TAG, "sidebar long-press app shortcuts")
    }

    private fun onMenuShown(t: Targets, host: Any, args: Array<out Any?>) {
        val menu = t.menu.get(host) as? ViewGroup ?: return
        val parent = menu.parent as? FrameLayout ?: return
        val existing = synchronized(cards) { cards[parent] }
        // Horizontal sidebars place the native card above the icon; that layout stays native.
        if (!settingEnabled || args[4] == true) { existing?.clear(); return }
        val item = args[1]
        val model = if (item != null && t.appGetter.declaringClass.isInstance(item)) t.appGetter.invoke(item) else null
        val pkg = model?.let { t.pkg.get(it) as? String }
        val card = existing ?: ShortcutCard(parent, menu, host, t).also { synchronized(cards) { cards[parent] = it } }
        val token = card.begin()
        if (model == null || pkg.isNullOrEmpty()) return
        val user = UserHandle.getUserHandleForUid(t.uid.getInt(model))
        val key = "$user/$pkg"
        cache[key]?.let { card.show(token, it); return }
        val context = menu.context.applicationContext ?: menu.context
        worker.execute {
            if (retired) return@execute
            val entries = runCatching { query(context, pkg, user) }
                .onFailure { DebugLog.w(TAG, "shortcut query failed for $pkg", it) }.getOrNull() ?: return@execute
            cache[key] = entries
            main.post { if (!retired) runCatching { card.show(token, entries) }.onFailure { DebugLog.w(TAG, "shortcut card render failed", it) } }
        }
    }

    private fun query(context: Context, pkg: String, user: UserHandle): List<Entry> {
        val apps = requireNotNull(context.getSystemService(LauncherApps::class.java))
        watch(apps)
        val query = LauncherApps.ShortcutQuery().setPackage(pkg)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        val found = apps.getShortcuts(query, user).orEmpty()
        val density = context.resources.displayMetrics.densityDpi
        return DockShortcutSelection.select(found, ShortcutInfo::isEnabled, ShortcutInfo::isDeclaredInManifest, ShortcutInfo::getRank)
            .mapNotNull { info ->
                val label = info.shortLabel?.takeIf { it.isNotBlank() } ?: info.longLabel?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(info, label, runCatching { apps.getShortcutIconDrawable(info, density) }.getOrNull())
            }.also { DebugLog.d(TAG) { "shortcuts pkg=$pkg user=$user found=${found.size} shown=${it.size}" } }
    }

    @Synchronized
    private fun watch(apps: LauncherApps) {
        if (appsCallback != null || retired) return
        val callback = object : LauncherApps.Callback() {
            private fun drop(pkg: String?, user: UserHandle?) { if (pkg != null) cache.remove("$user/$pkg") }
            override fun onPackageRemoved(pkg: String?, user: UserHandle?) = drop(pkg, user)
            override fun onPackageAdded(pkg: String?, user: UserHandle?) = drop(pkg, user)
            override fun onPackageChanged(pkg: String?, user: UserHandle?) = drop(pkg, user)
            override fun onPackagesAvailable(pkgs: Array<out String>?, user: UserHandle?, replacing: Boolean) { pkgs?.forEach { drop(it, user) } }
            override fun onPackagesUnavailable(pkgs: Array<out String>?, user: UserHandle?, replacing: Boolean) { pkgs?.forEach { drop(it, user) } }
            override fun onShortcutsChanged(pkg: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) = drop(pkg, user)
        }
        appsCallback = generationRegistration({ apps.registerCallback(callback, main) }) { apps.unregisterCallback(callback) }
    }

    private fun launch(t: Targets, host: Any, row: View, entry: Entry) {
        val bounds = IntArray(2).let { row.getLocationOnScreen(it); Rect(it[0], it[1], it[0] + row.width, it[1] + row.height) }
        runCatching {
            val options = if (settingFreeform) freeformOptions(row.context, entry.info.`package`) else null
            requireNotNull(row.context.getSystemService(LauncherApps::class.java)).startShortcut(entry.info, bounds, options?.toBundle())
            DebugLog.i(TAG, "started shortcut ${entry.info.`package`}/${entry.info.id} freeform=${options != null}")
        }.onFailure { DebugLog.w(TAG, "shortcut start failed ${entry.info.`package`}/${entry.info.id}", it) }
        // Native launches close the sidebar through SystemUI's overlay binder; a shortcut start does
        // not, so request the same animated collapse (gesture 0 = regular removal) directly.
        runCatching { t.collapse.invoke(host, 0) }.onFailure {
            DebugLog.w(TAG, "sidebar collapse failed; dismissing menu only", it)
            runCatching { t.dismiss.invoke(host) }.onFailure { error -> DebugLog.w(TAG, "native menu dismiss failed", error) }
        }
    }

    /**
     * The same framework factory the sidebar uses for its small windows: bounds, scale and MIUI
     * freeform flags. Null means this app does not support a small window; it then opens normally.
     */
    private fun freeformOptions(context: Context, pkg: String): ActivityOptions? = runCatching {
        Class.forName("android.util.MiuiMultiWindowUtils")
            .getMethod("getActivityOptions", Context::class.java, String::class.java, java.lang.Boolean.TYPE)
            .invoke(null, context, pkg, true) as ActivityOptions?
    }.onFailure { DebugLog.w(TAG, "freeform options unavailable for $pkg", it) }.getOrNull()
        .also { if (it == null) DebugLog.i(TAG, "small window unavailable for $pkg; opening normally") }

    /** Settings only gate future long presses; a visible card is cleared when the feature turns off. */
    fun refreshSettings(): Boolean {
        Preferences.invalidateRuntimeReadCache()
        if (retired) return true
        main.post {
            if (!retired && !settingEnabled) synchronized(cards) { cards.values.toList() }.forEach { it.clear() }
        }
        return true
    }

    override fun recoverHotReloadHosts() { refreshSettings() }
    override fun onPrepareHotReload() {
        retired = true
    }

    /** Host CardView sibling of the native card inside the sidebar panel container. */
    private class ShortcutCard(val parent: FrameLayout, val menu: ViewGroup, host: Any, val t: Targets) {
        private val host = WeakReference(host)
        private val context = menu.context
        private val density = context.resources.displayMetrics.density
        private fun dp(value: Float) = (value * density + 0.5f).toInt()
        private val card: FrameLayout = createCard()
        private val metrics = MiuixMetrics(context, ::dp)
        private val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, metrics.verticalPadding, 0, metrics.verticalPadding)
        }
        private val scroller = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        private var token = 0L
        private var hasContent = false
        private val sync = ViewTreeObserver.OnPreDrawListener { follow(); true }
        private var observer: ViewTreeObserver? = null

        init {
            // Cards left by an older module generation belong to an unloaded class loader.
            (0 until parent.childCount).map(parent::getChildAt).filter { it.tag == CARD_TAG }.forEach(parent::removeView)
            card.tag = CARD_TAG
            card.visibility = View.GONE
            card.addView(scroller, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            parent.addView(card, absoluteCardParams())
            observer = parent.viewTreeObserver.also { it.addOnPreDrawListener(sync) }
        }

        // follow() places the card from absolute window coordinates through leftMargin, so the
        // gravity must stay absolute too; START would read rightMargin in RTL locales.
        @SuppressLint("RtlHardcoded")
        private fun absoluteCardParams() =
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.TOP or Gravity.LEFT }

        private fun createCard(): FrameLayout {
            var type: Class<*>? = menu.javaClass
            while (type != null && type.name != CARD_CLASS) type = type.superclass
            requireNotNull(type) { "native shortcut menu is not a CardView" }
            fun get(name: String) = type.getMethod(name).invoke(menu)
            val created = type.getConstructor(Context::class.java, android.util.AttributeSet::class.java).newInstance(context, null) as FrameLayout
            type.getMethod("setUseCompatPadding", java.lang.Boolean.TYPE).invoke(created, get("getUseCompatPadding"))
            type.getMethod("setRadius", java.lang.Float.TYPE).invoke(created, get("getRadius"))
            type.getMethod("setMaxCardElevation", java.lang.Float.TYPE).invoke(created, get("getMaxCardElevation"))
            type.getMethod("setCardElevation", java.lang.Float.TYPE).invoke(created, get("getCardElevation"))
            type.getMethod("setCardBackgroundColor", ColorStateList::class.java).invoke(created, get("getCardBackgroundColor"))
            created.elevation = menu.elevation
            applyMiShadow(created)
            return created
        }

        /** Same framework shadow the native card receives when the device supports it. */
        private fun applyMiShadow(view: View) = runCatching {
            val properties = Class.forName("android.os.SystemProperties")
            val supported = properties.getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
                .invoke(null, "persist.sys.mi_shadow_supported", false) == true
            if (!supported) return@runCatching
            val f = java.lang.Float.TYPE
            View::class.java.getMethod("setMiShadow", Integer.TYPE, f, f, f, f)
                .invoke(view, 0x73000000, 0f, dp(10f).toFloat(), dp(50f).toFloat(), 1f)
        }.onFailure { DebugLog.d(TAG) { "mi shadow unavailable: $it" } }

        fun begin(): Long {
            token++
            hasContent = false
            clearRows()
            card.visibility = View.GONE
            return token
        }

        fun clear() { begin() }

        fun show(expected: Long, entries: List<Entry>) {
            if (expected != token || menu.visibility != View.VISIBLE || card.parent !== parent) return
            clearRows()
            if (entries.isEmpty()) return
            // Concentric with the card: the Folme tint is inset and rounded instead of a full-width strip.
            val radius = runCatching { card.javaClass.getMethod("getRadius").invoke(card) as Float }.getOrDefault(dp(16f).toFloat())
            val inset = dp(6f).toFloat()
            entries.forEach { entry ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = metrics.itemMinHeight
                    setPaddingRelative(metrics.horizontalPadding, 0, metrics.horizontalPadding, 0)
                    isClickable = true
                    contentDescription = entry.label
                }
                row.addView(ImageView(context).apply {
                    setImageDrawable(entry.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(metrics.iconSize, metrics.iconSize).apply { marginEnd = metrics.iconMargin })
                row.addView(TextView(context).apply {
                    text = entry.label
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.textSize)
                    typeface = metrics.typeface
                    setTextColor(metrics.textColor)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(200f)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.setOnClickListener { view -> host.get()?.let { launch(t, it, view, entry) } }
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.foreground = metrics.pressEffect() ?: RippleDrawable(ColorStateList.valueOf(Color.argb(0x1f, 0x80, 0x80, 0x80)), null,
                    android.graphics.drawable.InsetDrawable(android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = radius - inset; setColor(Color.WHITE)
                    }, inset.toInt(), 0, inset.toInt(), 0))
            }
            hasContent = true
            place()
            follow()
        }

        private fun clearRows() {
            list.removeAllViews()
        }

        /** Below the native card with the same visual gap on both compat-padded cards; shifts the pair to fit. */
        private fun place() {
            val menuParams = menu.layoutParams as? FrameLayout.LayoutParams ?: return
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            card.minimumWidth = menu.measuredWidth
            val cardParams = card.layoutParams as FrameLayout.LayoutParams
            cardParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            card.measure(unspecified, unspecified)
            val window = IntArray(2).also(parent::getLocationInWindow)
            val available = parent.rootView.height - window[1] - dp(16f)
            if (available > 0 && card.measuredHeight > available) {
                // More shortcuts than fit: cap the card and let the list scroll inside it.
                cardParams.height = available
                card.measure(unspecified, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY))
            }
            val parentWidth = parent.width.takeIf { it > 0 } ?: parent.measuredWidth
            val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val menuLeft = if (rtl) parentWidth - menuParams.marginStart - menu.measuredWidth else menuParams.marginStart
            // The native pivot marks the dock side: the pair grows away from the dock.
            val dockOnLeft = menu.pivotX < menu.measuredWidth / 2f
            val left = if (dockOnLeft) menuLeft else menuLeft + menu.measuredWidth - card.measuredWidth
            val gap = dp(8f)
            var menuTop = menuParams.topMargin
            var top = menuTop + menu.measuredHeight - menu.paddingBottom - card.paddingTop + gap
            val limit = parent.rootView.height - window[1]
            if (limit > 0 && top + card.measuredHeight > limit) {
                val shift = minOf(top + card.measuredHeight - limit, menuTop)
                menuTop -= shift
                top -= shift
                menuParams.topMargin = menuTop
                menu.layoutParams = menuParams
                if (top + card.measuredHeight > limit) {
                    top = (menuTop + menu.paddingTop - gap - card.measuredHeight + card.paddingBottom).coerceAtLeast(0)
                }
            }
            val params = cardParams
            params.leftMargin = left.coerceAtLeast(0)
            params.topMargin = top
            card.layoutParams = params
            card.pivotX = menuLeft + menu.pivotX - params.leftMargin
            card.pivotY = menuTop + menu.pivotY - top
        }

        private fun follow() {
            val visible = hasContent && menu.isVisible && card.parent === parent
            val target = if (visible) View.VISIBLE else View.GONE
            if (card.visibility != target) card.visibility = target
            if (visible) {
                card.alpha = menu.alpha
                card.scaleX = menu.scaleX
                card.scaleY = menu.scaleY
            }
        }

        fun detach() {
            token++
            hasContent = false
            clearRows()
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(sync)
            parent.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(sync)
            observer = null
            parent.removeView(card)
        }
    }

    /** Miuix drop-down item metrics and typography from the host's own resources. */
    private class MiuixMetrics(context: Context, private val dp: (Float) -> Int) {
        private val res = context.resources
        private fun id(name: String, type: String) = res.getIdentifier(name, type, SecurityCenterDockHost.PACKAGE)
        private fun px(name: String, fallback: Float) = id(name, "dimen").takeIf { it != 0 }
            ?.let { runCatching { res.getDimensionPixelSize(it) }.getOrNull() } ?: dp(fallback)
        val itemMinHeight = px("miuix_appcompat_popup_menu_item_min_height", 44f)
        val horizontalPadding = px("miuix_appcompat_dropdown_item_padding_horizontal", 20f)
        val verticalPadding = px("miuix_popup_window_vertical_padding", 8f)
        val iconSize = px("miuix_appcompat_drop_down_item_icon_width", 24f)
        val iconMargin = px("miuix_appcompat_immersion_menu_icon_margin_end", 8f).coerceAtLeast(dp(12f))
        val textSize: Float = id("miuix_appcompat_drop_down_item_text_size", "dimen").takeIf { it != 0 }
            ?.let { runCatching { res.getDimension(it) }.getOrNull() }
            ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, res.displayMetrics)
        val typeface: Typeface = id("miuix_font_weight_headline2", "string").takeIf { it != 0 }
            ?.let { runCatching { Typeface.create(res.getString(it), Typeface.NORMAL) }.getOrNull() } ?: Typeface.DEFAULT
        private val night = (res.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor: ColorStateList = id(if (night) "miuix_appcompat_immersion_text_dark" else "miuix_appcompat_immersion_text_light", "color")
            .takeIf { it != 0 }?.let { runCatching { context.getColorStateList(it) }.getOrNull() }
            ?: ColorStateList.valueOf(if (night) Color.WHITE else Color.BLACK)
        private val pressId = id("miuix_popup_window_list_item_fg", "drawable")
        private val host = context

        /**
         * HyperOS popup-list press foreground (`miuix.popupwidget.widget.PressEffectDrawable`): an inset,
         * rounded fill over the whole row whose alpha Folme animates on press. Resolves light/night itself.
         */
        fun pressEffect(): Drawable? = pressId.takeIf { it != 0 }?.let { id ->
            runCatching { host.getDrawable(id) }.onFailure { DebugLog.d(TAG) { "Miuix press effect unavailable: $it" } }.getOrNull()
        }
    }

}
