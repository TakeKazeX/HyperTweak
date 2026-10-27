package com.takekazex.hypertweak.app

import com.takekazex.hypertweak.settings.contract.ModuleServicePort
import com.takekazex.hypertweak.settings.contract.NativeUpgradePort
import com.takekazex.hypertweak.settings.contract.NativeRulePublisher

import com.takekazex.hypertweak.util.restart.RestartUtils
import com.takekazex.hypertweak.util.platform.ShortcutUtils
import com.takekazex.hypertweak.util.diagnostics.WirelessDiagnostics
import com.takekazex.hypertweak.util.scope.ScopeManager
import com.takekazex.hypertweak.util.scope.ScopePromptStore

internal interface AppServicePorts {
    val diagnostics: com.takekazex.hypertweak.util.diagnostics.DiagnosticCollector
    val restarts: RestartUtils
    val shortcuts: ShortcutUtils
    val wireless: WirelessDiagnostics
    val module: ModuleServicePort
    val nativeUpgrade: NativeUpgradePort
    val nativeRules: NativeRulePublisher
    val scopes: ScopeManager
    val scopePrompts: ScopePromptStore
}
