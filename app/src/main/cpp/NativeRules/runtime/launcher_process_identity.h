#pragma once

#include <stddef.h>

// Process admission and immutable library-path contracts, shared without runtime state.
namespace hypertweak::native::identity {
constexpr char kSpawnerPath[] = "/system_ext/bin/hyos_spawner";
constexpr char kLauncherProcessName[] = "com.miui.home";
constexpr char kDataLauncherLibraryTail[] =
        "/base.apk!/lib/arm64-v8a/libapp_launcher.so";
constexpr char kSystemLauncherLibraryTail[] =
        "/MiuiHome.apk!/lib/arm64-v8a/libapp_launcher.so";
constexpr char kDartLibraryName[] = "libapp.so";
constexpr char kDartLibraryTail[] = "/libapp.so";

constexpr size_t ConstStringLength(const char* value) {
    size_t length = 0u;
    if (value == nullptr) return length;
    while (value[length] != '\0') ++length;
    return length;
}

constexpr bool StartsWith(const char* value, const char* prefix) {
    if (value == nullptr || prefix == nullptr) return false;
    for (size_t index = 0u; prefix[index] != '\0'; ++index) {
        if (value[index] != prefix[index]) return false;
    }
    return true;
}

constexpr bool StringsEqual(const char* left, const char* right) {
    if (left == nullptr || right == nullptr) return false;
    size_t index = 0u;
    while (left[index] != '\0' && right[index] != '\0') {
        if (left[index] != right[index]) return false;
        ++index;
    }
    return left[index] == right[index];
}

constexpr bool EndsWith(const char* value, const char* suffix) {
    if (value == nullptr || suffix == nullptr) return false;
    const size_t value_length = ConstStringLength(value);
    const size_t suffix_length = ConstStringLength(suffix);
    if (suffix_length > value_length) return false;
    const size_t start = value_length - suffix_length;
    for (size_t index = 0u; index < suffix_length; ++index) {
        if (value[start + index] != suffix[index]) return false;
    }
    return true;
}

constexpr bool IsLauncherLibraryPath(const char* path) {
    if (path == nullptr) return false;
    if (StringsEqual(path, "libapp_launcher.so")) return true;
    if (StartsWith(path, "/data/app/") &&
            EndsWith(path, kDataLauncherLibraryTail)) {
        return true;
    }
    return StartsWith(path, "/product/priv-app/MiuiHome/") &&
            EndsWith(path, kSystemLauncherLibraryTail);
}

constexpr bool IsDartLibraryPath(const char* path) {
    return StringsEqual(path, kDartLibraryName) ||
            EndsWith(path, kDartLibraryTail);
}

static_assert(IsLauncherLibraryPath(
        "/data/app/~~token/com.miui.home-token/base.apk!/lib/arm64-v8a/"
        "libapp_launcher.so"));
static_assert(IsLauncherLibraryPath(
        "/product/priv-app/MiuiHome/MiuiHome.apk!/lib/arm64-v8a/"
        "libapp_launcher.so"));
static_assert(!IsLauncherLibraryPath(
        "/data/app/token/base.apk!/lib/armeabi-v7a/libapp_launcher.so"));
static_assert(!IsLauncherLibraryPath(
        "/data/app/token/base.apk!/lib/arm64-v8a/libnot_launcher.so"));
static_assert(!IsLauncherLibraryPath(
        "/data/local/tmp/base.apk!/lib/arm64-v8a/libapp_launcher.so"));
static_assert(IsLauncherLibraryPath("libapp_launcher.so"));
static_assert(StringsEqual("com.miui.home", kLauncherProcessName));
static_assert(!StringsEqual("com.miui.home:remote", kLauncherProcessName));
static_assert(!StringsEqual("com.evil.home", kLauncherProcessName));


bool IsLauncherProcess();
bool IsHyosSpawnerProcessFamily();
bool IsLauncherHookProcess();
}  // namespace hypertweak::native::identity
