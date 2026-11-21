package com.takekazex.hypertweak.hook.rules.systemui.media

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.platform.ResourceLookup

/** Reuses the same page indicator and modal actions as the host's notification shade. */
internal class HyperOsMediaChrome(
    private val loader: ClassLoader?,
    materialStateType: Class<*>,
    modalManagerType: Class<*>,
) {
    private val modal = HyperOsMediaModalEffects(loader, modalManagerType)
    private fun type(name: String) = Class.forName(name, false, loader)
    private val indicator = type("miuix.miuixbasewidget.widget.PageIndicator")
    private val newIndicator = indicator.getConstructor(Context::class.java)
    private val int = Int::class.javaPrimitiveType!!
    private val count = checkNotNull(HostAccess.method(indicator, "setIndicatorCount", int))
    private val position = checkNotNull(HostAccess.method(indicator, "setCurrentPosition", int))
    private val offset = checkNotNull(HostAccess.method(indicator, "setCurrentPositionOffset", Float::class.javaPrimitiveType!!))

    private val menuItem = type("com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin\$MenuItem")
    private val nativeItem = type("com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow\$MiuiNotificationMenuItem")
    private val newItem = nativeItem.getConstructor(
        Context::class.java, int, type("com.android.systemui.statusbar.notification.row.NotificationSnooze"), int,
    )
    private val menuView = checkNotNull(HostAccess.method(menuItem, "getMenuView"))
    private val click = checkNotNull(HostAccess.method(nativeItem, "setOnClickListener", View.OnClickListener::class.java))
    private val description = checkNotNull(HostAccess.field(nativeItem, "mContentDescription"))
    private val viewType = type("com.miui.systemui.material.NotificationViewType")
    private val modalMenu = checkNotNull(HostAccess.field(viewType, "ModalMenu")).get(null)
    private val bind = checkNotNull(HostAccess.method(
        type("com.android.systemui.statusbar.notification.style.view.NotificationMenuViewBinder"),
        "bind", menuItem, viewType, materialStateType,
    ))

    init {
        check(menuItem.isAssignableFrom(nativeItem) && menuView.returnType == View::class.java)
        check(description.type == String::class.java && viewType.isInstance(modalMenu))
    }

    fun createIndicator(context: Context): View = (newIndicator.newInstance(context) as View).apply {
        visibility = View.GONE
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setPageCount(view: View, pages: Int) {
        count.invoke(view, pages)
        view.visibility = if (pages > 1) View.VISIBLE else View.GONE
    }

    fun setPosition(view: View, page: Int, fraction: Float) {
        position.invoke(view, page)
        offset.invoke(view, fraction)
    }

    fun showMenu(
        anchor: View,
        material: Any,
        modalManager: Any,
        createFocusCard: (ViewGroup) -> HyperOsMediaContract.Page,
        releaseFocusCard: (HyperOsMediaContract.Page) -> Unit,
        closePage: () -> Unit,
        hideApp: (() -> Unit)?,
        restoreApps: (() -> Unit)?,
        onDismiss: (HyperOsMediaModal) -> Unit,
    ): HyperOsMediaModal? {
        val context = anchor.context
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER }
        val menu = HyperOsMediaModal(
            anchor, actions, modal, modalManager,
            dimension(context, "notification_modal_menu_margin_top"),
            dimension(context, "notification_modal_menu_bottom_max"),
            dimension(context, "notification_min_height"),
            releaseFocusCard, onDismiss,
        )
        val margin = dimension(context, "miui_notification_modal_menu_margin_left_right")
        val itemCount = 1 + listOfNotNull(hideApp, restoreApps).size
        val titleWidth = (anchor.width / itemCount - 2 * margin).coerceAtLeast(1)
        fun add(label: Int, icon: String, run: () -> Unit) {
            val item = createAction(context, material, label, icon) {
                menu.exit(run)
            }
            checkNotNull(item.findViewById<TextView>(resource(context, "modal_menu_title", "id"))).maxWidth = titleWidth
            actions.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = margin
                marginEnd = margin
            })
        }
        add(R.string.settings_media_card_close, "miui_notification_menu_ic_close", closePage)
        hideApp?.let { add(R.string.settings_media_card_hide_app, "miui_notification_menu_ic_fold", it) }
        restoreApps?.let { add(R.string.settings_media_card_restore_apps, "miui_notification_menu_ic_un_fold", it) }
        return menu.takeIf { it.show(createFocusCard) }
    }

    fun createRestoreAction(context: Context, material: Any, restore: () -> Unit): View =
        createAction(context, material, R.string.settings_media_card_restore_apps, "miui_notification_menu_ic_un_fold", restore)

    private fun createAction(context: Context, material: Any, label: Int, icon: String, run: () -> Unit): View {
        val title = context.createPackageContext("com.takekazex.hypertweak", 0).getString(label)
        val item = newItem.newInstance(context, resource(context, "miui_notification_menu_more_setting", "string"),
            null, resource(context, icon, "drawable"))
        val view = menuView.invoke(item) as View
        description.set(item, title)
        checkNotNull(view.findViewById<TextView>(resource(context, "modal_menu_title", "id"))).text = title
        val button = checkNotNull(view.findViewById<View>(resource(context, "modal_menu_icon", "id")))
        button.contentDescription = title
        val listener = View.OnClickListener {
            HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "invoke media action", Unit, run)
        }
        click.invoke(item, listener)
        view.setOnClickListener(listener)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        bind.invoke(null, item, modalMenu, material)
        return view
    }

    private fun dimension(context: Context, name: String) =
        context.resources.getDimensionPixelSize(resource(context, name, "dimen"))

    private fun resource(context: Context, name: String, kind: String): Int =
        ResourceLookup.identifier(context.resources, name, kind, "com.android.systemui").also {
            check(it != 0) { "Missing notification menu resource: $kind/$name" }
        }
}
