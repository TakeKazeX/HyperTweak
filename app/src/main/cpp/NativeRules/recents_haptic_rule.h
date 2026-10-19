// SPDX-License-Identifier: Apache-2.0
#pragma once
namespace hypertweak::native {
void SetRecentsHapticEnabled(bool enabled);
bool RecentsHapticRequested();
bool ApplyRecentsHapticRule(void* handle);
const char* RecentsHapticRuleReason();
void ResetRecentsHapticAfterFork();
}
