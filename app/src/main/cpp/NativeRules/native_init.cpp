// SPDX-License-Identifier: Apache-2.0
//
// The single entry LSPosed calls for this payload, declared by
// app/src/main/resources/META-INF/xposed/native_init.list.
#include "hook_bridge.h"
#include "native_api.h"
#include "rules_entry.h"

// LSPosed injects the library into every scoped process and then resolves this
// symbol. Returning a callback is what keeps the payload alive: the callback is
// invoked for each library the process loads afterwards, which is also how the
// launcher's own libraries are observed.
extern "C" __attribute__((visibility("default"), unused))
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    if (!hypertweak::native::InitializeHookApi(entries)) {
        // No API means no page guard and no hook. Failing here leaves the host
        // process completely untouched, which is the only safe outcome.
        return nullptr;
    }
    hypertweak::native::StartInstaller();
    return hypertweak::native::OnLibraryLoaded;
}
