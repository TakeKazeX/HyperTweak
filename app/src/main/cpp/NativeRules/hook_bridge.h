// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "native_api.h"

#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native {

// Installs the LSPosed native API. Must succeed before any hook is attempted;
// every entry point below fails closed until it does.
bool InitializeHookApi(const NativeAPIEntries* entries);

bool HookApiReady();

// Replaces the GOT slot of `symbol` inside the image mapped at `base`. Every
// matching slot must currently hold the same value, the write is rolled back on
// partial failure, and the slots are registered with the page guard first.
bool PltHook(void* base, const char* symbol, void* replacement, void** original);

// Replaces the entry point of `target`. The patched pages are published to the
// page guard before LSPosed writes the trampoline, because Xiaomi can issue
// MADV_DONTNEED concurrently with hook installation.
bool InlineHook(void* target, void* replacement, void** original);

bool InlineUnhook(void* target);

}  // namespace hypertweak::native
