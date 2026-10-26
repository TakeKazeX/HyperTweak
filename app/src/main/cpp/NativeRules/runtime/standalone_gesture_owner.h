#pragma once
#include <string.h>

namespace hypertweak::native {
// Match the package component and payload together, including APK-mapped and
// extracted native libraries. A random library with the same basename is not an owner.
inline bool IsStandaloneGestureImage(const char* name) {
    if (name == nullptr || strncmp(name, "/data/app/", 10u) != 0) return false;
    const char* package = strstr(name, "/dev.codex.miuibackgesturehook-");
    if (package == nullptr) return false;
    const char* separator = strchr(package + 1, '/');
    if (separator == nullptr) return false;
    constexpr char suffix[] = "/libmiui_home_hyos_lsp.so";
    const size_t length = strlen(separator);
    return length >= sizeof(suffix) - 1u &&
           strcmp(separator + length - (sizeof(suffix) - 1u), suffix) == 0;
}
}  // namespace hypertweak::native
