// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "native_api.h"

constexpr int kHookSuccess = 0;
constexpr int kHookFailed = 1;

struct NativeSymbolResolver;

bool InitializeLsposedHookBackend(const NativeAPIEntries* entries);
bool EnsureLsposedMadviseGuard(const char* runtime_name = nullptr);
bool LsposedMadviseGuardReady();
int InstallPltHook(void* base_addr, const char* symbol, void* hook_handler,
                   void** original);
int InstallInlineHook(void* target, void* replacement, void** original);
int RemoveInlineHook(void* target);
NativeSymbolResolver* NewNativeSymbolResolver(const char* path,
                                              void* base_addr);
void FreeNativeSymbolResolver(NativeSymbolResolver* resolver);
void* GetNativeBaseAddress(NativeSymbolResolver* resolver);
void* LookupNativeSymbol(NativeSymbolResolver* resolver, const char* name,
                         bool prefix, size_t* size);
// Returns the sole R_AARCH64_JUMP_SLOT for an exact dynamic symbol name.
// The lookup is read-only and fails closed for missing or ambiguous slots.
void** LookupNativePltSlot(NativeSymbolResolver* resolver, const char* name);

// Some HyperOS launcher builds map the Dart AOT ELF out of base.apk without
// publishing a libapp.so link-map entry. Reuse the upstream MapInfo scan and
// return one target only when its ELF build-id, segment, and backing mapping
// all agree. The target range is required to be readable and executable.
bool FindMappedImageTargetByBuildId(const uint8_t* build_id,
                                    size_t build_id_size,
                                    uintptr_t build_id_offset,
                                    uintptr_t target_offset,
                                    size_t target_size,
                                    void** target);
