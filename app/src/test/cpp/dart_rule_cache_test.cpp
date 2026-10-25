// SPDX-License-Identifier: Apache-2.0
// Host regression checks. Pass the verified launcher libapp.so as argv[1].
#include "dart_resolution_cache.h"
#include "dart_rule_support.h"
#include "clear_button_rule.h"
#include "folder_columns_rule.h"
#include "assistant_widget_rule.h"
#include "widget_stack_rule.h"
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
extern "C" void HyperTweakWidgetStackDragHook() {}
extern "C" void HyperTweakWidgetStackTargetHook() {}
extern "C" uintptr_t hypertweak_assistant_widget_reject;
extern "C" uintptr_t hypertweak_assistant_widget_accepted;
extern "C" uintptr_t hypertweak_assistant_widget_field;
extern "C" uint32_t hypertweak_assistant_widget_cid;
extern "C" uint32_t hypertweak_widget_stack_requested;
extern "C" uint32_t hypertweak_widget_stack_ready;
extern "C" uintptr_t hypertweak_widget_stack_field;
extern "C" uintptr_t hypertweak_widget_stack_drag_success;
extern "C" uintptr_t hypertweak_widget_stack_drag_reject;
extern "C" uintptr_t hypertweak_widget_stack_target_success;
extern "C" uintptr_t hypertweak_widget_stack_target_reject;
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
    // Capture the verified current-image widget contract before any patching.
    // Fixed fixture RVAs/CIDs made the lifecycle test reject newer launchers
    // even though the production structural resolver had succeeded.
    const auto widget_contract = dart::ResolveTarget(image, dart::kAssistantWidgetTarget);
    assert(widget_contract.resolved && widget_contract.verified);
    // Same reason: the paired widget-stack gates must be captured from the
    // unpatched image, because later assertions patch them.
    const auto stack_contract = dart::ResolveTarget(image, dart::kWidgetStackTarget);
    assert(stack_contract.resolved && stack_contract.verified && stack_contract.site_count == 3u);
    uintptr_t widget_offset = 0u, widget_type_offset = 0u;
    for (size_t i = 0; i < widget_contract.site_count; ++i) {
        const auto& site = widget_contract.sites[i];
        if (strcmp(site.name, "widget_eligibility") == 0) widget_offset = site.offset;
        if (strcmp(site.name, "app_widget_type") == 0) widget_type_offset = site.offset;
    }
    assert(widget_offset != 0u && widget_type_offset != 0u);
    uint32_t widget_field_read = 0u, widget_branch = 0u, widget_type_test = 0u;
    memcpy(&widget_field_read, snapshot.data() + widget_offset, 4u);
    memcpy(&widget_branch, snapshot.data() + widget_offset + 8u, 4u);
    memcpy(&widget_type_test, snapshot.data() + widget_type_offset, 4u);
    int32_t widget_reject_delta = static_cast<int32_t>((widget_branch >> 5u) & 0x3fffu);
    if ((widget_reject_delta & 0x2000) != 0) widget_reject_delta -= 0x4000;
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
    assert(widget == reinterpret_cast<uintptr_t>(snapshot.data()) + widget_offset);
    assert(hypertweak_assistant_widget_reject == widget + 8u + static_cast<intptr_t>(widget_reject_delta) * 4);
    assert(hypertweak_assistant_widget_accepted == widget + 28);
    assert(hypertweak_assistant_widget_field == ((widget_field_read >> 12u) & 0x1ffu));
    assert(hypertweak_assistant_widget_cid == ((widget_type_test >> 10u) & 0xfffu));
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

    // Android widget stack: prepared targets only, paired gates, disable is passive.
    SetAndroidWidgetStackEnabled(true);
    assert(!ApplyAndroidWidgetStackRule(reinterpret_cast<void*>(1))); // no input scan
    assert(strcmp(AndroidWidgetStackRuleReason(), "dart_preparation_pending") == 0);
    assert(PrepareDartRuleTargets(reinterpret_cast<void*>(1), false, false, false, false, false, false, true));
    auto* drag_gate = snapshot.data() + stack_contract.sites[0].offset;
    auto* target_gate = snapshot.data() + stack_contract.sites[1].offset;
    uint32_t drag_word = 0u, target_word = 0u, drag_branch = 0u, target_branch = 0u;
    memcpy(&drag_word, drag_gate, 4u);
    memcpy(&target_word, target_gate, 4u);
    memcpy(&drag_branch, drag_gate - 4, 4u);
    memcpy(&target_branch, target_gate - 4, 4u);
    const unsigned stack_before = installs;
    assert(ApplyAndroidWidgetStackRule(reinterpret_cast<void*>(1)));
    assert(installs == stack_before + 2u && hooks.size() == 3u);
    assert(hypertweak_widget_stack_field == ((drag_word >> 12u) & 0x1ffu));
    assert(hypertweak_widget_stack_field == ((target_word >> 12u) & 0x1ffu));
    // The shim replaces the conditional branch itself, so success resumes at
    // that branch's target (the b.ne and the tbz agree on it); rejection resumes
    // one instruction past the four replayed words.
    uintptr_t drag_target = 0u, target_target = 0u;
    assert(dart::DecodeTestBranchTarget(
        static_cast<uintptr_t>(drag_gate - snapshot.data()) - 4u, drag_branch, &drag_target));
    assert(dart::DecodeTestBranchTarget(
        static_cast<uintptr_t>(target_gate - snapshot.data()) - 4u, target_branch, &target_target));
    assert(hypertweak_widget_stack_drag_success ==
           reinterpret_cast<uintptr_t>(snapshot.data()) + drag_target);
    assert(hypertweak_widget_stack_target_success ==
           reinterpret_cast<uintptr_t>(snapshot.data()) + target_target);
    assert(hypertweak_widget_stack_drag_reject == reinterpret_cast<uintptr_t>(drag_gate) + 16u);
    assert(hypertweak_widget_stack_target_reject == reinterpret_cast<uintptr_t>(target_gate) + 16u);
    const unsigned stack_installed = installs;
    // Repeated application is idempotent while the owned patch is intact.
    assert(ApplyAndroidWidgetStackRule(reinterpret_cast<void*>(1)) && installs == stack_installed);
    // A remap restores stock bytes; the rule repairs its own two sites only.
    for (const auto& hook : hooks) memcpy(hook.first, hook.second.data(), 16u);
    assert(ApplyAndroidWidgetStackRule(reinterpret_cast<void*>(1)) && installs == stack_installed + 2u);
    // Another owner's patch at either gate must never inherit our continuations.
    target_gate[0] ^= 1u;
    assert(!ApplyAndroidWidgetStackRule(reinterpret_cast<void*>(1)));
    assert(strcmp(AndroidWidgetStackRuleReason(), "foreign_patch_detected") == 0);
    target_gate[0] ^= 1u;
    assert(ApplyAndroidWidgetStackRule(reinterpret_cast<void*>(1)));
    // Disable keeps the passive shims: they read the live flag and run stock.
    SetAndroidWidgetStackEnabled(false);
    assert(ApplyAndroidWidgetStackRule(reinterpret_cast<void*>(1)));
    assert(strcmp(AndroidWidgetStackRuleReason(), "disabled") == 0);
    assert(hypertweak_widget_stack_requested == 0u);
    assert(hypertweak_widget_stack_ready == 0u);
    // A failed family leaves an empty cache slot on a new settings generation.
    // Retrying it must reuse that slot, not evict another family's live patched
    // target and subsequently attempt to rediscover that target from patched code.
    snapshot.assign(std::istreambuf_iterator<char>(std::ifstream(argv[1], std::ios::binary).rdbuf()), {});
    identity[16] ^= 2u; // independent image identity, without relying on cache internals
    records.clear();
    memset(snapshot.data() + widget_offset, 0, 16u); // deliberate unavailable family
    assert(!PrepareDartRuleTargets(reinterpret_cast<void*>(1), true, true, true, true, true, true, true, true));
    DartResolution clear_before{};
    assert(ResolveDartSites(reinterpret_cast<void*>(1), dart::kRecentsClearButtonTarget, &clear_before));
    const uintptr_t clear_target = clear_before.Find("insert_overlay");
    assert(clear_target != 0u);
    memset(reinterpret_cast<void*>(clear_target), 0xa5, 16u); // installed/foreign patch: never rescan or overwrite it
    InvalidateFailedDartRuleTargets();
    assert(!PrepareDartRuleTargets(reinterpret_cast<void*>(1), true, true, true, true, true, true, true, true));
    DartResolution clear_after{};
    assert(ResolveDartSites(reinterpret_cast<void*>(1), dart::kRecentsClearButtonTarget, &clear_after));
    assert(clear_after.Find("insert_overlay") == clear_target);
    assert(*reinterpret_cast<const uint8_t*>(clear_target) == 0xa5);
    puts("PASS: cache identity/corruption/bounds/relocation, readiness, input deferral, idempotent apply, remap repair, and foreign-hook preservation");
}
