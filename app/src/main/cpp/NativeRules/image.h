// SPDX-License-Identifier: Apache-2.0
//
// A minimal, read-only view of an already mapped ELF image.
//
// This payload runs inside the launcher process. Every address it derives from
// a program header, a dynamic entry, a symbol, or a relocation is validated
// against the mapped segments before it is dereferenced: the linker is loading
// libraries concurrently with these walks, and an unvalidated pointer here is a
// launcher crash, not a failed hook.
#pragma once

#include <elf.h>
#include <link.h>
#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native {

constexpr size_t kMaxImageSegments = 16u;
constexpr size_t kMaxImagePathLength = 512u;
// The dynamic symbol table is bounded by both its own segment and this cap, so
// a corrupt hash table cannot turn a symbol scan into a full address-space walk.
constexpr size_t kMaxSymbolCount = 1u << 22;

struct Segment {
    uintptr_t begin;
    uintptr_t end;
    uint32_t flags;
};

struct Image {
    uintptr_t base;
    size_t phnum;
    const ElfW(Phdr)* phdr;
    Segment segments[kMaxImageSegments];
    size_t segment_count;
    char path[kMaxImagePathLength];
};

struct DynamicView {
    const ElfW(Sym)* symbols;
    size_t symbol_count;
    const char* strings;
    size_t string_size;
    const ElfW(Rela)* plt_rela;
    size_t plt_rela_count;
    const ElfW(Rela)* dyn_rela;
    size_t dyn_rela_count;
};

size_t PageSize();
uintptr_t PageStart(uintptr_t address);

// Locates a mapped image by absolute path, by base name, or by load bias.
// `path` may be null when `base` is non-zero. Any match count other than one is
// rejected, so an ambiguous name can never select the wrong image.
//
// This walks the linker's own object list, which only covers the caller's
// namespace. An injected payload can live in a namespace that cannot see the
// host's libraries, so callers that must find them regardless should use
// FindImageViaMaps or FindImageByBuildId instead.
bool FindImage(const char* path, uintptr_t base, Image* output);

// Locates a mapped image from /proc/self/maps, which lists every mapping of the
// process independently of linker namespaces. `path` must be absolute.
bool FindImageViaMaps(const char* path, Image* output);

// Locates an ELF object by its build-id note. This is the only reliable way to
// find an object mapped out of an APK: /proc/self/maps names the APK rather than
// the object, and the linker may not list it at all.
//
// `build_id_va` is the virtual address of the note's descriptor inside the
// object (for example 0x1d8), which pins the match to the object's own base.
bool FindImageByBuildId(const uint8_t* build_id, size_t build_id_size,
                        uintptr_t build_id_va, Image* output);

// True when [address, address + size) lies entirely inside a single loaded
// segment carrying every flag in `required_flags`.
bool Contains(const Image& image, uintptr_t address, size_t size,
              uint32_t required_flags = 0u);

// Copies `size` bytes out of a validated image range.
bool ReadBytes(const Image& image, uintptr_t address, void* output, size_t size,
               uint32_t required_flags = 0u);

// True when the bytes at `address` equal `expected`.
bool MatchesBytes(const Image& image, uintptr_t address, const uint8_t* expected,
                  size_t size);

// Converts a dynamic-table address (absolute or image-relative) to a runtime
// address, rejecting anything that would land outside the image.
uintptr_t RuntimeAddress(const Image& image, ElfW(Addr) value);

bool BuildDynamicView(const Image& image, DynamicView* output);

bool SymbolNameEquals(const DynamicView& view, uint32_t symbol_index,
                      const char* expected);

// Resolves a defined dynamic symbol to its runtime address. Returns null when
// the symbol is absent, undefined, or resolves outside the image.
void* SymbolLookup(const Image& image, const char* name, size_t* size);

}  // namespace hypertweak::native
