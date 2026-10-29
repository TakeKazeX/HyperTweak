package com.takekazex.hypertweak.util.platform

import com.takekazex.hypertweak.settings.contract.AivsPathPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fixed package roots only. Migration never overwrites files or recursively deletes data. */
internal object AivsDirectoryMigration {
    private val mutex = Mutex()

    suspend fun directories(root: String): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val output = NativeRootCommand.run(script(root, move = false)).toString(Charsets.UTF_8)
            val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
            check(lines.lastOrNull() == "HT_AIVS_OK") { "Directory check failed" }
            val packages = lines.dropLast(1)
            check(packages.all { it in AivsPathPolicy.packages }) { "Invalid directory response" }
            packages
        }
    }

    suspend fun move(root: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val output = NativeRootCommand.run(script(root, move = true), timeoutMs = 60_000)
                .toString(Charsets.UTF_8)
            check(output.trim() == "HT_AIVS_OK") { "Directory migration failed" }
        }
    }

    internal fun script(root: String, move: Boolean): String {
        require(Regex("/storage/emulated/[0-9]+").matches(root))
        val roots = AivsPathPolicy.packages.joinToString(" ") { NativeRootCommand.quote(it) }
        return """
            set -eu
            root=${NativeRootCommand.quote(root)}
            [ "${'$'}(id -u)" = 0 ] || exit 60
            [ -d "${'$'}root" ] && [ ! -L "${'$'}root" ] || exit 61
            for parent in "${'$'}root/MIUI" "${'$'}root/MIUI/AIVS"; do
                [ ! -L "${'$'}parent" ] || exit 62
                [ ! -e "${'$'}parent" ] || [ -d "${'$'}parent" ] || exit 62
            done
            # Preflight the entire tree, including dot files, before moving anything.
            preflight() (
                src=${'$'}1; dst=${'$'}2
                [ ! -L "${'$'}src" ] && [ ! -L "${'$'}dst" ] || exit 63
                if [ -d "${'$'}src" ]; then
                    [ ! -e "${'$'}dst" ] || [ -d "${'$'}dst" ] || exit 64
                    for child in "${'$'}src"/* "${'$'}src"/.[!.]* "${'$'}src"/..?*; do
                        [ -e "${'$'}child" ] || [ -L "${'$'}child" ] || continue
                        preflight "${'$'}child" "${'$'}dst/${'$'}{child##*/}" || exit ${'$'}?
                    done
                else
                    [ -f "${'$'}src" ] && [ ! -e "${'$'}dst" ] || exit 65
                fi
            )
            migrate() (
                src=${'$'}1; dst=${'$'}2
                [ ! -L "${'$'}src" ] && [ ! -L "${'$'}dst" ] || exit 63
                if [ -d "${'$'}src" ]; then
                    mkdir -p "${'$'}dst" || exit 67
                    for child in "${'$'}src"/* "${'$'}src"/.[!.]* "${'$'}src"/..?*; do
                        [ -e "${'$'}child" ] || [ -L "${'$'}child" ] || continue
                        migrate "${'$'}child" "${'$'}dst/${'$'}{child##*/}" || exit ${'$'}?
                    done
                    rmdir "${'$'}src" || exit 68
                else
                    [ -f "${'$'}src" ] && [ ! -e "${'$'}dst" ] || exit 65
                    mv -n "${'$'}src" "${'$'}dst" || exit 69
                    [ ! -e "${'$'}src" ] || exit 66
                fi
            )
            for pkg in $roots; do
                src="${'$'}root/${'$'}pkg"
                [ ! -L "${'$'}src" ] || exit 63
                [ ! -e "${'$'}src" ] || [ -d "${'$'}src" ] || exit 64
                if [ -d "${'$'}src" ]; then
                    ${if (move) "preflight \"${'$'}src\" \"${'$'}root/MIUI/AIVS/${'$'}pkg\"" else "printf '%s\\n' \"${'$'}pkg\""}
                fi
            done
            ${if (move) """
            for pkg in $roots; do
                src="${'$'}root/${'$'}pkg"
                if [ -d "${'$'}src" ]; then migrate "${'$'}src" "${'$'}root/MIUI/AIVS/${'$'}pkg"; fi
            done
            """ else ""}
            printf 'HT_AIVS_OK\n'
        """.trimIndent()
    }
}
