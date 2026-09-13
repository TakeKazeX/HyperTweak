// SPDX-License-Identifier: Apache-2.0
//
// A file-based control channel for the launcher-side rules.
//
// LSPosed injects only the *native* payload into `com.miui.home`: the launcher's
// package carries no dex, and the framework loads the module's Java elsewhere.
// Nothing in the launcher can therefore read the module's preferences, and the
// payload runs as `platform_app`, which cannot read another app's private or
// app-specific external storage either.
//
// Rather than assume a channel exists, the payload probes a list of candidate
// paths once at startup and logs exactly which of them it can open. The module
// process writes the same file into every location it is allowed to write, so
// whichever path survives is picked up without a further build.
#pragma once

#include <stddef.h>

namespace hypertweak::native {

// Probes every candidate path and logs the outcome for each. When no channel
// exists yet, callers may invoke this again after the module creates its file.
// The probe is serialized and becomes a no-op once a readable channel is found.
void ProbeConfigChannel();

// Reads `key=<0|1>` from the selected channel. Returns `fallback` when no
// channel is readable or the key is absent.
bool ReadConfigFlag(const char* key, bool fallback);

// The channel currently in use, or a static reason string. For status output.
const char* ConfigChannelPath();

}  // namespace hypertweak::native
