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
#include "hook_bridge.h"
#include "image.h"

namespace hypertweak::native {
namespace {

using FrameworkEntry = void (*)();

[[gnu::used, gnu::retain]] const FrameworkEntry kFrameworkEntries[] = {
    // Hook primitives.
    reinterpret_cast<FrameworkEntry>(&PltHook),
    reinterpret_cast<FrameworkEntry>(&InlineHook),
    reinterpret_cast<FrameworkEntry>(&InlineUnhook),
    // Resolution primitives a rule needs to find and validate its target.
    reinterpret_cast<FrameworkEntry>(&FindImageViaMaps),
    reinterpret_cast<FrameworkEntry>(&FindImageByBuildId),
    reinterpret_cast<FrameworkEntry>(&SymbolLookup),
    reinterpret_cast<FrameworkEntry>(&ReadBytes),
    reinterpret_cast<FrameworkEntry>(&MatchesBytes),
};

}  // namespace
}  // namespace hypertweak::native
