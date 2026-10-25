package com.takekazex.hypertweak.ui.app

import android.content.Context
import com.takekazex.hypertweak.app.AppActivationCoordinator
import androidx.compose.runtime.*
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.update.UpdateManager
import com.takekazex.hypertweak.util.update.UpdateRecoveryManager
import com.takekazex.hypertweak.settings.contract.ModuleServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/** Launch reconciliation is independent of navigation and the localized Activity wrapper. */
@Composable
internal fun ReconcileUpdates(
    context: Context, updateManager: UpdateManager,
    snackbarHostState: SnackbarHostState, noticeContext: Context
) {
    val currentNoticeContext by rememberUpdatedState(noticeContext)
    // Package replacement kills the installing process, so the outcome of an update is
    // reconciled on the next launch: confirm the installed package really is the armed
    // target, delete the leftover download, and surface the one-shot completion notice.
    LaunchedEffect(Unit) {
        val notice = withContext(Dispatchers.IO) {
            val reconciled = runCatching {
                UpdateRecoveryManager(context.applicationContext).reconcile()
            }.getOrNull()
            // Restore the last known result before any network work, so opening the app
            // shows a real answer without having to wait for (or be due for) a fresh query.
            updateManager.primeFromCache()
            reconciled
        }
        // Frequency-throttled and a no-op when the interval is "never", when offline, or
        // when this build is not the official package. Started before the snackbar below,
        // because `showSnackbar` suspends until the notice is dismissed and the background
        // check must not queue behind a completion message.
        updateManager.startAutoCheck()
        if (notice != null) {
            val message = if (notice.versionCode != null) {
                currentNoticeContext.getString(R.string.update_completion_snackbar, notice.versionName, notice.versionCode)
            } else {
                currentNoticeContext.getString(R.string.update_completion_snackbar_legacy, notice.versionName)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

}

/** Service rebinding reuses the same settings readers and persisted restart state as startup. */
@Composable
internal fun rememberModuleActivation(
    settings: AppSettingsState, restarts: AppRestartTracker,
    serviceConnected: ModuleServiceInfo?, isModuleActive: () -> Boolean
): Boolean {
    val activation = remember { AppActivationCoordinator(restarts.localPrefs, isModuleActive, settings::reload) }
    var moduleActive by remember { mutableStateOf(activation.initialActive) }
    LaunchedEffect(serviceConnected) {
        activation.refresh(serviceConnected != null)?.let { moduleActive = it }
    }
    return moduleActive
}
