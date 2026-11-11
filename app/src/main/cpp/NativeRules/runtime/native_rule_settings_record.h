// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stdint.h>
namespace hypertweak::native {
constexpr uint32_t kNativeRuleSettingsSchema = 10u;
constexpr uint32_t kNativePadDockOption = 1u << 22u;
struct NativeRuleSettingsRecord {
    uint32_t schema;
    uint32_t hidden;
    int32_t columns;
    // Contextual search, Android widgets, card haptics, stacked folder preview, widget stack, XiaoAI options.
    uint32_t options;
    uint32_t checksum;
};
static_assert(sizeof(NativeRuleSettingsRecord) == 20u);
// Native Dock glass geometry, packed as height | margin << 8 | bottom << 16 |
// radius << 24 in logical pixels. Every byte stays below 128, except that bit 7
// of the height byte selects the adaptive width (NativeDockGlassGeometry.adaptive).
constexpr uint32_t kNativeDockGlassSchema = 1u;
constexpr uint32_t kNativeDockGlassAdaptive = 0x80u;
constexpr uint32_t kNativeDockGlassDefault = 95u | (5u << 8u) | (5u << 16u) | (24u << 24u);  // NativeDockGlassGeometry.DEFAULT
struct NativeDockGlassRecord {
    uint32_t schema;
    uint32_t geometry;
    uint32_t checksum;
};
static_assert(sizeof(NativeDockGlassRecord) == 12u);
inline bool ValidNativeDockGlassGeometry(int64_t packed) {
    return packed >= 0 && packed <= 0x7f7f7fff && (static_cast<uint32_t>(packed) & 0x80808000u) == 0u;
}
inline uint32_t NativeDockGlassChecksum(const NativeDockGlassRecord& record) {
    return 0x48544447u ^ record.schema ^ (record.geometry * 0x9e3779b9u);
}
inline bool RestoreNativeDockGlass(const NativeDockGlassRecord& record) {
    return record.schema == kNativeDockGlassSchema && ValidNativeDockGlassGeometry(record.geometry) &&
           record.checksum == NativeDockGlassChecksum(record);
}
inline uint32_t NativeRuleSettingsChecksum(const NativeRuleSettingsRecord& record) {
    return 0x48545231u ^ record.schema ^ (record.hidden << 8u) ^
           (static_cast<uint32_t>(record.columns) << 16u) ^ (record.schema < 7u ? record.options << 24u : record.options * 0x9e3779b9u);
}
// Validate the old format before migrating. Missing extension bits stay disabled.
inline bool RestoreNativeRuleSettings(NativeRuleSettingsRecord* record, bool* migrated) {
    *migrated = false;
    if (record->schema < 1u || record->schema > kNativeRuleSettingsSchema ||
        record->hidden > 1u || record->columns < 3 || record->columns > 5 ||
        (record->schema < 7u ? record->options > ((1u << record->schema) - 1u) :
         ((((record->options >> 6u)&255u) != 0u && (((record->options >> 6u)&255u) < 51u || ((record->options >> 6u)&255u) > 201u)) ||
          (record->schema == 7u ? record->options >> 14u :
           (record->schema == 8u ? (record->options >> 14u)>12u :
            (((record->options >> 14u)&15u)>12u || ((record->options >> 18u)&7u)>4u || (record->options >> (record->schema >= 10u ? 23u : 22u)) != 0u))))) ||
        record->checksum != NativeRuleSettingsChecksum(*record)) return false;
    // Bit 21 belonged to the removed automatic night refresh feature.
    if (record->schema != kNativeRuleSettingsSchema || (record->options & (1u << 21u))) {
        record->options &= ~(1u << 21u);
        record->schema = kNativeRuleSettingsSchema;
        record->checksum = NativeRuleSettingsChecksum(*record);
        *migrated = true;
    }
    return true;
}
}
