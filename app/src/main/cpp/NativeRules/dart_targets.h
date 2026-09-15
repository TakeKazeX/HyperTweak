// SPDX-License-Identifier: Apache-2.0
//
// Declarative target registry for the launcher's Dart AOT features.
//
// Adding a feature should mean adding a spec here plus a rule implementation --
// not editing the upstream hook file at six call sites, and not hand-maintaining
// a per-build RVA table. A spec names the site and says how to find it; the
// resolver owns uniqueness, fail-closed policy, and diagnostics.
//
// Discovery is tiered, and a site declares only the tiers it actually supports:
//
//   T1  masked byte pattern, required to match exactly once in the executable
//       segments. This is the whole story for a distinctive instruction
//       sequence, and one masked pattern can cover many builds when the only
//       variation sits in an encoded immediate.
//
//   T2/T3  a structural predicate used when T1 is ambiguous. This is real
//       reverse engineering per target: it keys on instruction shape and
//       call-graph relationships rather than on bytes, because a large part of
//       Dart AOT code shares byte-identical prologues and epilogues. A pattern
//       that matches hundreds of times is not made usable by widening it -- it
//       needs a different kind of evidence.
//
// A site with no tier that resolves is REJECTED, never guessed. The resolver
// never returns a best-effort candidate: an unreached feature is a bug report,
// a mis-patched snapshot is a crash inside the launcher.
#pragma once

#include "dart_image.h"

#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native::dart {

// Which evidence produced a resolved offset. Ordered by strength so diagnostics
// can report the weakest link in a feature's resolution.
enum class FindTier : uint32_t {
    kRejected = 0u,
    kMaskedBytes = 1u,
    kStructural = 2u,
};

const char* FindTierName(FindTier tier);

// A structural finder. It must return true only when the evidence is unique;
// reporting a plausible-but-ambiguous offset is a defect, not a fallback.
// `candidates_out` reports how many candidate families were seen so a rejection
// can explain itself.
using StructuralFinder = bool (*)(const Image& image, uintptr_t* offset_out,
                                  uint32_t* candidates_out);

struct SiteSpec {
    // Stable, human-readable site name; also the key used in diagnostics.
    const char* name;

    // T1 evidence. `pattern.bytes == nullptr` means this site has no byte
    // evidence and must be found structurally.
    BytePattern pattern;

    // T2/T3 evidence, tried only when T1 is absent or ambiguous.
    StructuralFinder structural;

    // True when the site is required for the feature to function. A required
    // site that fails rejects the whole feature; an optional site is reported
    // and skipped, so a partial capability degrades instead of disappearing.
    bool required;

    // Defence in depth: bytes that must be present at `offset + verify_delta`
    // before anything is written. This is not the finding evidence repeated --
    // it is the contract the rule relies on, and it must stay build independent.
    // For a site whose own bytes move between builds (a pool displacement, a
    // call target) this is a masked pattern or a nearby stable sequence, never a
    // build-specific constant. `bytes == nullptr` disables the re-check.
    BytePattern verify;
    size_t verify_delta;
};

struct TargetSpec {
    // Feature id, e.g. "recents_clear_button".
    const char* id;

    // The snapshot this feature patches, as matched by the upstream framework.
    // Kept for diagnostics only: resolution itself is build independent.
    const char* library_name;

    const SiteSpec* sites;
    size_t site_count;
};

// Outcome for one site.
struct SiteResult {
    const char* name;
    uintptr_t offset;
    uint32_t candidates;
    FindTier tier;
    // True when the finder produced a unique candidate. Tracked separately from
    // `verified` and `resolved` so a failure can say which stage rejected --
    // conflating them once made "nothing matched" report as "bytes mismatched".
    bool found;
    // True when the site's `verify` bytes were confirmed on the live image.
    bool verified;
    // found && verified. A caller must not patch a site unless this is true.
    bool resolved;
};

// Outcome for a whole feature.
//
// `resolved` means every required site was *located* -- it deliberately does NOT
// require the verification bytes to still match. Those bytes are the ones a rule
// overwrites when it installs its hook, so requiring them here would make a
// patched target unresolvable and leave a rule unable to tell "already installed"
// from "lost, reinstall". Location is structural; whether the original bytes are
// still present is the rule's question, answered at write time.
//
// `verified` is that answer for this pass: false is the normal state once our own
// hook is in place.
struct TargetResult {
    const char* id;
    bool resolved;
    bool verified;
    const char* reason;   // "resolved" | "no_sites" | "too_many_sites" | site name
    const char* failure;  // "none" | "not_found" | "verify_failed"
    SiteResult sites[8];
    size_t site_count;
};

// Runs the tiered finder for every site of `spec` against `image`.
TargetResult ResolveTarget(const Image& image, const TargetSpec& spec);

// The registered features. The device payload and the host probe both iterate
// this same list, so an offline verdict matches the device's.
extern const TargetSpec kRecentsClearButtonTarget;
extern const TargetSpec kFolderColumnsTarget;

extern const TargetSpec* const kTargetSpecs[];
extern const size_t kTargetSpecCount;

}  // namespace hypertweak::native::dart
