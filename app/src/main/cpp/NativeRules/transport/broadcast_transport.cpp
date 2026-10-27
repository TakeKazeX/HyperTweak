#include "broadcast_transport.h"
#include <string.h>
namespace hypertweak::native::broadcast {
namespace { template<class T> void Store(T* p, T v) { __atomic_store_n(p, v, __ATOMIC_RELEASE); } }
bool MakeString(const char* source, RString* output, const void* vtable, Memory memory) {
    if (!source || !output || !vtable) return false;
    const size_t length = strlen(source);
    auto* data = static_cast<char*>(memory.allocate(length == 0 ? 1 : length));
    if (!data) return false;
    if (length) memcpy(data, source, length);
    *output = {data, length, length, vtable};
    return true;
}
bool MakeOptionString(const char* source, ROptionRString* output, const void* vtable, Memory memory) {
    if (!output) return false;
    memset(output, 0, sizeof(*output));
    return MakeString(source, &output->value, vtable, memory);
}
bool Transport::Send(const Context& context, const char* action, void* extras, uint32_t kind) {
    struct ExtrasOwner {
        void* value;
        void (*drop)(void*);
        ~ExtrasOwner() { if(value && drop) drop(value); }
    } pending_extras{extras,context.api.bundle_drop};
    auto& d = diagnostics;
    __atomic_fetch_add(&d.count, uint32_t{1}, __ATOMIC_RELAXED);
    Store(&d.kind, kind); Store(&d.result_tag, uint32_t{0xffffffffu});
    Store(&d.options_consumed, uint32_t{0}); Store(&d.state, uint32_t{1});
    const auto& a = context.api;
    if (!context.valid || (!context.captured_runtime && context.runtime_state != context.ready_value)) {
        Store(&d.state, uint32_t{2}); return false;
    }
    if (!a.ensure_options()) return false;
    if (!a.intent_default || !a.intent_drop || !a.set_action || !a.set_package || !a.send ||
            !a.inc || !a.dec || !a.bundle_default || !a.bundle_drop) {
        Store(&d.state, uint32_t{3}); return false;
    }
    void* intent = a.intent_default();
    if (!intent) { Store(&d.state, uint32_t{4}); return false; }
    ROptionRString owned_action{}, owned_package{};
    if (!MakeOptionString(action, &owned_action, context.string_vtable, context.memory) ||
            !MakeOptionString("com.android.systemui", &owned_package, context.string_vtable, context.memory)) {
        context.memory.release(owned_action.value.data);
        context.memory.release(owned_package.value.data);
        Store(&d.state, uint32_t{5}); a.intent_drop(intent); return false;
    }
    a.set_action(intent, &owned_action); a.set_package(intent, &owned_package);
    if (extras) {
        if (!a.set_extras) { Store(&d.state, uint32_t{6}); a.intent_drop(intent); return false; }
        a.set_extras(intent, extras);
        pending_extras.value = nullptr; // Native setter moves the Bundle into the Intent.
    }
    void* runtime = context.captured_runtime;
    if (reinterpret_cast<uintptr_t>(runtime) < 0x100000000ull) runtime = nullptr;
    if (!runtime && context.runtime_slot) runtime = __atomic_load_n(context.runtime_slot, __ATOMIC_ACQUIRE);
    if (!runtime) { Store(&d.state, uint32_t{7}); a.intent_drop(intent); return false; }
    void* options = a.bundle_default();
    if (!options) { Store(&d.state, uint32_t{8}); a.intent_drop(intent); return false; }
    if (!a.add_i32(options, "android:broadcast.flags", 16)) {
        Store(&d.state, uint32_t{9}); a.bundle_drop(options); a.intent_drop(intent); return false;
    }
    a.inc(runtime);
    uintptr_t thread = 0;
    if (!a.arm(options, &thread)) {
        Store(&d.state, uint32_t{10}); a.dec(runtime); a.bundle_drop(options); a.intent_drop(intent); return false;
    }
    void* shared = runtime;
    const NativeResult result = a.send(&shared, intent);
    Store(&d.state, uint32_t{11}); Store(&d.result_tag, static_cast<uint32_t>(result.bytes[0]));
    const bool consumed = a.disarm(thread);
    Store(&d.options_consumed, consumed ? uint32_t{1} : uint32_t{0});
    a.dec(runtime); a.bundle_drop(options); a.intent_drop(intent);
    if (!consumed) { Store(&d.state, uint32_t{12}); return false; }
    if ((result.bytes[0] & uint8_t{1}) != 0u) { Store(&d.state, uint32_t{13}); return false; }
    Store(&d.state, uint32_t{14}); return true;
}
} // namespace hypertweak::native::broadcast
