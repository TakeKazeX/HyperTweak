// SPDX-License-Identifier: Apache-2.0
#pragma once
namespace hypertweak::native {
void SetDesktopIconPercent(int percent);
int DesktopIconPercentRequested();
bool DesktopIconScaleNeedsPreparation();
bool ApplyDesktopIconScaleRule(void* handle);
const char* DesktopIconScaleReason();
void ResetDesktopIconScaleAfterFork();
}
