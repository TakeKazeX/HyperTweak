package com.takekazex.hypertweak.app

import com.takekazex.hypertweak.settings.contract.ModuleServicePort
import com.takekazex.hypertweak.settings.contract.NativeUpgradePort
import com.takekazex.hypertweak.settings.contract.NativeRulePublisher

import com.takekazex.hypertweak.util.RestartUtils
import com.takekazex.hypertweak.util.ShortcutUtils
import com.takekazex.hypertweak.util.WirelessDiagnostics
import com.takekazex.hypertweak.util.ScopeManager
import com.takekazex.hypertweak.util.ScopePromptStore

internal interface AppServicePorts {
    val restarts: RestartUtils
    val shortcuts: ShortcutUtils
    val wireless: WirelessDiagnostics
    val module: ModuleServicePort
    val nativeUpgrade: NativeUpgradePort
    val nativeRules: NativeRulePublisher
    val scopes: ScopeManager
    val scopePrompts: ScopePromptStore
}
