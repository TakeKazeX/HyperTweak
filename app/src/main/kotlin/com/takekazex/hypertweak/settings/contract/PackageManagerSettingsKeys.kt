package com.takekazex.hypertweak.settings.contract

internal object PackageManagerSettingsKeys {
    const val KEY_ENABLED = "package_manager_core_patch_enabled"
    const val KEY_DOWNGRADE = "package_manager_allow_downgrade"
    const val KEY_LOW_TARGET = "package_manager_allow_low_target"

    /** HyperCeiler core_patch_auth_creak: v1 schemes, APK integrity and compressed resources. */
    const val KEY_AUTH_CREAK = "package_manager_disable_package_manager_signature_verification"

    /** HyperCeiler core_patch_disable_integrity: disables JAR signature rollback protection. */
    const val KEY_DISABLE_INTEGRITY = "package_manager_disable_system_integrity_check"

    const val KEY_UPDATE_SIGNATURE = "package_manager_allow_different_update_signature"
    const val KEY_SPLIT_SIGNATURE = "package_manager_allow_different_split_signature"
    const val KEY_SHARED_UID = "package_manager_allow_different_shared_uid_signature"
    const val KEY_SYSTEM_SHARED_UID = "package_manager_allow_system_shared_uid"
    const val KEY_VERIFICATION_AGENT = "package_manager_disable_verification_agent"
    const val KEY_PERSISTENT_UPDATE = "package_manager_allow_persistent_update"
    const val KEY_SYSTEM_APP_UPDATE = "package_manager_allow_system_app_update"
    const val KEY_ISOLATION = "package_manager_bypass_hyperos_isolation"

    /**
     * The three old independent switches merged into [KEY_AUTH_CREAK]. Kept as read-only
     * migration inputs so an existing configuration keeps its effective behavior.
     */
    val legacyAuthCreakKeys = setOf(
        "package_manager_relax_signature_scheme",
        "package_manager_relax_apk_integrity",
        "package_manager_allow_compressed_resources",
    )

    /** Options controlled by the page's master switch. */
    val corePatchKeys = setOf(
        KEY_DOWNGRADE, KEY_UPDATE_SIGNATURE, KEY_AUTH_CREAK,
        KEY_DISABLE_INTEGRITY, KEY_SPLIT_SIGNATURE, KEY_SHARED_UID, KEY_SYSTEM_SHARED_UID,
    )

    /** Independent install options, always available regardless of the master switch. */
    val extendedPatchKeys = setOf(
        KEY_LOW_TARGET, KEY_VERIFICATION_AGENT, KEY_PERSISTENT_UPDATE,
        KEY_SYSTEM_APP_UPDATE, KEY_ISOLATION,
    )

    val patchKeys = corePatchKeys + extendedPatchKeys
    val keys = patchKeys + KEY_ENABLED
}
