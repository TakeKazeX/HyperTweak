// SPDX-License-Identifier: Apache-2.0
#include "dart_resolution_cache.h"
#include <string.h>

namespace hypertweak::native {
namespace {
uint64_t Hash(const void* data, size_t size, uint64_t hash = 14695981039346656037ull) {
    const auto* bytes = static_cast<const uint8_t*>(data);
    for (size_t i = 0u; i < size; ++i) hash = (hash ^ bytes[i]) * 1099511628211ull;
    return hash;
}
uint64_t SpecHash(const dart::TargetSpec& spec) {
    uint64_t hash = Hash(spec.id, strlen(spec.id));
    for (size_t i = 0u; i < spec.site_count; ++i) {
        const auto& site = spec.sites[i];
        hash = Hash(site.name, strlen(site.name), hash);
        hash = Hash(&site.verify_delta, sizeof(site.verify_delta), hash);
        if (site.verify.bytes != nullptr) hash = Hash(site.verify.bytes, site.verify.size, hash);
        if (site.verify.mask != nullptr) hash = Hash(site.verify.mask, site.verify.size, hash);
    }
    return hash;
}
bool SafeSite(const dart::Image& image, const dart::SiteSpec& site, uintptr_t offset) {
    return offset != 0u && (offset & 3u) == 0u &&
           dart::Contains(image, offset, kCachedEvidenceSize, dart::kFlagRead | dart::kFlagExec) &&
           !dart::AddOverflows(offset, site.verify_delta) &&
           (site.verify.bytes == nullptr ||
            (dart::Contains(image, offset + site.verify_delta, site.verify.size,
                            dart::kFlagRead | dart::kFlagExec) &&
             dart::MatchPatternAtAddress(reinterpret_cast<uintptr_t>(image.base) +
                                         offset + site.verify_delta, site.verify)));
}
}
bool ValidDartCacheIdentity(const uint8_t* identity) {
    // _kDartSnapshotBuildId is a GNU note: 16-byte header, then a 16-byte ID.
    constexpr uint8_t header[16] = {
        4, 0, 0, 0, 16, 0, 0, 0, 3, 0, 0, 0, 'G', 'N', 'U', 0,
    };
    return identity != nullptr && memcmp(identity, header, sizeof(header)) == 0;
}
bool EncodeDartCache(const dart::Image& image, const dart::TargetSpec& spec,
                     const uint8_t* identity, uint32_t module_version,
                     const dart::TargetResult& result, DartCacheRecord* record) {
    if (record == nullptr || !ValidDartCacheIdentity(identity) || !result.resolved ||
        spec.site_count == 0u || spec.site_count > kCachedSiteCount ||
        result.site_count != spec.site_count) return false;
    *record = DartCacheRecord{};
    record->schema = kDartCacheSchema;
    record->module_version = module_version;
    record->spec_hash = SpecHash(spec);
    memcpy(record->identity, identity, kDartIdentitySize);
    record->image_span = image.image_span;
    record->site_count = spec.site_count;
    for (size_t i = 0u; i < spec.site_count; ++i) {
        const uintptr_t offset = result.sites[i].offset;
        if (!SafeSite(image, spec.sites[i], offset)) return false;
        record->offsets[i] = offset;
        memcpy(record->evidence[i], image.base + offset, kCachedEvidenceSize);
    }
    record->checksum = Hash(record, offsetof(DartCacheRecord, checksum));
    return true;
}
bool DecodeDartCache(const dart::Image& image, const dart::TargetSpec& spec,
                     const uint8_t* identity, uint32_t module_version,
                     const DartCacheRecord& record, dart::TargetResult* result) {
    if (!ValidDartCacheIdentity(identity) || result == nullptr || spec.site_count == 0u ||
        spec.site_count > kCachedSiteCount || record.schema != kDartCacheSchema ||
        record.module_version != module_version || record.spec_hash != SpecHash(spec) ||
        record.image_span != image.image_span || record.site_count != spec.site_count ||
        memcmp(record.identity, identity, kDartIdentitySize) != 0 ||
        record.checksum != Hash(&record, offsetof(DartCacheRecord, checksum))) return false;
    *result = dart::TargetResult{};
    for (size_t i = 0u; i < spec.site_count; ++i) {
        const uintptr_t offset = record.offsets[i];
        if (!SafeSite(image, spec.sites[i], offset) ||
            memcmp(record.evidence[i], image.base + offset, kCachedEvidenceSize) != 0) return false;
        result->sites[i] = {spec.sites[i].name, offset, 1u, dart::FindTier::kStructural,
                            true, true, true};
    }
    result->id = spec.id;
    result->resolved = true;
    result->verified = true;
    result->site_count = spec.site_count;
    result->reason = "persistent_cache";
    result->failure = "none";
    return true;
}
}
