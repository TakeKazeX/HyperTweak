// SPDX-License-Identifier: Apache-2.0
#include "dart_rule_support.h"

#include "logging.h"

#include <string.h>

// Declared by the upstream wrapper in clear_button_rule.h at global scope: the
// framework's own image validation, shared so a rule cannot disagree with it
// about what a valid Dart snapshot is.
bool MiuiHomeHyosResolveDartImage(void* dart_handle, uint8_t** base_out,
                                  const uint8_t** build_id_out);

namespace hypertweak::native {
namespace {

// Resolution costs a full scan of the launcher's executable segments (tens of
// megabytes, several passes). The rules call this from the input maintenance
// path on every action-down, so an uncached resolve there blocks the launcher's
// input thread and produced a real "Input dispatching timed out" ANR. Results --
// including failures -- are therefore memoized per (image base, spec): the image
// cannot change without its base changing, so a base match is a valid identity.
//
// Failures are cached deliberately. A site that cannot be found stays unfound
// until the image changes, and retrying the full scan on every gesture is exactly
// what made the device unresponsive.
constexpr size_t kMaxCacheEntries = 8u;

// The identity of a resolved image. `base` alone is not enough: a launcher remap
// can unmap and reload the snapshot at the same address, and the cached offsets
// would then describe a build that is no longer there. The 32-byte GNU build-id
// note is what distinguishes the two, so it is part of the key.
//
// This is a cache identity, not a gate. Resolution itself stays build
// independent -- a new build simply misses the cache and is re-resolved.
constexpr size_t kImageIdentitySize = 32u;

struct CacheEntry {
    uint8_t* base;
    const dart::TargetSpec* spec;
    uint8_t identity[kImageIdentitySize];
    bool identity_valid;
    DartResolution result;
};

CacheEntry g_cache[kMaxCacheEntries];
uint32_t g_cache_ready[kMaxCacheEntries] = {};
size_t g_cache_next = 0u;

bool IdentityMatches(const CacheEntry& entry, bool identity_valid,
                     const uint8_t* identity) {
    if (entry.identity_valid != identity_valid) return false;
    if (!identity_valid) return true;
    if (identity == nullptr) return false;
    return memcmp(entry.identity, identity, kImageIdentitySize) == 0;
}

const DartResolution* CacheLookup(uint8_t* base, const dart::TargetSpec* spec,
                                  const uint8_t* identity) {
    const bool identity_valid = identity != nullptr;
    for (size_t index = 0u; index < kMaxCacheEntries; ++index) {
        if (__atomic_load_n(&g_cache_ready[index], __ATOMIC_ACQUIRE) == 0u) {
            continue;
        }
        if (g_cache[index].base != base || g_cache[index].spec != spec) {
            continue;
        }
        if (!IdentityMatches(g_cache[index], identity_valid, identity)) {
            continue;
        }
        return &g_cache[index].result;
    }
    return nullptr;
}

void CacheStore(uint8_t* base, const dart::TargetSpec* spec,
                const uint8_t* identity, const DartResolution& result) {
    // Plain round-robin over fixed slots. A concurrent store can only cost one
    // redundant resolve, never a wrong answer, because every entry is keyed by
    // the image base, the build identity and the spec, and is published with a
    // release store.
    const size_t slot = g_cache_next % kMaxCacheEntries;
    ++g_cache_next;
    __atomic_store_n(&g_cache_ready[slot], 0u, __ATOMIC_RELEASE);
    g_cache[slot].base = base;
    g_cache[slot].spec = spec;
    g_cache[slot].identity_valid = identity != nullptr;
    if (identity != nullptr) {
        memcpy(g_cache[slot].identity, identity, kImageIdentitySize);
    } else {
        memset(g_cache[slot].identity, 0, kImageIdentitySize);
    }
    g_cache[slot].result = result;
    __atomic_store_n(&g_cache_ready[slot], 1u, __ATOMIC_RELEASE);
}

// Logs one line per site so a device-side failure names the stage and the
// candidate count instead of only the first failing site.
void LogResult(const dart::TargetResult& result) {
    for (size_t index = 0u; index < result.site_count; ++index) {
        const dart::SiteResult& site = result.sites[index];
        LogWarn("dart site %s/%s found=%d verified=%d tier=%s candidates=%u",
                result.id, site.name == nullptr ? "?" : site.name,
                site.found ? 1 : 0, site.verified ? 1 : 0,
                dart::FindTierName(site.tier), site.candidates);
    }
}

}  // namespace

uintptr_t DartResolution::Find(const char* name) const {
    if (name == nullptr) return 0u;
    for (size_t index = 0u; index < site_count; ++index) {
        if (sites[index].name != nullptr &&
                strcmp(sites[index].name, name) == 0) {
            return sites[index].address;
        }
    }
    return 0u;
}

bool DartResolution::MatchVerify(const char* name) const {
    if (name == nullptr) return false;
    for (size_t index = 0u; index < site_count; ++index) {
        if (sites[index].name == nullptr ||
                strcmp(sites[index].name, name) != 0) {
            continue;
        }
        const ResolvedDartSite& site = sites[index];
        if (site.verify.bytes == nullptr || site.verify.size == 0u) {
            return site.address != 0u;
        }
        return dart::MatchPatternAtAddress(site.address + site.verify_delta,
                                           site.verify);
    }
    return false;
}

bool ResolveDartSites(void* dart_handle, const dart::TargetSpec& spec,
                      DartResolution* out) {
    if (out == nullptr) return false;
    out->base = nullptr;
    out->site_count = 0u;
    out->located = false;
    out->reason = "dart_not_attempted";
    out->failing_site = nullptr;

    // The upstream wrapper proves the image identity and the mapped ranges before
    // anything reads it, so the registry never searches an unvalidated mapping.
    uint8_t* base = nullptr;
    const uint8_t* build_id = nullptr;
    if (!MiuiHomeHyosResolveDartImage(dart_handle, &base, &build_id) ||
            base == nullptr) {
        out->reason = "dart_image_unresolved";
        return false;
    }

    // Memoized: this runs on the launcher's input thread.
    if (const DartResolution* cached = CacheLookup(base, &spec, build_id)) {
        *out = *cached;
        return cached->located;
    }

    dart::Image image{};
    if (!dart::ParseImage(base, &image)) {
        LogWarn("dart image rejected base=%p", static_cast<void*>(base));
        out->reason = "dart_elf_rejected";
        return false;
    }
    // First-resolve only (cache miss): the parsed segment table is what every
    // finder and every verify check is evaluated against, so a device that
    // disagrees with the offline probe must be diagnosed from it directly.
    LogWarn("dart image ok base=%p span=0x%zx loads=%zu",
            static_cast<void*>(base), static_cast<size_t>(image.image_span),
            image.load_count);
    for (size_t index = 0u; index < image.load_count; ++index) {
        LogWarn("dart load[%zu] start=0x%zx end=0x%zx flags=0x%x", index,
                static_cast<size_t>(image.loads[index].start),
                static_cast<size_t>(image.loads[index].end),
                image.loads[index].flags);
    }

    const dart::TargetResult result = dart::ResolveTarget(image, spec);
    if (!result.resolved) {
        LogResult(result);
        // The failing site's name is static (it lives in the spec), so it is safe
        // to publish; the reason stays one of a small fixed set so the module UI
        // and the log never see a dangling pointer.
        out->reason = "dart_site_not_found";
        out->failing_site = result.reason;
        CacheStore(base, &spec, build_id, *out);
        return false;
    }

    // Copy the resolved sites plus their verification contracts. The registry
    // fixes site order, but callers look up by name, so the order is not part of
    // the contract.
    if (result.site_count > kMaxResolvedDartSites ||
            result.site_count > spec.site_count) {
        out->reason = "dart_site_overflow";
        return false;
    }
    for (size_t index = 0u; index < result.site_count; ++index) {
        const dart::SiteResult& site = result.sites[index];
        ResolvedDartSite& target = out->sites[index];
        target.name = site.name;
        target.address = reinterpret_cast<uintptr_t>(base) + site.offset;
        target.verified = site.verified;
        target.verify = spec.sites[index].verify;
        target.verify_delta = spec.sites[index].verify_delta;
    }
    out->site_count = result.site_count;
    out->base = base;
    out->located = true;
    out->reason = "resolved";
    CacheStore(base, &spec, build_id, *out);
    return true;
}

}  // namespace hypertweak::native
