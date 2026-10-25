package com.takekazex.hypertweak.settings.contract

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

enum class NativeUpgradeState { CHECKING, CURRENT, UPDATED, FAILED, ROLLBACK_REQUESTED }
data class NativeUpgradeReport(val state: NativeUpgradeState, val detail: String? = null)

internal interface NativeUpgradePort {
    val busy: StateFlow<Boolean>
    val report: StateFlow<NativeUpgradeReport?>
    fun activate(context: Context)
    fun rollback(context: Context)
    fun canRollback(): Boolean
}
