// SPDX-License-Identifier: Apache-2.0
//
// The rule-facing surface this payload must keep in every build.
//
// The hook primitives and the resolution helpers have no caller until a rule is
// written, and `-Wl,--gc-sections` would silently drop them — producing a
// payload that compiles, injects, resolves a launcher image, and still cannot
// hook anything. That failure mode is invisible from the build log, so the
// surface is anchored here instead of being left to chance.
//
// The table is emitted into a retained section (SHF_GNU_RETAIN), which the
// linker treats as a garbage-collection root, so every function referenced
// below survives whether or not a rule currently uses it.
#include "lsposed_hook_backend.h"

namespace hypertweak::native {
namespace {

using FrameworkEntry = void (*)();

[[gnu::used, gnu::retain]] const FrameworkEntry kFrameworkEntries[] = {
    // Hook primitives.
    reinterpret_cast<FrameworkEntry>(&InstallPltHook),
    reinterpret_cast<FrameworkEntry>(&InstallInlineHook),
    reinterpret_cast<FrameworkEntry>(&RemoveInlineHook),
    // Upstream resolver and page-guard primitives retained for feature rules.
    reinterpret_cast<FrameworkEntry>(&EnsureLsposedMadviseGuard),
    reinterpret_cast<FrameworkEntry>(&NewNativeSymbolResolver),
    reinterpret_cast<FrameworkEntry>(&FreeNativeSymbolResolver),
    reinterpret_cast<FrameworkEntry>(&GetNativeBaseAddress),
    reinterpret_cast<FrameworkEntry>(&LookupNativeSymbol),
    reinterpret_cast<FrameworkEntry>(&LookupNativePltSlot),
};

}  // namespace
}  // namespace hypertweak::native
