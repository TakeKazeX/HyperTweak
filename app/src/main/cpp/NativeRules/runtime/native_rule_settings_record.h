// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stdint.h>
namespace hypertweak::native {
constexpr uint32_t kNativeRuleSettingsSchema = 9u;
struct NativeRuleSettingsRecord {
    uint32_t schema;
    uint32_t hidden;
    int32_t columns;
    // Contextual search, Android widgets, card haptics, stacked folder preview, widget stack, XiaoAI options.
    uint32_t options;
    uint32_t checksum;
};
static_assert(sizeof(NativeRuleSettingsRecord) == 20u);
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
         ((((record->options >> 6u)&255u) != 0u && (((record->options >> 6u)&255u) < 51u || ((record->options >> 6u)&255u) > 151u)) ||
          (record->schema == 7u ? record->options >> 14u :
           (record->schema == 8u ? (record->options >> 14u)>12u :
            (((record->options >> 14u)&15u)>12u || ((record->options >> 18u)&7u)>4u || (record->options >> 22u)!=0u))))) ||
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
