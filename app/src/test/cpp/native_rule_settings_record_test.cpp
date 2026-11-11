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
        assert(record.schema == kNativeRuleSettingsSchema && migrated);
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
            case 0: broken.schema = kNativeRuleSettingsSchema + 1; break;
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
    for (unsigned percent : {50u,100u,150u,175u,200u}) {
        NativeRuleSettingsRecord record{7,0,3,63u | ((percent+1u)<<6u),0};
        record.checksum=NativeRuleSettingsChecksum(record);
        assert(RestoreNativeRuleSettings(&record,&migrated) && migrated && record.schema==kNativeRuleSettingsSchema);
        auto corrupt=record; corrupt.options ^= 1u<<12u;
        assert(!RestoreNativeRuleSettings(&corrupt,&migrated));
    }
    for (unsigned percent : {49u,201u,254u}) {
        NativeRuleSettingsRecord record{9,0,3,(percent+1u)<<6u,0};
        record.checksum=NativeRuleSettingsChecksum(record);
        assert(!RestoreNativeRuleSettings(&record,&migrated));
    }
    for(unsigned grid=0;grid<=12;++grid){
        NativeRuleSettingsRecord record{8,0,3,63u|(101u<<6u)|(grid<<14u),0};record.checksum=NativeRuleSettingsChecksum(record);
        assert(RestoreNativeRuleSettings(&record,&migrated)&&migrated);
        record.options|=1u<<31u;record.checksum=NativeRuleSettingsChecksum(record);assert(!RestoreNativeRuleSettings(&record,&migrated));
    }
    for(unsigned mode=0;mode<=4;++mode) for(unsigned refresh=0;refresh<=1;++refresh) {
        NativeRuleSettingsRecord r{9,1,5,63u|(101u<<6u)|(12u<<14u)|(mode<<18u)|(refresh<<21u),0};
        r.checksum=NativeRuleSettingsChecksum(r);
        assert(RestoreNativeRuleSettings(&r, &migrated) && migrated);
        assert(!(r.options & ((1u << 21u) | kNativePadDockOption)));
        auto bad=r;bad.options=(bad.options&~(7u<<18u))|(5u<<18u);bad.checksum=NativeRuleSettingsChecksum(bad);
        assert(!RestoreNativeRuleSettings(&bad,&migrated));
    }
    NativeRuleSettingsRecord native_dock{9, 0, 3, kNativePadDockOption, 0};
    native_dock.checksum = NativeRuleSettingsChecksum(native_dock);
    assert(!RestoreNativeRuleSettings(&native_dock, &migrated));
    native_dock.schema = kNativeRuleSettingsSchema;
    native_dock.checksum = NativeRuleSettingsChecksum(native_dock);
    assert(RestoreNativeRuleSettings(&native_dock, &migrated) && !migrated);
    assert(native_dock.options == kNativePadDockOption);
    // Dock glass geometry: every byte below 128, and the checksum covers it.
    const uint32_t geometry = 72u | (16u << 8u) | (8u << 16u) | (24u << 24u);
    NativeDockGlassRecord glass{kNativeDockGlassSchema, geometry, 0u};
    glass.checksum = NativeDockGlassChecksum(glass);
    assert(RestoreNativeDockGlass(glass) && ValidNativeDockGlassGeometry(kNativeDockGlassDefault));
    auto tampered = glass; tampered.geometry ^= 1u; assert(!RestoreNativeDockGlass(tampered));
    auto future = glass; future.schema = kNativeDockGlassSchema + 1u;
    future.checksum = NativeDockGlassChecksum(future); assert(!RestoreNativeDockGlass(future));
    for (uint32_t bit : {15u, 23u, 31u}) {
        auto wide = glass; wide.geometry |= 1u << bit; wide.checksum = NativeDockGlassChecksum(wide);
        assert(!RestoreNativeDockGlass(wide) && !ValidNativeDockGlassGeometry(geometry | (1u << bit)));
    }
    // Bit 7 of the height byte is the adaptive width flag.
    auto adaptive = glass; adaptive.geometry |= kNativeDockGlassAdaptive;
    adaptive.checksum = NativeDockGlassChecksum(adaptive);
    assert(kNativeDockGlassAdaptive == 0x80u && RestoreNativeDockGlass(adaptive));
    auto stale = adaptive; stale.checksum = glass.checksum; assert(!RestoreNativeDockGlass(stale));
    assert(!ValidNativeDockGlassGeometry(-1) && ValidNativeDockGlassGeometry(0x7f7f7fff) &&
           !ValidNativeDockGlassGeometry(0x7f7f807f));
    puts("native snapshots: schemas 1-9 migrate, native Pad Dock persists in schema 10, Dock glass geometry record with adaptive flag");
}
