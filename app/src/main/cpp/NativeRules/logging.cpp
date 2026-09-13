// SPDX-License-Identifier: Apache-2.0
#include "logging.h"

#include <android/log.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>

namespace hypertweak::native {
namespace {

constexpr char kTag[] = "HyperTweakNative";
constexpr size_t kMaxMessageSize = 512u;

void LogLine(int priority, const char* format, va_list arguments) {
    char message[kMaxMessageSize];
    const int written = vsnprintf(message, sizeof(message), format, arguments);
    if (written < 0) return;
    // vsnprintf truncates rather than overflowing, but the buffer is only
    // guaranteed to be terminated when the result fits.
    message[sizeof(message) - 1u] = '\0';
    __android_log_write(priority, kTag, message);
}

}  // namespace

void LogInfo(const char* format, ...) {
    va_list arguments;
    va_start(arguments, format);
    LogLine(ANDROID_LOG_INFO, format, arguments);
    va_end(arguments);
}

void LogWarn(const char* format, ...) {
    va_list arguments;
    va_start(arguments, format);
    LogLine(ANDROID_LOG_WARN, format, arguments);
    va_end(arguments);
}

void LogError(const char* format, ...) {
    va_list arguments;
    va_start(arguments, format);
    LogLine(ANDROID_LOG_ERROR, format, arguments);
    va_end(arguments);
}

}  // namespace hypertweak::native
