// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stdint.h>

namespace hypertweak::native {
void SetNativeDockEnabled(bool enabled);
// Packed as in NativeDockGlassRecord. Height 0 fills the Dock area above the
// bottom offset. An invalid value is rejected and leaves the geometry as is.
bool SetNativeDockGlassGeometry(uint32_t packed);
bool NativeDockRequested();
bool PrepareNativeDockRule(void* handle);
bool ApplyNativeDockRule(void* handle);
const char* NativeDockRuleReason();
void ResetNativeDockAfterFork();
}
