#include "launcher_process_identity.h"

#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

namespace hypertweak::native::identity {
bool IsLauncherProcess() {
    const int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char command_line[64]{};
    ssize_t result;
    do {
        result = read(fd, command_line, sizeof(command_line));
    } while (result < 0 && errno == EINTR);
    close(fd);
    if (result <= 0) return false;

    constexpr size_t expected_length =
            ConstStringLength(kLauncherProcessName);
    return static_cast<size_t>(result) > expected_length &&
            command_line[expected_length] == '\0' &&
            StringsEqual(command_line, kLauncherProcessName);
}

bool IsHyosSpawnerProcessFamily() {
    char executable[128]{};
    const ssize_t length = readlink("/proc/self/exe", executable,
                                    sizeof(executable) - 1u);
    if (length <= 0 || static_cast<size_t>(length) >= sizeof(executable)) {
        return false;
    }
    executable[length] = '\0';
    return StringsEqual(executable, kSpawnerPath);
}

// LSPosed's HYOS entry is initialized in the root spawner (whose cmdline is
// currently `usap64`) before it forks the package process.  The old check
// required the spawner itself to already advertise com.miui.home, which is no
// longer true after the launcher update.  Keep the executable identity as the
// hard boundary and accept the actual launcher child as well.
bool IsLauncherHookProcess() {
    return IsLauncherProcess() || IsHyosSpawnerProcessFamily();
}

}  // namespace hypertweak::native::identity
