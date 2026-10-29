// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "dart_targets.h"
namespace hypertweak::native {
bool ApplyDesktopIconScope(uint8_t* base,const dart::IconScaleLayout& layout);
void ResetDesktopIconScopeAfterFork();
}
