// SPDX-License-Identifier: Apache-2.0
// Execute the actual Dart shims on Bionic with synthetic thread/static storage.
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
extern "C" {
uint64_t hypertweak_folder_columns_smi = 8;
uint32_t hypertweak_folder_columns_hits = 0;
uintptr_t hypertweak_folder_preview_set_items_continuation = 0;
uintptr_t hypertweak_folder_open_continuation = 0;
uint64_t hypertweak_folder_open_frame_size = 64;
uint64_t hypertweak_folder_columns_field_offset = 0x100;
uint64_t hypertweak_folder_native_columns_smi = 0;
uint32_t hypertweak_folder_columns_enabled = 1;
uint32_t hypertweak_folder_cache_sync_hits = 0;
uint64_t hypertweak_test_receiver = 0, hypertweak_test_folder = 0, hypertweak_test_flags = 0;
uintptr_t TestFolderOpen(void* thread, uintptr_t receiver, uintptr_t folder, void* stack);
uint64_t TestFolderGetter(uint64_t native_offset, void* stack);
void TestFolderContinuation();
}
int main() {
    alignas(16) uint8_t thread[0x80]{};
    alignas(16) uint64_t table[128]{};
    alignas(16) uint8_t stack[256]{};
    void* table_pointer = table;
    memcpy(thread + 0x78, &table_pointer, sizeof(table_pointer));
    hypertweak_folder_open_continuation = reinterpret_cast<uintptr_t>(&TestFolderContinuation);
    auto open = [&] {
        assert(TestFolderOpen(thread, 0x1111, 0x2222, stack + sizeof(stack)) == 0x2222);
        assert(hypertweak_test_receiver == 0x1111 && hypertweak_test_folder == 0x2222);
        assert(hypertweak_test_flags == 0x60000000); // original entry's NZCV preserved
    };
    table[0x100 / 8] = 6;
    open(); assert(table[32] == 8 && hypertweak_folder_native_columns_smi == 6);
    open(); assert(table[32] == 8 && hypertweak_folder_native_columns_smi == 6);
    hypertweak_folder_columns_smi = 10;
    open(); assert(table[32] == 10 && hypertweak_folder_native_columns_smi == 6);
    hypertweak_folder_columns_enabled = 0;
    open(); assert(table[32] == 6);
    // The getter captures the host's computed result before applying override.
    hypertweak_folder_columns_smi = 10;
    assert(TestFolderGetter(2, stack + sizeof(stack)) == 10);
    assert(hypertweak_folder_native_columns_smi == 8);
    table[32] = 10;
    open(); assert(table[32] == 8); // preserve a native four-column default
    hypertweak_folder_native_columns_smi = 0;
    hypertweak_folder_columns_enabled = 1;
    const auto hits = hypertweak_folder_cache_sync_hits;
    table[32] = 0x12345; open();
    assert(table[32] == 0x12345 && hypertweak_folder_cache_sync_hits == hits);
    assert(hypertweak_folder_native_columns_smi == 0); // leave late-init sentinel/objects alone
    table[32] = 10; open();
    assert(table[32] == 10 && hypertweak_folder_cache_sync_hits == hits); // unknown stock value
    hypertweak_folder_columns_field_offset = 0x200;
    table[64] = 6; open();
    assert(table[64] == 10 && table[32] == 10); // resolved field displacement, no fixed layout
    puts("PASS: real Dart ABI shims sync 3/4/5 cache, restore native defaults, preserve args/NZCV and reject sentinel/object writes");
}
