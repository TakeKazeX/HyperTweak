// SPDX-License-Identifier: Apache-2.0
#include "native_rule_settings_record.h"
#include <assert.h>
#include <stdio.h>
using namespace hypertweak::native;
int main() {
    for (uint32_t schema = 1; schema <= 4; ++schema) {
        NativeRuleSettingsRecord record{schema, 1, 5, (1u << schema) - 1u, 0};
        record.checksum = NativeRuleSettingsChecksum(record);
        bool migrated = false;
        assert(RestoreNativeRuleSettings(&record, &migrated));
        assert(record.schema == 4 && migrated == (schema != 4));
        assert(record.hidden == 1 && record.columns == 5);
        assert((record.options & 8u) == (schema == 4 ? 8u : 0u));
        assert(record.checksum == NativeRuleSettingsChecksum(record));
        assert(RestoreNativeRuleSettings(&record, &migrated) && !migrated);
    }
    NativeRuleSettingsRecord good{4, 0, 3, 0, 0};
    good.checksum = NativeRuleSettingsChecksum(good);
    for (unsigned field = 0; field < 5; ++field) {
        auto broken = good;
        switch (field) {
            case 0: broken.schema = 5; break;
            case 1: broken.hidden = 2; break;
            case 2: broken.columns = 6; break;
            case 3: broken.options = 16; break;
            default: broken.checksum ^= 1u; break;
        }
        if (field != 4) broken.checksum = NativeRuleSettingsChecksum(broken);
        bool migrated = false;
        assert(!RestoreNativeRuleSettings(&broken, &migrated));
    }
    NativeRuleSettingsRecord oldWithNewBit{3, 0, 3, 8, 0};
    oldWithNewBit.checksum = NativeRuleSettingsChecksum(oldWithNewBit);
    bool migrated = false;
    assert(!RestoreNativeRuleSettings(&oldWithNewBit, &migrated));
    puts("native snapshots: schemas 1/2/3 migrate disabled, schema 4 roundtrip, invalid fields/checksum rejected");
}
