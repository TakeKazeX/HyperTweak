// SPDX-License-Identifier: Apache-2.0
//
// Xiaomi's Flutter runtime calls madvise(MADV_DONTNEED) over its own code pages
// while running. A patched page inside that range is silently reverted to the
// file contents, which turns a working hook into a nondeterministic one. The
// guard intercepts madvise in that one library and splits the range so pages
// this payload owns are never discarded.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace hypertweak::native {

// Installs the guard once per process. Must succeed before any page in the
// launcher's images is patched; callers treat a failure as "do not hook".
bool EnsurePageGuard();

// True once the guard is installed for this process.
bool PageGuardReady();

// True when the guard can never be installed here, as opposed to merely not yet
// (the Flutter runtime has not been loaded). LSPlt may already hold a partial
// registration, so retrying would risk duplicating it.
bool PageGuardFailed();

// Registers the page containing `address` as hook-owned.
bool ProtectPage(uintptr_t address);

}  // namespace hypertweak::native
