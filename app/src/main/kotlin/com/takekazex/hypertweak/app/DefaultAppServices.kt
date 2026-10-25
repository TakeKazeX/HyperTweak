package com.takekazex.hypertweak.app

import android.content.Context
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.hook.NativeUpgradeManager
import com.takekazex.hypertweak.hook.NativeRuleConfig
import com.takekazex.hypertweak.settings.contract.*
import com.takekazex.hypertweak.util.RestartUtils
import com.takekazex.hypertweak.util.ShortcutUtils
import com.takekazex.hypertweak.util.WirelessDiagnostics
import com.takekazex.hypertweak.util.PlatformLevel
import com.takekazex.hypertweak.util.ScopeManager
import com.takekazex.hypertweak.util.ScopePromptStore
import io.github.libxposed.service.XposedService

/** App-process composition only; runtime managers remain the owners of their existing state. */
internal object DefaultAppServices : AppServicePorts {
    override val restarts = RestartUtils(PreferencesAppSettings::flush)
    override val shortcuts = ShortcutUtils(PreferencesAppSettings)
    override val wireless = WirelessDiagnostics { logger, enabled -> PreferencesAppSettings.putBoolean(logger.preference, enabled) }
    override val module: ModuleServicePort = XposedServiceManager
    override val nativeUpgrade: NativeUpgradePort = NativeUpgradeManager
    override val scopePrompts = ScopePromptStore(PreferencesAppSettings)
    override val scopes = ScopeManager(
        boundService = { XposedServiceManager.currentService?.let(::BoundScopeService) },
        launcherNeeded = {
            val settings = PreferencesAppSettings
            settings.launcher.hideRecentsClearButton() ||
                settings.launcher.openedFolderColumns() != PlatformSettingsKeys.DEFAULT_OPENED_FOLDER_COLUMNS ||
                settings.backGesture.contextualSearchLongPress() ||
                (PlatformLevel.isOs4 && settings.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS, false)) ||
                (PlatformLevel.isOs4 && DockConfig.decode(settings.getString(DockConfig.KEY, DockConfig().encode()))?.enabled == true)
        }
    )
    override val nativeRules = object : NativeRulePublisher {
        override fun publish(context: Context, hideRecentsClearButton: Boolean, openedFolderColumns: Int?,
            contextualSearchLongPress: Boolean?, assistantWidgets: Boolean?, recentsHaptic: Boolean?,
            folderStack: Boolean?, widgetStack: Boolean?, xiaoaiOptions: Boolean?) {
            val settings = PreferencesAppSettings
            NativeRuleConfig.publish(context, hideRecentsClearButton,
                openedFolderColumns ?: settings.launcher.openedFolderColumns(),
                contextualSearchLongPress ?: settings.backGesture.contextualSearchLongPress(),
                assistantWidgets ?: settings.launcher.allowAndroidWidgetsToAssistant(),
                recentsHaptic ?: settings.launcher.recentsCardHaptic(),
                folderStack ?: settings.launcher.folderStackPreview(),
                widgetStack ?: settings.launcher.androidWidgetStack(),
                xiaoaiOptions ?: settings.launcher.xiaoaiGestureOptions())
        }
    }
}

private class BoundScopeService(private val delegate: XposedService) : ScopeService {
    override val scope: List<String> get() = delegate.scope
    override fun removeScope(packages: List<String>) { delegate.removeScope(packages) }
    override fun requestScope(packages: List<String>, listener: ScopeService.Listener) {
        delegate.requestScope(packages, object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) = listener.onScopeRequestApproved(approved)
            override fun onScopeRequestFailed(message: String) = listener.onScopeRequestFailed(message)
        })
    }
}
