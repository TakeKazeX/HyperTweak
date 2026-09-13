// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native {

// LSPosed calls native_init while the process is still starting. The launcher's
// own images (`libapp_launcher.so`, the Dart AOT snapshot) are mapped later by
// the Flutter/Rust runtime, so the payload cannot resolve anything yet.
// StartInstaller returns immediately and does the work on a detached thread.
void StartInstaller();

// Invoked by LSPosed for every shared library the process loads afterwards.
// It fires hundreds of times for the launcher, so it only performs the small
// amount of lifecycle work needed by the native rules.
void OnLibraryLoaded(const char* name, void* handle);

enum class Stage : int32_t {
    kIdle = 0,
    kWrongProcess = 1,
    kWaitingForLauncherImage = 2,
    kReady = 3,
};

Stage CurrentStage();

// Renders a one-line status for the module UI. Never blocks and never performs
// I/O beyond the atomics it reads, because it runs on a Binder thread.
size_t FormatStatus(char* buffer, size_t size);

}  // namespace hypertweak::native
