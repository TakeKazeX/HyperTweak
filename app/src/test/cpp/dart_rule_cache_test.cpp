// SPDX-License-Identifier: Apache-2.0
// Host regression checks. Pass the verified launcher libapp.so as argv[1].
#include "dart_resolution_cache.h"
#include "dart_rule_support.h"
#include "clear_button_rule.h"
#include "folder_columns_rule.h"
#include "assistant_widget_rule.h"
#include <assert.h>
#include <fstream>
#include <iterator>
#include <map>
#include <string>
#include <vector>
#include <stdio.h>
#include <string.h>

using namespace hypertweak::native;
namespace {
std::vector<uint8_t> snapshot;
uint8_t identity[kDartIdentitySize]{};
bool image_ready = true;
std::map<std::string, std::vector<uint8_t>> records;
std::map<void*, std::vector<uint8_t>> hooks;
unsigned installs = 0u;
}
bool MiuiHomeHyosResolveDartImage(void*, uint8_t** base, const uint8_t** build) {
    if (!image_ready) return false;
    *base = snapshot.data();
    *build = identity;
    return true;
}
void* MiuiHomeHyosCurrentDartHandle() { return reinterpret_cast<void*>(1); }
bool MiuiHomeHyosDartRangeHasFlags(const uint8_t* base, uintptr_t offset, size_t size, uint32_t flags) {
    dart::Image image{};
    return base == snapshot.data() && dart::ParseImage(base, &image) &&
           dart::Contains(image, offset, size, flags);
}
int InstallInlineHook(void* target, void*, void** original) {
    auto* bytes = static_cast<uint8_t*>(target);
    assert(hooks.count(target) == 0u);
    hooks[target] = std::vector<uint8_t>(bytes, bytes + 16u);
    memset(bytes, 0xa5, 16u);
    *original = bytes + 16u;
    ++installs;
    return 0;
}
int RemoveInlineHook(void* target) {
    const auto hook = hooks.find(target);
    if (hook == hooks.end()) return 1;
    memcpy(target, hook->second.data(), 16u);
    hooks.erase(hook);
    return 0;
}
extern "C" void HyperTweakRecentsClearButtonInsertHook() {}
extern "C" void HyperTweakFolderColumnsHook() {}
extern "C" void HyperTweakFolderPreviewIconColumnsHook() {}
extern "C" void HyperTweakFolderPreviewItemsMaxCountHook() {}
extern "C" void HyperTweakFolderPreviewSetItemsHook() {}
extern "C" void HyperTweakFolderOpenHook() {}
extern "C" void HyperTweakAssistantWidgetGateHook() {}
extern "C" uintptr_t hypertweak_assistant_widget_reject;
extern "C" uintptr_t hypertweak_assistant_widget_accepted;
extern "C" uintptr_t hypertweak_assistant_widget_field;
extern "C" uint32_t hypertweak_assistant_widget_cid;
namespace hypertweak::native {
void* NativeRuleCurrentDartHandle() { return reinterpret_cast<void*>(1); }
void LogInfo(const char*, ...) {}
void LogWarn(const char*, ...) {}
void ProbeConfigChannel() {}
bool ReadConfigFlag(const char*, bool fallback) { return fallback; }
int32_t ReadConfigInt(const char*, int32_t fallback) { return fallback; }
bool ReadNativeRecord(const char* name, void* data, size_t size) {
    const auto record = records.find(name);
    if (record == records.end() || record->second.size() != size) return false;
    memcpy(data, record->second.data(), size);
    return true;
}
bool WriteNativeRecord(const char* name, const void* data, size_t size) {
    const auto* bytes = static_cast<const uint8_t*>(data);
    records[name] = std::vector<uint8_t>(bytes, bytes + size);
    return true;
}
void DeleteNativeRecord(const char* name) { records.erase(name); }
}
int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream input(argv[1], std::ios::binary);
    assert(input.good());
    snapshot.assign(std::istreambuf_iterator<char>(input), {});
    for (size_t i = 0u; i < sizeof(identity); ++i) identity[i] = static_cast<uint8_t>(i);
    constexpr uint8_t note_header[16] = {4, 0, 0, 0, 16, 0, 0, 0, 3, 0, 0, 0, 'G', 'N', 'U', 0};
    memcpy(identity, note_header, sizeof(note_header));
    dart::Image image{};
    assert(dart::ParseImage(snapshot.data(), &image));
    const auto& spec = dart::kFolderColumnsTarget;
    const auto found = dart::ResolveTarget(image, spec);
    if (!found.resolved || !found.verified) {
        for (size_t i = 0; i < found.site_count; ++i) {
            fprintf(stderr, "%s found=%d verified=%d candidates=%u offset=0x%zx\n",
                    found.sites[i].name, found.sites[i].found, found.sites[i].verified,
                    found.sites[i].candidates, static_cast<size_t>(found.sites[i].offset));
        }
    }
    assert(found.resolved && found.verified);
    DartCacheRecord record{};
    assert(EncodeDartCache(image, spec, identity, HYPERTWEAK_NATIVE_VERSION, found, &record));
    dart::TargetResult restored{};
    assert(DecodeDartCache(image, spec, identity, HYPERTWEAK_NATIVE_VERSION, record, &restored));
    assert(restored.site_count == found.site_count);
    for (size_t i = 0u; i < found.site_count; ++i) assert(restored.sites[i].offset == found.sites[i].offset);
    assert(!DecodeDartCache(image, spec, identity, HYPERTWEAK_NATIVE_VERSION + 1u, record, &restored));
    identity[16] ^= 1u;
    assert(!DecodeDartCache(image, spec, identity, HYPERTWEAK_NATIVE_VERSION, record, &restored));
    identity[16] ^= 1u;
    auto corrupt = record;
    corrupt.offsets[0] ^= 4u;
    assert(!DecodeDartCache(image, spec, identity, HYPERTWEAK_NATIVE_VERSION, corrupt, &restored));
    assert(!DecodeDartCache(image, dart::kRecentsClearButtonTarget, identity,
                            HYPERTWEAK_NATIVE_VERSION, record, &restored));
    snapshot[found.sites[0].offset] ^= 1u;
    assert(!DecodeDartCache(image, spec, identity, HYPERTWEAK_NATIVE_VERSION, record, &restored));
    snapshot[found.sites[0].offset] ^= 1u;
    auto invalid = found;
    invalid.sites[0].offset = image.image_span;
    assert(!EncodeDartCache(image, spec, identity, HYPERTWEAK_NATIVE_VERSION, invalid, &corrupt));
    invalid = found;
    invalid.resolved = false;
    assert(!EncodeDartCache(image, spec, identity, HYPERTWEAK_NATIVE_VERSION, invalid, &corrupt));

    // Input must not perform discovery; settling readiness is retried later.
    DartResolution resolution{};
    image_ready = false;
    assert(!ResolveDartSites(reinterpret_cast<void*>(1), spec, &resolution));
    image_ready = true;
    assert(!ResolveDartSites(reinterpret_cast<void*>(1), spec, &resolution));
    assert(strcmp(resolution.reason, "dart_preparation_pending") == 0);
    PrepareDartRuleTargets(reinterpret_cast<void*>(1), true, true);
    assert(ResolveDartSites(reinterpret_cast<void*>(1), spec, &resolution));
    assert(records.size() == 2u);

    // A fresh mapping has a new absolute base but restores the same relative targets.
    std::vector<uint8_t> relocated = snapshot;
    snapshot.swap(relocated);
    ResetDartRulePreparationAfterFork();
    assert(!ResolveDartSites(reinterpret_cast<void*>(1), spec, &resolution));
    PrepareDartRuleTargets(reinterpret_cast<void*>(1), true, true);
    assert(ResolveDartSites(reinterpret_cast<void*>(1), spec, &resolution));
    assert(resolution.Find("grid_return") == reinterpret_cast<uintptr_t>(snapshot.data()) + found.sites[0].offset);

    // Version/record damage is discarded and replaced by a successful fresh resolve.
    records["folder_columns.cache"][0] ^= 1u;
    identity[17] ^= 1u;
    PrepareDartRuleTargets(reinterpret_cast<void*>(1), true, true);
    assert(ResolveDartSites(reinterpret_cast<void*>(1), spec, &resolution));
    DartCacheRecord rewritten{};
    assert(ReadNativeRecord("folder_columns.cache", &rewritten, sizeof(rewritten)));
    assert(dart::ParseImage(snapshot.data(), &image));
    assert(DecodeDartCache(image, spec, identity, HYPERTWEAK_NATIVE_VERSION, rewritten, &restored));
    // A transient incomplete target is not persisted as an unsupported version.
    snapshot[found.sites[0].offset] ^= 1u;
    identity[18] ^= 1u;
    PrepareDartRuleTargets(reinterpret_cast<void*>(1), false, true);
    assert(!ResolveDartSites(reinterpret_cast<void*>(1), spec, &resolution));
    assert(records.count("folder_columns.cache") == 0u);
    snapshot[found.sites[0].offset] ^= 1u;
    InvalidateFailedDartRuleTargets();
    PrepareDartRuleTargets(reinterpret_cast<void*>(1), true, true);
    assert(ResolveDartSites(reinterpret_cast<void*>(1), spec, &resolution));
    assert(records.count("folder_columns.cache") == 1u);

    // Real feature rules consume prepared results; repeated inputs are idempotent.
    SetClearButtonHidden(true);
    SetFolderColumns(4);
    assert(ApplyClearButtonRule(reinterpret_cast<void*>(1)));
    assert(ApplyFolderColumnsRule(reinterpret_cast<void*>(1)));
    assert(hooks.size() == 6u && installs == 6u);
    assert(ApplyClearButtonRule(reinterpret_cast<void*>(1)));
    assert(ApplyFolderColumnsRule(reinterpret_cast<void*>(1)));
    assert(installs == 6u);
    SetFolderColumns(5);
    assert(ApplyFolderColumnsRule(reinterpret_cast<void*>(1)));
    assert(installs == 6u);

    // A remap restores original bytes while hook handles still claim installation.
    for (const auto& hook : hooks) memcpy(hook.first, hook.second.data(), 16u);
    assert(ApplyClearButtonRule(reinterpret_cast<void*>(1)));
    assert(ApplyFolderColumnsRule(reinterpret_cast<void*>(1)));
    assert(installs == 12u && hooks.size() == 6u);

    const uintptr_t grid = FolderColumnsTargetAddress();
    auto* bytes = reinterpret_cast<uint8_t*>(grid);
    bytes[0] ^= 1u;
    assert(!ApplyFolderColumnsRule(reinterpret_cast<void*>(1)));
    assert(strcmp(FolderColumnsRuleReason(), "foreign_patch_detected") == 0);
    SetFolderColumns(3);
    assert(!ApplyFolderColumnsRule(reinterpret_cast<void*>(1)));
    assert(installs == 12u && hooks.size() == 6u);
    bytes[0] ^= 1u;
    assert(ApplyFolderColumnsRule(reinterpret_cast<void*>(1)));
    SetClearButtonHidden(false);
    assert(ApplyClearButtonRule(reinterpret_cast<void*>(1)));
    // Only the passive on-open boundary remains to restore the native static
    // after disable; the feature's grid/preview hooks have all been removed.
    assert(hooks.size() == 1u);
    SetAssistantWidgetAllowed(true);
    assert(!ApplyAssistantWidgetRule(reinterpret_cast<void*>(1))); // no input scan
    assert(PrepareDartRuleTargets(reinterpret_cast<void*>(1), false, false, true));
    assert(ApplyAssistantWidgetRule(reinterpret_cast<void*>(1)));
    const uintptr_t widget = AssistantWidgetTargetAddress();
    assert(widget == reinterpret_cast<uintptr_t>(snapshot.data()) + 0xc9bd18);
    assert(hypertweak_assistant_widget_reject == reinterpret_cast<uintptr_t>(snapshot.data()) + 0xc9bdc0);
    assert(hypertweak_assistant_widget_accepted == widget + 28);
    assert(hypertweak_assistant_widget_field == 0xfb && hypertweak_assistant_widget_cid == 0x7f1);
    const unsigned before = installs;
    assert(ApplyAssistantWidgetRule(reinterpret_cast<void*>(1)) && installs == before);
    auto* widget_bytes = reinterpret_cast<uint8_t*>(widget);
    widget_bytes[0] ^= 1u;
    SetAssistantWidgetAllowed(false);
    assert(!ApplyAssistantWidgetRule(reinterpret_cast<void*>(1))); // another owner preserved
    widget_bytes[0] ^= 1u;
    assert(ApplyAssistantWidgetRule(reinterpret_cast<void*>(1)) && hooks.size() == 1u);
    SetAssistantWidgetAllowed(true);
    assert(ApplyAssistantWidgetRule(reinterpret_cast<void*>(1)));
    memcpy(widget_bytes, hooks.at(reinterpret_cast<void*>(widget)).data(), 16u);
    assert(ApplyAssistantWidgetRule(reinterpret_cast<void*>(1)) && installs == before + 2u);
    SetAssistantWidgetAllowed(false);
    assert(ApplyAssistantWidgetRule(reinterpret_cast<void*>(1)) && hooks.size() == 1u);
    puts("PASS: cache identity/corruption/bounds/relocation, readiness, input deferral, idempotent apply, remap repair, and foreign-hook preservation");
}
