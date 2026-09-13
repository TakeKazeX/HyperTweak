// SPDX-License-Identifier: Apache-2.0
//
// Status query and rule control for the module. The library is loaded in the
// launcher process by LSPosed, not by the module's own process, so
// `System.loadLibrary` on the Kotlin side only succeeds where LSPosed already
// injected it. The symbols are resolved by the default JNI lookup, so no
// JNI_OnLoad/RegisterNatives is needed.
#include "rules_entry.h"

#include <jni.h>

namespace {

constexpr size_t kStatusBufferSize = 320u;

jstring BuildStatus(JNIEnv* env) {
    char buffer[kStatusBufferSize];
    hypertweak::native::FormatStatus(buffer, sizeof(buffer));
    return env->NewStringUTF(buffer);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_takekazex_hypertweak_hook_NativeRules_nativeStatus(JNIEnv* env, jobject thiz) {
    (void)thiz;
    if (env == nullptr) return nullptr;
    return BuildStatus(env);
}
