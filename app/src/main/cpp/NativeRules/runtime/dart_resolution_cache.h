// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "dart_targets.h"

namespace hypertweak::native {
// Bump when the structural contracts or record format change.
constexpr uint32_t kDartCacheSchema = 1u;
constexpr size_t kDartIdentitySize = 32u;
constexpr size_t kCachedSiteCount = 8u;
constexpr size_t kCachedEvidenceSize = 32u;
struct DartCacheRecord {
    uint32_t schema;
    uint32_t module_version;
    uint64_t spec_hash;
    uint8_t identity[kDartIdentitySize];
    uint64_t image_span;
    uint64_t site_count;
    uint64_t offsets[kCachedSiteCount];
    uint8_t evidence[kCachedSiteCount][kCachedEvidenceSize];
    uint64_t checksum;
};
bool ValidDartCacheIdentity(const uint8_t* identity);
bool EncodeDartCache(const dart::Image& image, const dart::TargetSpec& spec,
                     const uint8_t* identity, uint32_t module_version,
                     const dart::TargetResult& result, DartCacheRecord* record);
bool DecodeDartCache(const dart::Image& image, const dart::TargetSpec& spec,
                     const uint8_t* identity, uint32_t module_version,
                     const DartCacheRecord& record, dart::TargetResult* result);
}
