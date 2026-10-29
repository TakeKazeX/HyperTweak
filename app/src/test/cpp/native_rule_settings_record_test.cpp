// SPDX-License-Identifier: Apache-2.0
#include "native_rule_settings_record.h"
#include <assert.h>
#include <stdio.h>
#include <initializer_list>
using namespace hypertweak::native;
int main() {
    for (uint32_t schema = 1; schema <= 6; ++schema) {
        NativeRuleSettingsRecord record{schema, 1, 5, (1u << schema) - 1u, 0};
        record.checksum = NativeRuleSettingsChecksum(record);
        bool migrated = false;
        assert(RestoreNativeRuleSettings(&record, &migrated));
        assert(record.schema == 8 && migrated);
        assert(record.hidden == 1 && record.columns == 5);
        assert((record.options & 8u) == (schema >= 4 ? 8u : 0u));
        assert((record.options & 16u) == (schema >= 5 ? 16u : 0u));
        assert((record.options & 32u) == (schema == 6 ? 32u : 0u));
        assert(record.checksum == NativeRuleSettingsChecksum(record));
        assert(RestoreNativeRuleSettings(&record, &migrated) && !migrated);
    }
    NativeRuleSettingsRecord good{6, 0, 3, 0, 0};
    good.checksum = NativeRuleSettingsChecksum(good);
    for (unsigned field = 0; field < 5; ++field) {
        auto broken = good;
        switch (field) {
            case 0: broken.schema = 9; break;
            case 1: broken.hidden = 2; break;
            case 2: broken.columns = 6; break;
            case 3: broken.options = 64; break;
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
    for (unsigned percent : {50u,100u,150u}) {
        NativeRuleSettingsRecord record{7,0,3,63u | ((percent+1u)<<6u),0};
        record.checksum=NativeRuleSettingsChecksum(record);
        assert(RestoreNativeRuleSettings(&record,&migrated) && migrated && record.schema==8);
        auto corrupt=record; corrupt.options ^= 1u<<12u;
        assert(!RestoreNativeRuleSettings(&corrupt,&migrated));
    }
    for(unsigned grid=0;grid<=12;++grid){
        NativeRuleSettingsRecord record{8,0,3,63u|(101u<<6u)|(grid<<14u),0};record.checksum=NativeRuleSettingsChecksum(record);
        assert(RestoreNativeRuleSettings(&record,&migrated)&&!migrated);
        record.options|=1u<<31u;record.checksum=NativeRuleSettingsChecksum(record);assert(!RestoreNativeRuleSettings(&record,&migrated));
    }
    puts("native snapshots: schemas 1-6 migrate with icon size disabled, schema 7 roundtrip, invalid fields/checksum rejected");
}
