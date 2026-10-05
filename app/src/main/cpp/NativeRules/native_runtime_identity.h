// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stdint.h>
namespace hypertweak::native {
struct __attribute__((packed)) NativeRuntimeIdentity {
    uint32_t schema = 1u;
    uint32_t version;
    uint32_t pid;
    uint32_t ready = 1u;
    uint64_t start_ticks;
    uint32_t checksum;
};
inline NativeRuntimeIdentity RuntimeIdentity(uint32_t version, uint32_t pid, uint64_t start_ticks) {
    return {1u, version, pid, 1u, start_ticks,
            0x48544e53u ^ version ^ pid ^ static_cast<uint32_t>(start_ticks) ^ static_cast<uint32_t>(start_ticks >> 32u)};
}
static_assert(sizeof(NativeRuntimeIdentity) == 28u);
}
