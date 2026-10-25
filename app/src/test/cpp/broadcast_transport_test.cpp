#include "broadcast_transport.h"
#include <cassert>
#include <cstring>
#include <set>
#include <string>
#include <vector>
using namespace hypertweak::native::broadcast;
namespace {
int allocations, fail_at, setters, intents, bundles, incs, decs;
bool intent_ok, bundle_ok, field_ok, arm_ok, consumed, ensure_ok;
uint8_t result_tag;
std::set<void*> live;
std::vector<std::string> events;
int intent_value, bundle_value, runtime_value;
void* Allocate(size_t n) { if (++allocations == fail_at) return nullptr; void* p = malloc(n); assert(p); live.insert(p); return p; }
void Release(void* p) { if (!p) return; assert(live.erase(p) == 1); free(p); }
void* Intent() { events.emplace_back("intent"); return intent_ok ? &intent_value : nullptr; }
void IntentDrop(void*) { intents++; events.emplace_back("intent-drop"); }
void Set(void*, ROptionRString* s) { assert(s->tag == 0); setters++; Release(s->value.data); }
void Extras(void*, void*) { events.emplace_back("extras"); }
NativeResult Send(void*, void*) { events.emplace_back("send"); NativeResult r{}; r.bytes[0] = result_tag; return r; }
void Inc(void*) { incs++; events.emplace_back("inc"); }
void Dec(void*) { decs++; events.emplace_back("dec"); }
void* Bundle() { return bundle_ok ? &bundle_value : nullptr; }
void BundleDrop(void*) { bundles++; events.emplace_back("bundle-drop"); }
bool Ensure() { return ensure_ok; }
bool Field(void*, const char* key, int32_t value) { assert(strcmp(key, "android:broadcast.flags") == 0 && value == 16); return field_ok; }
bool Arm(void*, uintptr_t* thread) { events.emplace_back("arm"); *thread = 42; return arm_ok; }
bool Disarm(uintptr_t thread) { assert(thread == 42); events.emplace_back("disarm"); return consumed; }
void Reset() {
    assert(live.empty()); allocations = fail_at = setters = intents = bundles = incs = decs = 0;
    intent_ok = bundle_ok = field_ok = arm_ok = consumed = ensure_ok = true; result_tag = 0; events.clear();
}
Context Make() {
    Api api{Intent, IntentDrop, Set, Set, Extras, Send, Inc, Dec, Bundle, BundleDrop, Ensure, Field, Arm, Disarm};
    return {api, true, 1, 1, &runtime_value, nullptr, &runtime_value, {Allocate, Release}};
}
void Check(Transport& transport, Context context, uint32_t state, const char* action = "action", void* extras = nullptr) {
    assert(transport.Send(context, action, extras, 2) == (state == 14));
    assert(transport.diagnostics.state == state);
    assert(transport.diagnostics.kind == 2);
    assert(incs == decs); assert(live.empty());
}
}
int main() {
    Transport transport;
    Reset(); auto c = Make(); c.valid = false; Check(transport, c, 2); assert(allocations == 0);
    Reset(); c = Make(); c.runtime_state = 0; c.captured_runtime = nullptr; Check(transport, c, 2);
    Reset(); c = Make(); ensure_ok = false; Check(transport, c, 1);
    Reset(); c = Make(); c.api.send = nullptr; Check(transport, c, 3);
    Reset(); intent_ok = false; Check(transport, Make(), 4);
    for (int failure : {1, 2}) { Reset(); fail_at = failure; Check(transport, Make(), 5); assert(setters == 0 && intents == 1); }
    Reset(); c = Make(); c.api.set_extras = nullptr; Check(transport, c, 6, "action", &bundle_value);
    Reset(); c = Make(); c.captured_runtime = nullptr; Check(transport, c, 7);
    Reset(); bundle_ok = false; Check(transport, Make(), 8);
    Reset(); field_ok = false; Check(transport, Make(), 9); assert(bundles == 1);
    Reset(); arm_ok = false; Check(transport, Make(), 10); assert(bundles == 1);
    Reset(); consumed = false; Check(transport, Make(), 12); assert(transport.diagnostics.options_consumed == 0);
    Reset(); result_tag = 1; Check(transport, Make(), 13);
    Reset(); result_tag = 2; Check(transport, Make(), 14); // Public Rust success depends on bit zero, not byte equality.
    assert(transport.diagnostics.result_tag == 2 && transport.diagnostics.options_consumed == 1);
    assert((events == std::vector<std::string>{"intent", "inc", "arm", "send", "disarm", "dec", "bundle-drop", "intent-drop"}));
    Reset(); Check(transport, Make(), 14, ""); assert(allocations == 2 && setters == 2);
    Reset(); Check(transport, Make(), 5, nullptr); assert(allocations == 0);
    Reset(); c = Make(); void* fallback = &runtime_value; c.captured_runtime = nullptr; c.runtime_slot = &fallback;
    Check(transport, c, 14, "action", &bundle_value);
}
