// SPDX-License-Identifier: Apache-2.0
#pragma once
namespace hypertweak::native {
void SetXiaoaiGestureOptionsEnabled(bool);
bool XiaoaiGestureOptionsRequested();
const char* XiaoaiGestureOptionsReason();
bool ApplyXiaoaiGestureOptionsRule(void*);
void ResetXiaoaiGestureOptionsAfterFork();
}
