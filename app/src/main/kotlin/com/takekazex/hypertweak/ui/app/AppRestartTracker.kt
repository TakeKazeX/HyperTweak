package com.takekazex.hypertweak.ui.app

import android.content.Context
import androidx.compose.runtime.*
import androidx.core.content.edit
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.restart.RestartScopeSelection
import com.takekazex.hypertweak.util.scope.ScopeManager

private val TWEAK_RESTART_SCOPES = mapOf(
    IconSettingsKeys.KEY_ICON_DUO_ENABLED to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_EXPANDED to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_SIZE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_RING_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_WIFI_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_CELLULAR_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_TYPE_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_DOTS_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_AIRPLANE_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_BATTERY_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_PERCENT_SCALE to RestartScopeSelection(systemUi = true),
    PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS)
    ),
    PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS_UI)
    ),
    PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS_UI)
    ),
    PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS_UI)
    ),
    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE to RestartScopeSelection(systemUi = true),
    // The four per-element dp values are Float-typed prefs; only the Boolean master switch is
    // tracked here (markTweaked stores Boolean baselines — Float keys would crash the baseline
    // read in clearRestartedScopes). Changing a slider takes effect on the next SystemUI restart,
    // which the master-switch restart dialog or the manual scope restart already covers.
    SystemUiSettingsKeys.KEY_CC_CORNER_ENABLED to RestartScopeSelection(systemUi = true),
    // The editor-cards hooks install at control-center plugin load; only enabling needs the
    // restart (callbacks read the switch live, so disabling applies immediately).
    SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED to RestartScopeSelection(systemUi = true),
    // Same for the element-size hooks; the individual size keys are read live on every bind.
    SystemUiSettingsKeys.KEY_CC_RESIZE_ENABLED to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS to RestartScopeSelection(systemUi = true),
    PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS to RestartScopeSelection(settings = true),
    PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE to RestartScopeSelection(settings = true),
    PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK to RestartScopeSelection(
        additionalPackages = setOf(
            RestartScopeSelection.PACKAGE_PHONE,
            RestartScopeSelection.PACKAGE_XIAOMI_PHONE
        )
    ),
    PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO to RestartScopeSelection(
        settings = true,
        milink = true,
        bluetooth = true
    ),
    PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC to RestartScopeSelection(
        settings = true,
        milink = true,
        bluetooth = true
    ),
    PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_TRUST_SERVICE)
    ),
    PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_GUARD_PROVIDER)
    ),
    PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES to RestartScopeSelection(milink = true),
    // The theme rights-check hook installs into the Theme Manager process. The framework-side
    // re-validation hook lives in system_server, which the app cannot restart; the Home page's
    // hot reload (or a reboot) is what applies that half.
    PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_THEME_MANAGER)
    ),
)

private const val KEY_PENDING_RESTART_BOOT_TOKEN = "pending_restart_boot_token"
private const val KEY_DIRTY_TWEAK_KEYS = "dirty_tweak_keys"
private const val KEY_TWEAK_BASELINE_PREFIX = "tweak_baseline_"
private const val KEY_MANUAL_PENDING_RESTART_SCOPES = "manual_pending_restart_scopes"
private const val KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED = "fingerprint_split_baseline_migrated"

