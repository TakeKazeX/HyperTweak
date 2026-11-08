package com.takekazex.hypertweak.settings.contract

internal object PackageManagerSettingsKeys {
    const val KEY_ENABLED = "package_manager_core_patch_enabled"
    const val KEY_DOWNGRADE = "package_manager_allow_downgrade"
    const val KEY_LOW_TARGET = "package_manager_allow_low_target"
    const val KEY_MIN_SIGNATURE_SCHEME = "package_manager_relax_signature_scheme"
    const val KEY_APK_INTEGRITY = "package_manager_relax_apk_integrity"
    const val KEY_COMPRESSED_RESOURCES = "package_manager_allow_compressed_resources"
    const val KEY_UPDATE_SIGNATURE = "package_manager_allow_different_update_signature"
    const val KEY_SPLIT_SIGNATURE = "package_manager_allow_different_split_signature"
    const val KEY_SHARED_UID = "package_manager_allow_different_shared_uid_signature"
    const val KEY_VERIFICATION_AGENT = "package_manager_disable_verification_agent"
    const val KEY_PERSISTENT_UPDATE = "package_manager_allow_persistent_update"
    const val KEY_SYSTEM_APP_UPDATE = "package_manager_allow_system_app_update"
    const val KEY_ISOLATION = "package_manager_bypass_hyperos_isolation"

    val patchKeys = setOf(
        KEY_DOWNGRADE, KEY_LOW_TARGET, KEY_MIN_SIGNATURE_SCHEME, KEY_APK_INTEGRITY,
        KEY_COMPRESSED_RESOURCES, KEY_UPDATE_SIGNATURE, KEY_SPLIT_SIGNATURE, KEY_SHARED_UID,
        KEY_VERIFICATION_AGENT, KEY_PERSISTENT_UPDATE, KEY_SYSTEM_APP_UPDATE, KEY_ISOLATION,
    )
    val keys = patchKeys + KEY_ENABLED
}
