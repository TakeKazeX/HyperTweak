// SPDX-License-Identifier: Apache-2.0
package com.takekazex.hypertweak.hook.rules.backgesture;

/** Shared wire names for the UI, SystemUI, and native-hook status challenge. */
public final class NativeHookStatusProtocol {
    public static final String PACKAGE_NAME =
            "com.takekazex.hypertweak";
    public static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    public static final String MIUI_HOME_PACKAGE = "com.miui.home";
    public static final String ACTION_QUERY =
            "com.takekazex.hypertweak.backgesture.action.RUNTIME_STATUS_QUERY";
    public static final String ACTION_REPLY =
            "com.takekazex.hypertweak.backgesture.action.RUNTIME_STATUS_REPLY";
    public static final String ACTION_SYSTEMUI_STATE =
            "com.takekazex.hypertweak.backgesture.action.SYSTEMUI_INPUT_ARBITER_STATE";

    public static final String EXTRA_NONCE = "status_nonce";
    public static final String EXTRA_QUERY = "status_query";
    public static final String EXTRA_NATIVE_RESPONSE = "status_native_response";
    public static final String EXTRA_LEGACY_MODE = "status_legacy_mode";
    public static final String EXTRA_LEGACY_READY = "status_legacy_ready";
    public static final String EXTRA_NATIVE_READY = "status_native_ready";
    public static final String EXTRA_NATIVE_PROFILE_RESOLVED =
            "status_native_profile_resolved";
    public static final String EXTRA_NATIVE_PROFILE_DYNAMIC =
            "status_native_profile_dynamic";
    public static final String EXTRA_NATIVE_PROFILE_ENTRY_OFFSET =
            "status_native_profile_entry_offset";
    public static final String EXTRA_NATIVE_SIDE_OFFSET = "status_native_side_offset";
    public static final String EXTRA_NATIVE_RUNTIME_PROFILE_STAGE =
            "status_native_runtime_profile_stage";
    public static final String EXTRA_NATIVE_DART_RESOLVER_STAGE =
            "status_native_dart_resolver_stage";
    public static final String EXTRA_NATIVE_DART_DRAWER_CANDIDATES =
            "status_native_dart_drawer_candidates";
    public static final String EXTRA_NATIVE_DART_TRANSITION_CANDIDATES =
            "status_native_dart_transition_candidates";
    public static final String EXTRA_NATIVE_DART_OVERVIEW_ENTER_CANDIDATES =
            "status_native_dart_overview_enter_candidates";
    public static final String EXTRA_NATIVE_DART_OVERVIEW_EXIT_CANDIDATES =
            "status_native_dart_overview_exit_candidates";
    public static final String EXTRA_NATIVE_DART_EDITING_CANDIDATES =
            "status_native_dart_editing_candidates";
    public static final String EXTRA_NATIVE_DRAWER_STATE_READY =
            "status_native_drawer_state_ready";
    public static final String EXTRA_NATIVE_OVERVIEW_STATE_READY =
            "status_native_overview_state_ready";
    public static final String EXTRA_NATIVE_EDITING_STATE_READY =
            "status_native_editing_state_ready";
    public static final String EXTRA_NATIVE_BUSINESS_STATE =
            "status_native_business_state";
    public static final String EXTRA_NATIVE_BRIDGE_STATE =
            "status_native_bridge_state";
    public static final String EXTRA_NATIVE_RECEIVER_STATE =
            "status_native_receiver_state";
    public static final String EXTRA_SYSTEMUI_READY = "status_systemui_ready";
    public static final String EXTRA_SYSTEMUI_GENERATION =
            "status_systemui_generation";
    public static final String EXTRA_SYSTEMUI_MONITORS = "status_systemui_monitors";
    public static final String EXTRA_REASON = "status_reason";

    private NativeHookStatusProtocol() {}
}
