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

#include <stdint.h>

namespace hypertweak::native {

// Desired state, pushed from the module. Safe to call from any thread.
void SetClearButtonHidden(bool hidden);

// Resolves the Dart snapshot and brings the hook in line with the desired
// state. Idempotent and safe to retry; returns true once the desired state is
// in effect (which, for "not hidden", is immediately).
bool ApplyClearButtonRule();

// True when the desired state is "hidden".
bool ClearButtonHiddenRequested();

// Number of times the patched function has been entered since installation.
uint32_t ClearButtonHookHits();

// Why the last apply attempt did not reach the desired state. A static string.
const char* ClearButtonRuleReason();

// Runtime address of the patched function, or 0 while it is unresolved.
uintptr_t ClearButtonTargetAddress();

}  // namespace hypertweak::native
