// SPDX-License-Identifier: Apache-2.0
//
// Removes the recents "clean up background apps" button by no-op'ing the Dart
// function that inserts its overlay.
//
// The target lives in MiuiHome's Dart AOT snapshot, which is mapped straight out
// of base.apk. The executable segments are r-xp, so patching depends on
// LSPosed's inline hook (which mprotects) and on the madvise page guard to keep
// the patched page alive.
//
// Every target is identified by the snapshot's build id *and* a prologue
// fingerprint before anything is written. A launcher update changes the build
// id, which fails the resolution closed instead of patching whatever now
// happens to sit at that offset.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native {

// Desired state, pushed from the module. Safe to call from any thread.
void SetClearButtonHidden(bool hidden);

// Resolves the Dart snapshot through the upstream native framework and brings
// the hook in line with the desired state. `dart_handle` is the handle supplied
// by the LSPosed load callback when available. Idempotent and safe to retry.
bool ApplyClearButtonRule(void* dart_handle = nullptr);

// True when the desired state is "hidden".
bool ClearButtonHiddenRequested();

// Number of times the patched function has been entered since installation.
uint32_t ClearButtonHookHits();

// Why the last apply attempt did not reach the desired state. A static string.
const char* ClearButtonRuleReason();

// Runtime address of the patched function, or 0 while it is unresolved.
uintptr_t ClearButtonTargetAddress();

// Gives the rule the same library-load boundary used by the upstream native
// payload.
void OnClearButtonLibraryLoaded(const char* name, void* handle);

// Called by the upstream action-down maintenance path, which is also where the
// upstream payload detects and repairs remapped Dart AOT pages.
void MaintainClearButtonRuleOnActionDown(void* dart_handle);

// Called from the upstream post-fork owner reset callback.
void ResetClearButtonStateAfterFork();

// Reads the shared module switch once during upstream native initialization.
void RefreshClearButtonConfig();

}  // namespace hypertweak::native

// These wrappers expose the upstream resolver's already-validated Dart image
// and range checks to the feature rule without duplicating its ELF scanner.
bool MiuiHomeHyosResolveDartImage(void* dart_handle, uint8_t** base_out,
                                  const uint8_t** build_id_out);
void* MiuiHomeHyosCurrentDartHandle();
bool MiuiHomeHyosDartRangeHasFlags(const uint8_t* base, uintptr_t offset,
                                   size_t size, uint32_t required_flags);
