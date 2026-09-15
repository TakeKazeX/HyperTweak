// SPDX-License-Identifier: Apache-2.0
//
// Bridge from a rule to the Dart target registry.
//
// A rule should not have to know how the launcher's AOT snapshot is found,
// validated, or searched. It asks for a target spec by name, receives the
// runtime address of every site, and either patches or gives up. Everything
// build specific lives behind this boundary: a launcher update is absorbed by
// the registry, and the rule keeps its own patch/hook logic unchanged.
//
// This file is Android-only by construction: it calls the upstream framework's
// image resolver, which does not exist on the host. The registry itself
// (dart_targets.cpp) stays host-buildable so `dart_probe` can run the identical
// finders offline.
#pragma once

#include "dart_targets.h"

#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native {

// Upper bound matches the registry's own per-target site capacity.
constexpr size_t kMaxResolvedDartSites = 8u;

struct ResolvedDartSite {
    const char* name;      // static, from the spec
    uintptr_t address;     // absolute runtime address
    // Whether the site's original bytes were still present when this resolution
    // was taken. False is the normal state once our own hook is installed, so a
    // rule must not treat it as an error -- it is how "already installed" is
    // told apart from "about to install".
    bool verified;
    // The site's verification contract, copied from the spec. A rule uses it to
    // tell "the original bytes are still here" from "our hook is in place",
    // which is how a remap is detected without a per-build table.
    dart::BytePattern verify;
    size_t verify_delta;
};

struct DartResolution {
    // Image base, so a rule can range-check addresses it derives itself.
    uint8_t* base;
    size_t site_count;
    ResolvedDartSite sites[kMaxResolvedDartSites];

    // True when the sites were located. Independent of `sites[].verified`: a
    // located-but-patched target is still a successful resolution.
    bool located;

    // Static string. On success "resolved"; otherwise one of
    // "dart_image_unresolved", "dart_elf_rejected", "dart_site_not_found".
    // Safe to store and log -- never a stack buffer.
    const char* reason;

    // Static pointer to the failing site's name, or nullptr. For diagnostics
    // only; `reason` is what a rule stores.
    const char* failing_site;

    // Every off these sites are guaranteed present only when the call returned
    // true. Look-up by name keeps a rule from depending on spec ordering.
    uintptr_t Find(const char* name) const;
    bool MatchVerify(const char* name) const;
};

// Resolves and verifies every site of `spec` against the Dart image mapped behind
// `dart_handle`. Returns true only when all required sites resolved and their
// verification bytes are present.
bool ResolveDartSites(void* dart_handle, const dart::TargetSpec& spec,
                      DartResolution* out);

}  // namespace hypertweak::native
