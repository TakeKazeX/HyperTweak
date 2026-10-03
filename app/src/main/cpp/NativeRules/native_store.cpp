// SPDX-License-Identifier: Apache-2.0
#include "native_store.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

namespace hypertweak::native {
namespace {
int OpenStore(bool create) {
    const uid_t uid = getuid();
    if (uid < 10000u) return -1; // Never create root-owned files in the spawner.
    char path[128];
    snprintf(path, sizeof(path), "/data/user_de/%u/com.miui.home", uid / 100000u);
    int parent = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (parent < 0) return -1;
    struct stat info{};
    if (fstat(parent, &info) != 0 || info.st_uid != uid) {
        close(parent);
        return -1;
    }
    if (create) (void)mkdirat(parent, "hypertweak-native", 0700);
    int directory = openat(parent, "hypertweak-native",
                           O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    close(parent);
    if (directory >= 0 && (fstat(directory, &info) != 0 || info.st_uid != uid ||
                            (info.st_mode & 0077u) != 0u)) {
        close(directory);
        return -1;
    }
    return directory;
}
bool ValidName(const char* name) {
    return name != nullptr && name[0] != '\0' && strlen(name) < 64u &&
           strchr(name, '/') == nullptr && name[0] != '.';
}
}
bool ReadNativeRecord(const char* name, void* data, size_t size) {
    if (!ValidName(name) || data == nullptr) return false;
    const int directory = OpenStore(false);
    if (directory < 0) return false;
    const int fd = openat(directory, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    close(directory);
    if (fd < 0) return false;
    struct stat info{};
    bool valid = fstat(fd, &info) == 0 && S_ISREG(info.st_mode) &&
                 info.st_uid == getuid() && info.st_size == static_cast<off_t>(size) &&
                 (info.st_mode & 0077u) == 0u;
    size_t done = 0u;
    while (valid && done < size) {
        const ssize_t count = read(fd, static_cast<char*>(data) + done, size - done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { valid = false; break; }
        done += static_cast<size_t>(count);
    }
    close(fd);
    return valid;
}
bool WriteNativeRecord(const char* name, const void* data, size_t size) {
    if (!ValidName(name) || data == nullptr) return false;
    const int directory = OpenStore(true);
    if (directory < 0) return false;
    char temporary[96];
    snprintf(temporary, sizeof(temporary), "%s.%d.tmp", name, getpid());
    // A killed writer can leave its temporary record behind; only this exact
    // task-owned name is replaced, inside the checked private directory.
    (void)unlinkat(directory, temporary, 0);
    const int fd = openat(directory, temporary,
                          O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) { close(directory); return false; }
    size_t done = 0u;
    bool valid = true;
    while (done < size) {
        const ssize_t count = write(fd, static_cast<const char*>(data) + done, size - done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { valid = false; break; }
        done += static_cast<size_t>(count);
    }
    if (valid) valid = fsync(fd) == 0;
    close(fd);
    if (valid) valid = renameat(directory, temporary, directory, name) == 0;
    if (valid) (void)fsync(directory);
    else (void)unlinkat(directory, temporary, 0);
    close(directory);
    return valid;
}
void DeleteNativeRecord(const char* name) {
    if (!ValidName(name)) return;
    const int directory = OpenStore(false);
    if (directory >= 0) { (void)unlinkat(directory, name, 0); close(directory); }
}
}
