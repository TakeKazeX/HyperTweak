// SPDX-License-Identifier: Apache-2.0
//
// Status query and rule control for the module. The library is loaded in the
// launcher process by LSPosed, not by the module's own process, so
// `System.loadLibrary` on the Kotlin side only succeeds where LSPosed already
// injected it. The symbols are resolved by the default JNI lookup, so no
// JNI_OnLoad/RegisterNatives is needed.
#include "clear_button_rule.h"
#include "folder_columns_rule.h"
#include "assistant_widget_rule.h"
#include "native_config.h"
#include "native_rule_runtime.h"

#include <jni.h>
#include <stdio.h>

namespace {

constexpr size_t kStatusBufferSize = 512u;

jstring BuildStatus(JNIEnv* env) {
    char buffer[kStatusBufferSize];
    snprintf(buffer, sizeof(buffer),
             "clearButton=%s/%s target=0x%zx hits=%u "
             "folderColumns=%d/%s target=0x%zx hits=%u "
             "assistantWidgets=%d/%s target=0x%zx hits=%u config=%s source=%s",
             hypertweak::native::ClearButtonHiddenRequested() ? "hide" : "keep",
             hypertweak::native::ClearButtonRuleReason(),
             static_cast<size_t>(hypertweak::native::ClearButtonTargetAddress()),
             hypertweak::native::ClearButtonHookHits(),
             static_cast<int>(hypertweak::native::FolderColumnsRequested()),
             hypertweak::native::FolderColumnsRuleReason(),
             static_cast<size_t>(hypertweak::native::FolderColumnsTargetAddress()),
             hypertweak::native::FolderColumnsHookHits(),
             hypertweak::native::AssistantWidgetAllowedRequested() ? 1 : 0,
             hypertweak::native::AssistantWidgetRuleReason(),
             static_cast<size_t>(hypertweak::native::AssistantWidgetTargetAddress()),
             hypertweak::native::AssistantWidgetHookHits(),
             hypertweak::native::ConfigChannelPath(),
             hypertweak::native::NativeRuleRuntimeSource());
    return env->NewStringUTF(buffer);
}

}  // namespace

// Defined in miui_home_native_hook.cpp, which owns the contextual-search gate.
extern "C" void HyperTweakSetContextualSearchLongPress(bool enabled);

extern "C" JNIEXPORT jstring JNICALL
Java_com_takekazex_hypertweak_hook_NativeRules_nativeStatus(JNIEnv* env, jobject thiz) {
    (void)thiz;
    if (env == nullptr) return nullptr;
    return BuildStatus(env);
}

// The authoritative switch channel. `NativeRuleConfig` also publishes the same
// values as a file, but only the module's own process can read that file back:
// the launcher runs as `platform_app_36` with an ordinary app uid, is neither
// the file's owner nor in its `media_rw` group, and scoped storage refuses it,
// so the file values never reach the rules. The module's Java is injected into
// the launcher, so it hands the values over here instead.
//
// Safe to call from any thread. The runtime persists this Preferences snapshot
// in the launcher's own DE directory and wakes its preparation worker. The two
// Dart rules install at a subsequent input/load boundary, never on this JNI call.
extern "C" JNIEXPORT void JNICALL
Java_com_takekazex_hypertweak_hook_NativeRules_nativeApplyRuleSwitches(
        JNIEnv* env, jobject thiz, jboolean hide_recents_clear, jint folder_columns,
        jboolean contextual_search_long_press, jboolean assistant_widgets) {
    (void)env;
    (void)thiz;
    hypertweak::native::UpdateNativeRuleSettings(
            hide_recents_clear == JNI_TRUE, static_cast<int32_t>(folder_columns),
            contextual_search_long_press == JNI_TRUE, assistant_widgets == JNI_TRUE);
}
