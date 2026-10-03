// SPDX-License-Identifier: Apache-2.0
#include "dart_rule_support.h"

#include "logging.h"
#include "dart_resolution_cache.h"
#include "native_store.h"
#include <pthread.h>
#include <time.h>
#include <stdio.h>

#include <string.h>

// Declared by the upstream wrapper in clear_button_rule.h at global scope: the
// framework's own image validation, shared so a rule cannot disagree with it
// about what a valid Dart snapshot is.
bool MiuiHomeHyosResolveDartImage(void* dart_handle, uint8_t** base_out,
                                  const uint8_t** build_id_out);

namespace hypertweak::native {
namespace {

// Full scans are permitted only on the preparation worker. Input callbacks do
// a bounded cache lookup and byte check, then wait for a later input boundary.
constexpr size_t kMaxCacheEntries = 8u;
constexpr size_t kImageIdentitySize = kDartIdentitySize;
struct CacheEntry {
    uint8_t* base;
    const dart::TargetSpec* spec;
    uint8_t identity[kImageIdentitySize];
    DartResolution result;
    uint32_t attempts;
    uint64_t retry_after;
};
CacheEntry g_cache[kMaxCacheEntries]{};
size_t g_cache_next = 0u;
pthread_mutex_t g_cache_lock = PTHREAD_MUTEX_INITIALIZER;
thread_local bool g_allow_scan = false;
uint64_t NowNs() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<uint64_t>(now.tv_sec) * 1000000000ull + now.tv_nsec;
}
bool SameKey(const CacheEntry& entry, uint8_t* base,
             const dart::TargetSpec& spec, const uint8_t* identity) {
    return identity != nullptr && entry.base == base && entry.spec == &spec &&
           memcmp(entry.identity, identity, kImageIdentitySize) == 0;
}
bool CacheLookup(uint8_t* base, const dart::TargetSpec& spec,
                 const uint8_t* identity, DartResolution* result) {
    bool found = false;
    pthread_mutex_lock(&g_cache_lock);
    for (const auto& entry : g_cache) {
        if (!SameKey(entry, base, spec, identity)) continue;
        // A failed scan can be retried after settling, at most three times per
        // image/configuration generation. Never persist negative results.
        if (g_allow_scan && !entry.result.located && entry.attempts < 3u &&
            NowNs() >= entry.retry_after) break;
        *result = entry.result;
        found = true;
        break;
    }
    pthread_mutex_unlock(&g_cache_lock);
    return found;
}
void CacheStore(uint8_t* base, const dart::TargetSpec& spec,
                const uint8_t* identity, const DartResolution& result) {
    if (identity == nullptr) return;
    pthread_mutex_lock(&g_cache_lock);
    size_t slot = kMaxCacheEntries;
    for (size_t i = 0u; i < kMaxCacheEntries; ++i) {
        if (SameKey(g_cache[i], base, spec, identity)) { slot = i; break; }
    }
    if (slot == kMaxCacheEntries) {
        slot = g_cache_next++ % kMaxCacheEntries;
        g_cache[slot] = CacheEntry{};
    }
    auto& entry = g_cache[slot];
    entry.base = base;
    entry.spec = &spec;
    memcpy(entry.identity, identity, kImageIdentitySize);
    entry.result = result;
    ++entry.attempts;
    entry.retry_after = NowNs() + 1000000000ull;
    pthread_mutex_unlock(&g_cache_lock);
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
    if (!ValidDartCacheIdentity(build_id)) {
        out->reason = "dart_identity_rejected";
        return false;
    }

    // Memoized: this runs on the launcher's input thread.
    if (CacheLookup(base, spec, build_id, out)) return out->located;
    if (!g_allow_scan) {
        out->reason = "dart_preparation_pending";
        return false;
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

    dart::TargetResult result{};
    DartCacheRecord record{};
    char cache_name[64];
    snprintf(cache_name, sizeof(cache_name), "%s.cache", spec.id);
    const bool exists = ReadNativeRecord(cache_name, &record, sizeof(record));
    const bool restored = exists && DecodeDartCache(image, spec, build_id,
                                HYPERTWEAK_NATIVE_VERSION, record, &result);
    if (restored) {
        LogInfo("dart persistent cache restored: %s", spec.id);
    } else {
        if (exists) {
            DeleteNativeRecord(cache_name);
            LogInfo("dart persistent cache invalidated: %s", spec.id);
        }
        result = dart::ResolveTarget(image, spec);
        if (EncodeDartCache(image, spec, build_id, HYPERTWEAK_NATIVE_VERSION,
                            result, &record)) {
            LogInfo("dart persistent cache saved: %s success=%d", spec.id,
                    WriteNativeRecord(cache_name, &record, sizeof(record)) ? 1 : 0);
        }
    }
    if (!result.resolved) {
        LogResult(result);
        // The failing site's name is static (it lives in the spec), so it is safe
        // to publish; the reason stays one of a small fixed set so the module UI
        // and the log never see a dangling pointer.
        out->reason = "dart_site_not_found";
        out->failing_site = result.reason;
        CacheStore(base, spec, build_id, *out);
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
    CacheStore(base, spec, build_id, *out);
    return true;
}

bool PrepareDartRuleTargets(void* handle, bool clear_button, bool folder_columns) {
    g_allow_scan = true;
    DartResolution result{};
    bool success = true;
    if (clear_button) success = ResolveDartSites(handle, dart::kRecentsClearButtonTarget, &result);
    if (folder_columns) success = ResolveDartSites(handle, dart::kFolderColumnsTarget, &result) && success;
    g_allow_scan = false;
    return success;
}
void InvalidateFailedDartRuleTargets() {
    pthread_mutex_lock(&g_cache_lock);
    for (auto& entry : g_cache) if (!entry.result.located) entry = CacheEntry{};
    pthread_mutex_unlock(&g_cache_lock);
}
void ResetDartRulePreparationAfterFork() {
    // Successful offsets remain valid only after the normal image/build-id check.
    pthread_mutex_t fresh = PTHREAD_MUTEX_INITIALIZER;
    g_cache_lock = fresh;
    g_allow_scan = false;
    for (auto& entry : g_cache) if (!entry.result.located) entry = CacheEntry{};
}
void LockDartRulePreparationForFork() { pthread_mutex_lock(&g_cache_lock); }
void UnlockDartRulePreparationAfterFork() { pthread_mutex_unlock(&g_cache_lock); }
}  // namespace hypertweak::native
