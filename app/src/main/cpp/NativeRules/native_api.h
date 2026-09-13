// SPDX-License-Identifier: Apache-2.0
//
// The native entry contract LSPosed uses for a module APK that ships a payload
// listed in META-INF/xposed/native_init.list.
#pragma once

#include <stdint.h>

struct NativeAPIEntries {
    uint32_t version;
    int (*hookFunc)(void* target, void* replacement, void** backup);
    int (*unhookFunc)(void* target);
};

// Called by LSPosed for every shared library the process loads after the
// payload itself. The callback receives the mapped file name and its handle.
using NativeOnModuleLoaded = void (*)(const char* name, void* handle);
