package com.takekazex.hypertweak.hook.rules.systemui

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHostAccess
import com.takekazex.hypertweak.util.DebugLog

/** Replay native standard wrappers only; app-owned custom RemoteViews retain their own styling. */
internal object NotificationContentHotReloadRecovery {
    private val wrappers = setOf(
        "com.android.systemui.statusbar.notification.row.wrapper.NotificationTemplateViewWrapper",
        "com.android.systemui.statusbar.notification.row.wrapper.MiuiNotificationTemplateViewWrapper",
        "com.android.systemui.statusbar.notification.row.wrapper.MiuiNotificationBigTextViewWrapper"
    )
    fun recover(views: List<View>) {
        var refreshed = 0
        views.forEach { view ->
            runCatching {
                NotificationAbsoluteTimeHooker.recoverExistingView(view)
                NotificationFontWeightHooker.recoverHybrid(view)
                NotificationMonetTextColorHooker.recoverHybrid(view)
                if (view.javaClass.name != "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow") return@runCatching
                listOf("mPrivateLayout", "mPublicLayout").mapNotNull { StatusIconHostAccess.read(view, it) }
                    .flatMap { content -> listOf("mContractedWrapper", "mExpandedWrapper", "mHeadsUpWrapper")
                        .mapNotNull { StatusIconHostAccess.read(content, it) } }.distinct().forEach wrapperLoop@ { wrapper ->
                        if (generateSequence(wrapper.javaClass) { it.superclass }.none { it.name in wrappers }) return@wrapperLoop
                        val method = wrapper.javaClass.methods.singleOrNull {
                            it.name == "onContentUpdated" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(view)
                        } ?: error("Missing native notification wrapper update")
                        method.invoke(wrapper, view)
                        // onContentUpdated resolves children but does not rerun MIUI's color
                        // callback. Reuse our exact entry-aware color path after resolution.
                        NotificationMonetTextColorHooker.recoverWrapper(wrapper, view)
                        refreshed++
                    }
            }.onFailure { DebugLog.w("NotificationHotReload", "existing notification recovery failed", it) }
        }
        DebugLog.i("NotificationHotReload", "hot reload native wrappers=$refreshed")
    }
}
