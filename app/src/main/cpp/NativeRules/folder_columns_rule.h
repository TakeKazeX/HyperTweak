// SPDX-License-Identifier: Apache-2.0
//
// Forces the number of columns in the opened-folder application grid while
// keeping the small folder preview rendered on the desktop at three columns.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native {

// Desired opened-folder column count. Three means the native launcher default
// and deliberately leaves the AOT function untouched.
void SetFolderColumns(int32_t columns);
int32_t FolderColumnsRequested();

// Resolves the exact Dart snapshot through the upstream native framework and
// installs/removes the column-count return-epilogue replacement and preview
// guards as needed.
bool ApplyFolderColumnsRule(void* dart_handle = nullptr);

// Number of times the replacement getter has been entered since installation.
uint32_t FolderColumnsHookHits();

// Static reason for the last apply attempt.
const char* FolderColumnsRuleReason();

// Runtime address of the patched return epilogue, or 0 while unresolved.
uintptr_t FolderColumnsTargetAddress();

// Gives the rule the same library-load boundary used by the upstream native
// payload.
void OnFolderColumnsLibraryLoaded(const char* name, void* handle);

// Called by the upstream action-down maintenance path. This also repairs an
// inherited/remapped target when the launcher image changes.
void MaintainFolderColumnsRuleOnActionDown(void* dart_handle);

// Called from the upstream post-fork owner reset callback.
void ResetFolderColumnsStateAfterFork();

// Reads the shared module setting during native initialization.
void RefreshFolderColumnsConfig();

}  // namespace hypertweak::native
