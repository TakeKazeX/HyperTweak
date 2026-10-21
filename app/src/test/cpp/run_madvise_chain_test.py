#!/usr/bin/env python3
"""Execute production madvise guards with distinct preceding GOT owners."""
from pathlib import Path
import subprocess
import tempfile

native = Path(__file__).resolve().parents[2] / "main/cpp/NativeRules"
source = (native / "lsposed_hook_backend.cpp").read_text()
body = source[source.index("bool AddProtectedPage("):source.index("bool RangeInImage(")]
preamble = r'''
#include <cassert>
#include <cstdint>
#include <cerrno>
#include <cstddef>
#include <sys/mman.h>
#include <vector>
using MadviseFn = int (*)(void*, size_t, int);
MadviseFn g_direct_madvise_backup, g_archive_madvise_backup;
constexpr size_t kMaxProtectedPages = 128;
uintptr_t g_protected_pages[kMaxProtectedPages]{};
size_t g_protected_page_count = 0;
uint32_t g_protected_page_lock = 0;
constexpr int ANDROID_LOG_DEBUG = 0;
const char* kLogTag = "test";
int __android_log_print(int, const char*, const char*, ...) { return 0; }
size_t PageSize() { return 4096; }
uintptr_t PageStart(uintptr_t p) { return p & ~uintptr_t(4095); }
struct Call { int owner; uintptr_t address; size_t length; int advice; };
std::vector<Call> calls;
bool reject = false;
int direct(void* p, size_t n, int a) {
    calls.push_back({1, reinterpret_cast<uintptr_t>(p), n, a});
    if (reject) { errno = EACCES; return -1; }
    return 0;
}
int archive(void* p, size_t n, int a) {
    calls.push_back({2, reinterpret_cast<uintptr_t>(p), n, a});
    return 0;
}
'''
tests = r'''
int main() {
    g_direct_madvise_backup = direct;
    g_archive_madvise_backup = archive;
    assert(AddProtectedPage(0x2001));
    assert(GuardedMadviseDirect(reinterpret_cast<void*>(0x1000), 0x3000, MADV_DONTNEED) == 0);
    assert(calls.size() == 2 && calls[0].owner == 1 && calls[1].owner == 1);
    assert(calls[0].address == 0x1000 && calls[0].length == 0x1000);
    assert(calls[1].address == 0x3000 && calls[1].length == 0x1000);
    calls.clear();
    assert(GuardedMadviseArchive(reinterpret_cast<void*>(0x1000), 0x3000, MADV_DONTNEED) == 0);
    assert(calls.size() == 2 && calls[0].owner == 2 && calls[1].owner == 2);
    calls.clear();
    assert(GuardedMadviseDirect(reinterpret_cast<void*>(0x2000), 0x1000, MADV_DONTNEED) == 0);
    assert(calls.empty());
    assert(GuardedMadviseArchive(reinterpret_cast<void*>(0x2000), 0x1000, MADV_NORMAL) == 0);
    assert(calls.size() == 1 && calls[0].owner == 2 && calls[0].advice == MADV_NORMAL);
    calls.clear();
    assert(GuardedMadviseDirect(reinterpret_cast<void*>(0x1001), 0x3000, MADV_DONTNEED) == 0);
    assert(calls.size() == 1 && calls[0].address == 0x1001 && calls[0].length == 0x3000);
    reject = true;
    assert(GuardedMadviseDirect(reinterpret_cast<void*>(0x1000), 0x3000, MADV_DONTNEED) == -1);
    assert(errno == EACCES);
    g_archive_madvise_backup = nullptr;
    assert(GuardedMadviseArchive(nullptr, 0, MADV_NORMAL) == -1 && errno == ENOSYS);
}
'''
with tempfile.TemporaryDirectory(prefix="hypertweak-madvise-") as directory:
    path = Path(directory)
    (path / "test.cpp").write_text(preamble + body + tests)
    subprocess.run(["clang++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    "-fsanitize=address,undefined", str(path / "test.cpp"), "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
print("madvise per-slot chain and protected-page tests passed")
