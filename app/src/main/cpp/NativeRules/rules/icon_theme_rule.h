// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstdint>
namespace hypertweak::native {
void SetIconTheme(int mode);
bool IconThemeNeedsPreparation();
bool ApplyIconThemeRule(void* handle);
void ResetIconThemeAfterFork();
const char* IconThemeReason();
uintptr_t IconThemeObserverFrame(uintptr_t statics,uintptr_t null,uintptr_t sentinel,uintptr_t* receiver);
}
