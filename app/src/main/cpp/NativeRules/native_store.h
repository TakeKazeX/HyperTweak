// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stddef.h>

namespace hypertweak::native {
// The launcher's own DE files, never the module's inaccessible Android/media path.
bool ReadNativeRecord(const char* name, void* data, size_t size);
bool WriteNativeRecord(const char* name, const void* data, size_t size);
void DeleteNativeRecord(const char* name);
}
