#pragma once
#include <stdint.h>
namespace hypertweak::native {
bool DockFrameRequested();
bool ConfigureDockFrame(int32_t port, int64_t token0, int64_t token1);
bool ApplyDockFrameRule(void* handle);
void ResetDockFrameAfterFork();
}
