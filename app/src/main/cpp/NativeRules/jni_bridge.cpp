// SPDX-License-Identifier: Apache-2.0
//
// Status query and rule control for the module. The library is loaded in the
// launcher process by LSPosed, not by the module's own process, so
// `System.loadLibrary` on the Kotlin side only succeeds where LSPosed already
// injected it. The symbols are resolved by the default JNI lookup, so no
// JNI_OnLoad/RegisterNatives is needed.
#include "clear_button_rule.h"
#include "folder_columns_rule.h"
#include "native_config.h"

#include <jni.h>
#include <stdio.h>

namespace {

constexpr size_t kStatusBufferSize = 512u;

jstring BuildStatus(JNIEnv* env) {
    char buffer[kStatusBufferSize];
    snprintf(buffer, sizeof(buffer),
             "clearButton=%s/%s target=0x%zx hits=%u "
             "folderColumns=%d/%s target=0x%zx hits=%u config=%s",
             hypertweak::native::ClearButtonHiddenRequested() ? "hide" : "keep",
             hypertweak::native::ClearButtonRuleReason(),
             static_cast<size_t>(hypertweak::native::ClearButtonTargetAddress()),
             hypertweak::native::ClearButtonHookHits(),
             static_cast<int>(hypertweak::native::FolderColumnsRequested()),
             hypertweak::native::FolderColumnsRuleReason(),
             static_cast<size_t>(hypertweak::native::FolderColumnsTargetAddress()),
             hypertweak::native::FolderColumnsHookHits(),
             hypertweak::native::ConfigChannelPath());
    return env->NewStringUTF(buffer);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_takekazex_hypertweak_hook_NativeRules_nativeStatus(JNIEnv* env, jobject thiz) {
    (void)thiz;
    if (env == nullptr) return nullptr;
    return BuildStatus(env);
}
