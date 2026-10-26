#pragma once
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

namespace hypertweak::native::broadcast {
struct RString { char* data; uintptr_t length, capacity; const void* vtable; };
struct ROptionRString { uint8_t tag; uint8_t padding[7]; RString value; };
// Public Rust wrapper uses an x8 48-byte result; the private wrapper's 16 bytes are distinct.
struct NativeResult { uint8_t bytes[48]; };
static_assert(sizeof(ROptionRString) == 40u);
static_assert(sizeof(NativeResult) == 48u);
struct Memory { void* (*allocate)(size_t) = malloc; void (*release)(void*) = free; };
bool MakeString(const char*, RString*, const void* vtable, Memory memory = {});
bool MakeOptionString(const char*, ROptionRString*, const void* vtable, Memory memory = {});
struct Api {
    void* (*intent_default)(); void (*intent_drop)(void*);
    void (*set_action)(void*, ROptionRString*); void (*set_package)(void*, ROptionRString*);
    void (*set_extras)(void*, void*); NativeResult (*send)(void*, void*);
    void (*inc)(void*); void (*dec)(void*);
    void* (*bundle_default)(); void (*bundle_drop)(void*);
    bool (*ensure_options)(); bool (*add_i32)(void*, const char*, int32_t);
    bool (*arm)(void*, uintptr_t*); bool (*disarm)(uintptr_t);
};
struct Context {
    Api api;
    bool valid;
    uint32_t runtime_state, ready_value;
    void* captured_runtime;
    void** runtime_slot;
    const void* string_vtable;
    Memory memory{};
};
class Transport {
public:
    struct Diagnostics {
        uint32_t count = 0, kind = 0, state = 0, result_tag = 0, options_consumed = 0;
    } diagnostics;
    bool Send(const Context&, const char* action, void* extras, uint32_t kind);
};
} // namespace hypertweak::native::broadcast
