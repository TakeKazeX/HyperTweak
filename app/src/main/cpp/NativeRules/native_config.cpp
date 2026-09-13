// SPDX-License-Identifier: Apache-2.0
#include "native_config.h"

#include "logging.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

namespace hypertweak::native {
namespace {

constexpr size_t kMaxLineLength = 128u;
constexpr size_t kMaxFileSize = 4096u;

// Probed and confirmed on OS4.0.0.25: the launcher's `platform_app` sandbox
// cannot see app-private storage, app-specific external storage (Android/data
// is hidden entirely from other UIDs) or /data/local/tmp, and writing the shared
// copy through MediaStore lands as a different file name (`*.conf.txt`). Shared
// external storage read directly is the one channel that works.
//
// Android/media is the app's own directory there, so publishing needs no
// permission and leaves nothing in the user's Downloads listing. The Download
// paths stay in the list so a file can be dropped there by hand for debugging.
constexpr const char* kCandidatePaths[] = {
    "/storage/emulated/0/Android/media/com.takekazex.hypertweak/hypertweak_native.conf",
    "/data/media/0/Android/media/com.takekazex.hypertweak/hypertweak_native.conf",
    "/storage/emulated/0/Download/hypertweak_native.conf",
    "/data/media/0/Download/hypertweak_native.conf",
};

volatile uint32_t g_probe_lock = 0u;
const char* volatile g_channel = "unprobed";

bool IsReadableChannel(const char* path) {
    return path != nullptr && strcmp(path, "unprobed") != 0 &&
            strcmp(path, "none_readable") != 0;
}

bool ReadFile(const char* path, char* buffer, size_t size) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t total = 0;
    while (static_cast<size_t>(total) < size - 1u) {
        const ssize_t count = read(fd, buffer + total, size - 1u - static_cast<size_t>(total));
        if (count < 0) {
            if (errno == EINTR) continue;
            total = -1;
            break;
        }
        if (count == 0) break;
        total += count;
    }
    close(fd);
    if (total < 0) return false;
    buffer[total] = '\0';
    return true;
}

}  // namespace

void ProbeConfigChannel() {
    const char* previous = __atomic_load_n(&g_channel, __ATOMIC_ACQUIRE);
    if (IsReadableChannel(previous)) return;
    if (__atomic_exchange_n(&g_probe_lock, uint32_t{1}, __ATOMIC_ACQ_REL) != 0u) return;
    previous = __atomic_load_n(&g_channel, __ATOMIC_ACQUIRE);
    if (IsReadableChannel(previous)) {
        __atomic_store_n(&g_probe_lock, uint32_t{0}, __ATOMIC_RELEASE);
        return;
    }
    const char* selected = nullptr;
    for (const char* path : kCandidatePaths) {
        const int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            close(fd);
            if (previous == nullptr || strcmp(previous, path) != 0) {
                LogInfo("config channel readable: %s", path);
            }
            if (selected == nullptr) selected = path;
        } else if (previous == nullptr || strcmp(previous, "unprobed") == 0) {
            LogInfo("config channel unreadable (%d): %s", errno, path);
        }
    }
    __atomic_store_n(&g_channel, selected != nullptr ? selected : "none_readable",
                     __ATOMIC_RELEASE);
    if (selected != nullptr) {
        if (previous == nullptr || strcmp(previous, selected) != 0) {
            LogInfo("config channel selected: %s", selected);
        }
    } else if (previous == nullptr || strcmp(previous, "none_readable") != 0) {
        LogWarn("no config channel is readable from the launcher process");
    }
    __atomic_store_n(&g_probe_lock, uint32_t{0}, __ATOMIC_RELEASE);
}

bool ReadConfigFlag(const char* key, bool fallback) {
    if (key == nullptr) return fallback;
    const char* path = __atomic_load_n(&g_channel, __ATOMIC_ACQUIRE);
    if (path == nullptr || strcmp(path, "unprobed") == 0 || strcmp(path, "none_readable") == 0) {
        return fallback;
    }
    char contents[kMaxFileSize];
    if (!ReadFile(path, contents, sizeof(contents))) return fallback;
    const size_t key_length = strlen(key);
    const char* cursor = contents;
    while (cursor != nullptr && *cursor != '\0') {
        const char* end = strchr(cursor, '\n');
        const size_t length = end != nullptr ? static_cast<size_t>(end - cursor)
                                             : strlen(cursor);
        if (length <= kMaxLineLength && length > key_length + 1u &&
            strncmp(cursor, key, key_length) == 0 && cursor[key_length] == '=') {
            const char value = cursor[key_length + 1u];
            if (value == '1') return true;
            if (value == '0') return false;
        }
        cursor = end != nullptr ? end + 1 : nullptr;
    }
    return fallback;
}

const char* ConfigChannelPath() {
    return __atomic_load_n(&g_channel, __ATOMIC_ACQUIRE);
}

}  // namespace hypertweak::native
