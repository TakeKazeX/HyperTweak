// SPDX-License-Identifier: Apache-2.0
#pragma once

namespace hypertweak::native {

// Every line this payload writes goes through one tag, so a device capture is
// `adb logcat -s HyperTweakNative:V`. Warnings and errors are also mirrored to
// the module's own log file only by the Kotlin side; the payload stays
// dependency-free and never touches Preferences.
void LogInfo(const char* format, ...) __attribute__((format(printf, 1, 2)));
void LogWarn(const char* format, ...) __attribute__((format(printf, 1, 2)));
void LogError(const char* format, ...) __attribute__((format(printf, 1, 2)));

}  // namespace hypertweak::native
