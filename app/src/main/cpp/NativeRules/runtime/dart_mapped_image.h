#pragma once

#include "launcher_profiles.h"
#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native::mapped {
constexpr char kDartSnapshotInstructionsSymbol[] =
        "_kDartIsolateSnapshotInstructions";
constexpr char kDartSnapshotBuildIdSymbol[] = "_kDartSnapshotBuildId";

bool MatchesCode(const uint8_t* base, uintptr_t offset, const uint8_t* expected, size_t size);
bool DartMappedRangeHasFlags(const uint8_t* base, uintptr_t offset, size_t size, uint32_t flags);
bool DartBlTargets(const uint8_t* base, uintptr_t offset, uintptr_t target);
bool MatchesLauncherProfile(const uint8_t* base, void* entry, const miui_home_profiles::LauncherProfile& profile);
}  // namespace hypertweak::native::mapped

// Existing native ABI used by rule support, not new exports.
bool MiuiHomeHyosResolveDartImage(void* handle, uint8_t** base, const uint8_t** build_id);
bool MiuiHomeHyosDartRangeHasFlags(const uint8_t* base, uintptr_t offset, size_t size, uint32_t flags);
