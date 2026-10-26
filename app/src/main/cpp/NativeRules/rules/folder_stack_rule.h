// SPDX-License-Identifier: Apache-2.0
#pragma once
namespace hypertweak::native {
void SetFolderStackEnabled(bool enabled);
bool FolderStackRequested();
bool ApplyFolderStackRule(void* handle);
const char* FolderStackRuleReason();
void ResetFolderStackAfterFork();
}