private fun currentBootToken(): String {
    return runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: ((System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 1000L).toString()
}

private fun restartScopesForDirtyTweaks(keys: Set<String>): RestartScopeSelection {
    return keys.fold(RestartScopeSelection.Empty) { acc, key ->
        acc.merge(TWEAK_RESTART_SCOPES[key] ?: RestartScopeSelection.Empty)
    }
}

/** Reads the live LSPosed scope, falling back to the module declaration while the service binds. */
internal suspend fun currentRestartableScopes(context: Context, scopes: ScopeManager): RestartScopeSelection {
    return scopes.restartableScope(context)
        ?.let(RestartScopeSelection::fromPackageSet)
        ?: RestartScopeSelection.fromPackageSet(scopes.declaredRestartableScope(context))
}

/** Owns persisted restart baselines; setting values are supplied by the current UI state. */
internal class AppRestartTracker(context: Context, private val values: AppSettingsState) {
    val localPrefs = context.getSharedPreferences(AppSettingsKeys.NAME, Context.MODE_PRIVATE)
    private val bootToken = currentBootToken()
    var dirtyTweakKeys by run {
        val storedBootToken = localPrefs.getString(KEY_PENDING_RESTART_BOOT_TOKEN, null)
        if (storedBootToken == bootToken) {
            val storedKeys = localPrefs
                .getStringSet(KEY_DIRTY_TWEAK_KEYS, emptySet())
                .orEmpty()
            val restartKeys = storedKeys.intersect(TWEAK_RESTART_SCOPES.keys)
            if (restartKeys != storedKeys) {
                localPrefs.edit { putStringSet(KEY_DIRTY_TWEAK_KEYS, restartKeys) }
            }
            mutableStateOf(restartKeys)
        } else {
            localPrefs.edit {
                remove(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES)
                remove(KEY_MANUAL_PENDING_RESTART_SCOPES)
                remove(KEY_DIRTY_TWEAK_KEYS)
                TWEAK_RESTART_SCOPES.keys.forEach { remove("$KEY_TWEAK_BASELINE_PREFIX$it") }
                putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
            }
            mutableStateOf(emptySet())
        }
    }
    var manualPendingRestartScopes by run {
        val storedBootToken = localPrefs.getString(KEY_PENDING_RESTART_BOOT_TOKEN, null)
        if (storedBootToken == bootToken) {
            mutableStateOf(
                RestartScopeSelection.fromKeySet(
                    localPrefs.getStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, emptySet()).orEmpty()
                )
            )
        } else {
            mutableStateOf(RestartScopeSelection.Empty)
        }
    }
    var pendingRestartScopes by run {
        val storedBootToken = localPrefs.getString(KEY_PENDING_RESTART_BOOT_TOKEN, null)
        if (storedBootToken == bootToken) {
            mutableStateOf(
                restartScopesForDirtyTweaks(dirtyTweakKeys).merge(manualPendingRestartScopes)
            )
        } else {
            mutableStateOf(RestartScopeSelection.Empty)
        }
    }

    fun effectivePendingRestartScopes(dirtyKeys: Set<String>): RestartScopeSelection {
        return restartScopesForDirtyTweaks(dirtyKeys).merge(manualPendingRestartScopes)
    }

    fun updateDirtyTweakKeys(next: Set<String>) {
        val nextPendingScopes = effectivePendingRestartScopes(next)
        dirtyTweakKeys = next
        pendingRestartScopes = nextPendingScopes
        localPrefs.edit {
            putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
            putStringSet(KEY_DIRTY_TWEAK_KEYS, next)
            putStringSet(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
            putStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, manualPendingRestartScopes.toKeySet())
        }
    }

    fun markTweaked(key: String, value: Boolean, defaultValue: Boolean = false) {
        val baselineKey = "$KEY_TWEAK_BASELINE_PREFIX$key"
        val splitFingerprintBaselineNeedsMigration =
            (key == SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD ||
                key == SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN) &&
                (!localPrefs.getBoolean(KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED, false) ||
                    !localPrefs.contains(baselineKey))
        val baseline = if (splitFingerprintBaselineNeedsMigration) {
            // A previous build tracked the merged lockscreen+AOD switch in this baseline
            // slot. Rebase each surface once on its effective current value before the
            // two surfaces become independent, so changing only one still requests a
            // restart (including when the value comes from the legacy aggregate key).
            when (key) {
                SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD -> values.storage.fingerprint.hideFingerprintAodEnabled()
                else -> values.storage.fingerprint.hideFingerprintLockscreenEnabled()
            }
        } else if (localPrefs.contains(baselineKey)) {
            localPrefs.getBoolean(baselineKey, value)
        } else {
            when (key) {
                SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD -> values.storage.fingerprint.hideFingerprintAodEnabled()
                SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN -> values.storage.fingerprint.hideFingerprintLockscreenEnabled()
                else -> values.storage.getBoolean(key, defaultValue)
            }
        }
        val nextDirtyKeys = if (value == baseline) {
            dirtyTweakKeys - key
        } else {
            dirtyTweakKeys + key
        }
        val nextPendingScopes = if (value == baseline) {
            effectivePendingRestartScopes(nextDirtyKeys)
        } else {
            pendingRestartScopes.merge(TWEAK_RESTART_SCOPES[key] ?: RestartScopeSelection.Empty)
        }

        dirtyTweakKeys = nextDirtyKeys
        pendingRestartScopes = nextPendingScopes
        localPrefs.edit {
            putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
            putBoolean(baselineKey, baseline)
            if (key == SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD ||
                key == SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN) {
                putBoolean(KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED, true)
            }
            putStringSet(KEY_DIRTY_TWEAK_KEYS, nextDirtyKeys)
            putStringSet(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
            putStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, manualPendingRestartScopes.toKeySet())
        }
    }

    /**
     * Int-valued counterpart of [markTweaked] for selector tweaks (e.g. the lockscreen
     * fingerprint-avoidance mode). The baseline is stored as an Int in the same
     * `tweak_baseline_` slot, so it must never be mixed with a Boolean-typed key.
     */
    fun markTweakedInt(key: String, value: Int) {
        val baselineKey = "$KEY_TWEAK_BASELINE_PREFIX$key"
        val baseline = if (localPrefs.contains(baselineKey)) {
            localPrefs.getInt(baselineKey, value)
        } else {
            values.storage.getInt(key, value)
        }
        val nextDirtyKeys = if (value == baseline) {
            dirtyTweakKeys - key
        } else {
            dirtyTweakKeys + key
        }
        val nextPendingScopes = if (value == baseline) {
            effectivePendingRestartScopes(nextDirtyKeys)
        } else {
            pendingRestartScopes.merge(TWEAK_RESTART_SCOPES[key] ?: RestartScopeSelection.Empty)
        }

        dirtyTweakKeys = nextDirtyKeys
        pendingRestartScopes = nextPendingScopes
        localPrefs.edit {
            putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
            putInt(baselineKey, baseline)
            putStringSet(KEY_DIRTY_TWEAK_KEYS, nextDirtyKeys)
            putStringSet(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
            putStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, manualPendingRestartScopes.toKeySet())
        }
    }

    fun clearRestartedScopes(scopes: RestartScopeSelection) {
        val nextPendingScopes = pendingRestartScopes.without(scopes)
        val nextManualPendingScopes = manualPendingRestartScopes.without(scopes)
        val clearedKeys = dirtyTweakKeys.filter { key ->
            TWEAK_RESTART_SCOPES[key]?.let(nextPendingScopes::intersect)?.isEmpty() == true
        }.toSet()
        val nextDirtyKeys = dirtyTweakKeys - clearedKeys
        dirtyTweakKeys = nextDirtyKeys
        pendingRestartScopes = nextPendingScopes
        localPrefs.edit {
            putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
            clearedKeys.forEach { key ->
                if (key == SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID) {
                    // Int-typed selector tweaks store an Int baseline; the Boolean
                    // branch below would write a Boolean into the same slot and crash
                    // the next getInt baseline read with a ClassCastException.
                    putInt("$KEY_TWEAK_BASELINE_PREFIX$key", values.currentTweakValueInt(key))
                } else {
                    putBoolean("$KEY_TWEAK_BASELINE_PREFIX$key", values.currentTweakValue(key))
                }
            }
            putStringSet(KEY_DIRTY_TWEAK_KEYS, nextDirtyKeys)
            putStringSet(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
            putStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, nextManualPendingScopes.toKeySet())
        }
        manualPendingRestartScopes = nextManualPendingScopes
    }

    fun clearPendingRestartTracking() {
        dirtyTweakKeys = emptySet()
        pendingRestartScopes = RestartScopeSelection.Empty
        manualPendingRestartScopes = RestartScopeSelection.Empty
        localPrefs.edit {
            remove(KEY_PENDING_RESTART_BOOT_TOKEN)
            remove(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES)
            remove(KEY_MANUAL_PENDING_RESTART_SCOPES)
            remove(KEY_DIRTY_TWEAK_KEYS)
            TWEAK_RESTART_SCOPES.keys.forEach { remove("$KEY_TWEAK_BASELINE_PREFIX$it") }
        }
    }

    /** Records a secondary-page change for the shared Home restart dialog. */
    fun requestRestartScopes(scopes: RestartScopeSelection) {
        if (scopes.isEmpty()) return
        val nextManualPendingScopes = manualPendingRestartScopes.merge(scopes)
        val nextPendingScopes = pendingRestartScopes.merge(scopes)
        manualPendingRestartScopes = nextManualPendingScopes
        pendingRestartScopes = nextPendingScopes
        localPrefs.edit {
            putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
            putStringSet(
                PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES,
                nextPendingScopes.toKeySet()
            )
            putStringSet(
                KEY_MANUAL_PENDING_RESTART_SCOPES,
                nextManualPendingScopes.toKeySet()
            )
        }
    }

}
