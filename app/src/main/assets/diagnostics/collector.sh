#!/system/bin/sh
# HyperTweak diagnostic collector (Android / POSIX sh)
# Local diagnostic collection. No settings changed; nothing uploaded.
# The HyperTweak export provider may start the app and write its own log export.
#
# v3 integrated collector; --app JOB APP_UID is reserved for the app runner.
# v2 (2026-10-26) changes, driven by the 2026-10-25 pond archive review:
#   - DropBox: query every tag that actually exists, not four always-empty ones
#   - settings: keep short string values (string switches were invisible before)
#   - previous boot logcat: real fallbacks instead of a bare "Unexpected EOF"
#   - ksud: a version flag that exists, plus module.prop as the authority
#   - boot-verdict.txt: state panic vs. orderly reboot instead of leaving it to guesswork
#   - LSPosed: HyperTweak-only filtered extract, so our records survive the tails
#   - ANR traces, tombstones and raw logd files that survive a truncated logcat

umask 077

# Root is required to read kernel crash data on most devices.
if [ "$(id -u 2>/dev/null)" != "0" ]; then
    echo "[HyperTweak] Requesting root permission..."
    if [ "${HT_DIAG_ROOT_ATTEMPT:-0}" = 1 ]; then
        echo "[ERROR] Root permission was not granted."
        exit 1
    fi
    case "$0" in *"'"*) echo "[ERROR] Move script to a path without apostrophes."; exit 1 ;; esac
    exec su -c "HT_DIAG_ROOT_ATTEMPT=1 /system/bin/sh '$0'"
fi

APP_UID=
if [ "${1:-}" = '--app' ]; then
    JOB=$2
    APP_UID=$3
    case "$JOB" in /data/user/*/com.takekazex.hypertweak/files/diagnostics/job-*) ;; *) exit 64 ;; esac
    case "$APP_UID" in ''|*[!0-9]*) exit 64 ;; esac
    [ -d "$JOB" ] && [ ! -L "$JOB" ] || exit 64
    # The app-owned job parent is 0700; writable children allow cancellation/TTL
    # cleanup by the app even when the root timeout kills the script.
    chmod 700 "$JOB" || exit 64
    umask 000
    WORK="$JOB/payload"
    ARCHIVE="$JOB/report.tar.gz"
    command -v timeout >/dev/null 2>&1 || exit 65
else
    OUT_DIR=/sdcard/Download
    [ -d "$OUT_DIR" ] || OUT_DIR=/storage/emulated/0/Download
    [ -d "$OUT_DIR" ] || exit 1
    STAMP=$(date +%Y%m%d-%H%M%S)
    WORK="/data/local/tmp/ht-diag-${STAMP}-$$"
    ARCHIVE="$OUT_DIR/HyperTweak-Diagnostics-$STAMP-$$.tar.gz"
fi
# App runner enforces a 180-second total deadline. Standalone uses the same bound.
if [ "${HT_DIAG_BOUNDED:-0}" != 1 ]; then
    command -v timeout >/dev/null 2>&1 || { echo 'timeout is required'; exit 65; }
    exec timeout -s KILL 180 env HT_DIAG_BOUNDED=1 /system/bin/sh "$0" "$@"
fi
trap 'exit 130' INT TERM

if ! mkdir -p "$WORK/pstore" "$WORK/hypertweak" "$WORK/lsposed" "$WORK/dropbox" \
        "$WORK/anr" "$WORK/tombstones" "$WORK/logd"; then
    echo "[ERROR] Cannot create a temporary directory."
    exit 1
fi

printf '%s\n' "HyperTweak diagnostics (collector v3, based on v2)" > "$WORK/README.txt"
cat >> "$WORK/README.txt" <<'HT_README_EOF'
This archive may contain sensitive personal information from system logs.
Review its contents before sharing it with the developer.
Collected locally only; nothing was uploaded or transmitted by this script.
Collect promptly after an unexpected reboot (before another reboot).
Pstore/last_kmsg may not exist on all devices, even after a real kernel panic.
Logs from the current boot may not explain a previous boot's kernel crash.
Additional logs include Android DropBox, ANR traces, tombstones and other
Xposed modules' framework logs.
LSPosed logs are bounded tails; hypertweak-filtered.txt re-extracts only this
module's records so they survive the tails.
DropBox is bounded per tag; dropbox/index.txt lists what the device held.
HyperTweak export may start the app and create an app-owned export file.
Settings snapshot keeps numeric/boolean values, short string values and the
key inventory. Debug-log strings and sets are omitted.
boot-verdict.txt states whether the previous boot ended in a kernel panic or in
an orderly user-space reboot, and who requested it when the device recorded it.
HT_README_EOF

# Record command failures separately. Android toybox normally provides timeout.
# HT_CAP_BYTES>0 keeps only the tail of a large output: newest records matter.
file_size() { wc -c < "$1" 2>/dev/null | tr -d " \t"; }

capture() {
    dest=$1
    shift
    cap=${HT_CAP_BYTES:-0}
    # Bound stdout/stderr streams, not the command's RLIMIT_FSIZE: app_process/ART
    # may create its own cache files, so a file-size ulimit can crash content itself.
    fifo="$WORK/$dest.stderr-fifo"
    result_file="$WORK/$dest.exit-status"
    rm -f "$fifo" "$result_file"
    mkfifo "$fifo" || return 1
    tail -c 65536 < "$fifo" > "$WORK/$dest.error.txt" &
    error_reader=$!
    (
        if command -v timeout >/dev/null 2>&1; then
            timeout -s KILL 15 "$@" 2> "$fifo"
        else
            "$@" 2> "$fifo"
        fi
        printf '%s\n' "$?" > "$result_file"
    ) | tail -c 8388608 > "$WORK/$dest.raw"
    wait "$error_reader"
    result=$(cat "$result_file" 2>/dev/null)
    case "$result" in ''|*[!0-9]*) result=125 ;; esac
    rm -f "$fifo" "$result_file"
    size=$(file_size "$WORK/$dest.raw")
    case "$size" in ''|*[!0-9]*) size=0 ;; esac
    case "$cap" in ''|*[!0-9]*) cap=0 ;; esac
    # A missing tail/wc must degrade to "keep everything", never abort the run.
    if [ "$size" -gt 0 ] && [ "$cap" -gt 0 ] && [ "$size" -gt "$cap" ] &&
            command -v tail >/dev/null 2>&1; then
        tail -c "$cap" "$WORK/$dest.raw" > "$WORK/$dest" 2>/dev/null || mv "$WORK/$dest.raw" "$WORK/$dest"
        printf '\n[[truncated by collector: kept the last %s of %s bytes]]\n' "$cap" "$size" >> "$WORK/$dest"
    else
        mv "$WORK/$dest.raw" "$WORK/$dest"
    fi
    rm -f "$WORK/$dest.raw"
    if [ "$size" -ge 8388608 ]; then
        printf '\n[[stdout tail bounded at 8388608 bytes; earlier output may be omitted]]\n' >> "$WORK/$dest"
    fi
    final=$(file_size "$WORK/$dest")
    printf '%s exit=%s bytes=%s\n' "$dest" "$result" "$final" >> "$WORK/extra-status.txt"
    [ "$result" -eq 0 ] || echo "Command exit status: $result (124 usually means timeout)" >> "$WORK/$dest.error.txt"
    [ -s "$WORK/$dest.error.txt" ] || rm -f "$WORK/$dest.error.txt"
    return "$result"
}

{
    echo "=== Collected at ==="
    date
    echo
    echo "=== Phone / OS ==="
    for prop in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.display.id ro.build.fingerprint ro.miui.ui.version.name ro.build.version.incremental; do
        printf '%s: ' "$prop"
        getprop "$prop" 2>/dev/null
    done
    echo
    echo "=== Reboot reason ==="
    for prop in sys.boot.reason ro.boot.bootreason persist.sys.boot.reason persist.sys.boot.reason.history persist.vendor.aeev.last.boot.reason; do
        printf '%s: ' "$prop"
        getprop "$prop" 2>/dev/null
    done
    echo
    echo "=== Kernel ==="
    uname -a 2>&1
    cat /proc/version 2>/dev/null
    echo
    echo "=== Uptime (current boot) ==="
    uptime 2>&1
    cat /proc/uptime 2>/dev/null
    echo
    echo "=== SELinux ==="
    getenforce 2>/dev/null
    echo
    echo "=== Root / tools ==="
    id
    for cmd in magisk ksud apd; do
        if command -v "$cmd" >/dev/null 2>&1; then
            echo "$cmd: available"
        else
            echo "$cmd: not found in PATH"
        fi
    done
    echo
    echo "=== Installed root module identifiers ==="
    if [ -d /data/adb/modules ]; then
        ls -1 /data/adb/modules 2>&1
    else
        echo "Not accessible"
    fi
} > "$WORK/device-info.txt" 2>&1

printf '%s\n' "[HyperTweak] Collecting persistent crash logs..."
COUNT=0
if [ -d /sys/fs/pstore ]; then
    for f in /sys/fs/pstore/*; do
        if [ -f "$f" ]; then
            base=${f##*/}
            if HT_CAP_BYTES=4194304 capture "pstore/$base" cat "$f"; then
                COUNT=$((COUNT + 1))
                rm -f "$WORK/pstore-${base}.error.txt"
            else
                rm -f "$WORK/pstore/$base"
            fi
        fi
    done
fi
if [ "$COUNT" -eq 0 ]; then
    echo "No accessible pstore records were found." > "$WORK/pstore/NO_RECORDS.txt"
fi

if [ -f /proc/last_kmsg ]; then
    HT_CAP_BYTES=4194304 capture last_kmsg.txt cat /proc/last_kmsg
fi

printf '%s\n' "[HyperTweak] Collecting current kernel log..."
HT_CAP_BYTES=4194304 capture dmesg.txt dmesg

printf '%s\n' "[HyperTweak] Collecting Android crash and system logs..."
# Avoid the radio buffer by default; it may contain additional sensitive data.
# The events buffer carries am_anr / am_crash / am_kill / am_proc_died, which is
# how process restarts become countable instead of guesswork.
HT_CAP_BYTES=8388608 capture logcat.txt logcat -d -b main -b system -b crash -b events -v threadtime

{
    echo "pstore_files_collected=$COUNT"
    if [ -s "$WORK/last_kmsg.txt" ]; then echo "last_kmsg=collected"; else echo "last_kmsg=unavailable"; fi
    if [ -s "$WORK/dmesg.txt" ]; then echo "dmesg=collected"; else echo "dmesg=unavailable"; fi
    if [ -s "$WORK/logcat.txt" ]; then echo "logcat=collected"; else echo "logcat=unavailable"; fi
} > "$WORK/collection-status.txt"

printf '%s\n' "[HyperTweak] Collecting module versions and failure history..."
{
    echo "=== Root implementation versions ==="
    for rootcmd in magisk ksud apd; do
        command -v "$rootcmd" >/dev/null 2>&1 || continue
        echo "--- $rootcmd ---"
        # ksud has no -v; ask each tool for a flag it actually implements.
        for flag in -v --version version; do
            if command -v timeout >/dev/null 2>&1; then
                timeout 5 "$rootcmd" "$flag" 2>&1 | head -n 5
            else
                "$rootcmd" "$flag" 2>&1 | head -n 5
            fi
        done
    done
    echo
    echo "=== Root modules and state markers ==="
    echo "module.prop is the authority for id/version; the directory name is not."
    for mod in /data/adb/modules/*; do
        [ -d "$mod" ] || continue
        echo "--- ${mod##*/} ---"
        if [ -f "$mod/module.prop" ]; then cat "$mod/module.prop"; fi
        for marker in disable remove update skip_mount; do
            [ ! -e "$mod/$marker" ] || echo "marker=$marker"
        done
    done
    echo "Directory presence alone does not prove a module is active."
} > "$WORK/root-modules.txt" 2>&1

capture hypertweak/package.txt dumpsys package com.takekazex.hypertweak
capture lsposed/manager-package.txt dumpsys package org.lsposed.manager

# Launcher identity: several features depend on the installed MiuiHome build.
capture launcher-package.txt sh -c 'dumpsys package com.miui.home | grep -E "versionName|versionCode|codePath|lastUpdateTime|firstInstallTime|primaryCpuAbi" | head -n 20'

# Which of our scoped processes are actually alive right now.
capture process-state.txt sh -c 'ps -A -o PID,PPID,STIME,NAME 2>/dev/null | grep -E "systemui|miui.home|takekazex|securitycenter|miui.aod" | head -n 40'

# ---------------------------------------------------------------- DropBox ----
# The 2026-10-25 archive exported only system_server_* tags and every one of them
# was empty, while the device held 44 system_app_anr / 20 data_app_crash entries.
capture dropbox/index.txt dumpsys dropbox
HT_CAP_BYTES=4194304
for tag in SYSTEM_LAST_KMSG SYSTEM_RESTART SYSTEM_BOOT SYSTEM_AUDIT SYSTEM_TOMBSTONE \
        system_server_crash system_server_native_crash system_server_watchdog system_server_anr \
        system_app_crash system_app_anr system_app_native_crash system_app_wtf system_app_strictmode \
        data_app_crash data_app_anr data_app_native_crash data_app_wtf data_app_strictmode \
        keymaster netstats; do
    capture "dropbox/$tag.txt" dumpsys dropbox --print "$tag"
done

# Then every other tag the device actually recorded, so an unseen failure class
# is not silently dropped by a hardcoded list.
if [ -s "$WORK/dropbox/index.txt" ]; then
    grep -oE "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]+ [A-Za-z_]+" "$WORK/dropbox/index.txt" 2>/dev/null |
        awk '{print $3}' | sort -u > "$WORK/dropbox/.tags"
    : > "$WORK/dropbox/.extra-tags"
    while IFS= read -r tag; do
        [ -n "$tag" ] || continue
        case "$tag" in
            VirusScanHistory) continue ;;
            SYSTEM_LAST_KMSG|SYSTEM_RESTART|SYSTEM_BOOT|SYSTEM_AUDIT|SYSTEM_TOMBSTONE) continue ;;
            system_server_*|system_app_*|data_app_*) continue ;;
            keymaster|netstats) continue ;;
        esac
        printf '%s\n' "$tag" >> "$WORK/dropbox/.extra-tags"
    done < "$WORK/dropbox/.tags"
    if [ -s "$WORK/dropbox/.extra-tags" ]; then
        head -n 12 "$WORK/dropbox/.extra-tags" > "$WORK/dropbox/.extra-tags.limited"
        while IFS= read -r tag; do
            [ -n "$tag" ] || continue
            HT_CAP_BYTES=1048576 capture "dropbox/extra-$tag.txt" dumpsys dropbox --print "$tag"
        done < "$WORK/dropbox/.extra-tags.limited"
        printf 'dropbox_extra_tags=%s\n' "$(wc -l < "$WORK/dropbox/.extra-tags")" >> "$WORK/extra-status.txt"
    fi
    rm -f "$WORK/dropbox/.tags" "$WORK/dropbox/.extra-tags" "$WORK/dropbox/.extra-tags.limited"
fi

# --------------------------------------------------- previous boot logcat ----
# The 2026-10-25 archive recorded only "logcat: Unexpected EOF!" here. Retry in
# the shapes that succeed on different buffer layouts, then fall back to the raw
# logd files, which survive when logcat cannot stitch a rotated buffer.
HT_CAP_BYTES=8388608
capture previous-boot-logcat.txt logcat -L -d -v threadtime
if [ ! -s "$WORK/previous-boot-logcat.txt" ]; then
    capture previous-boot-logcat.retry-all.txt logcat -L -d -b all -v threadtime
fi
if [ ! -s "$WORK/previous-boot-logcat.txt" ]; then
    echo "logcat -L produced no usable previous-boot log; see logd/ for raw files." >> "$WORK/extra-status.txt"
    if [ -d /data/misc/logd ]; then
        ls -1t /data/misc/logd/*.log 2>/dev/null | head -n 3 > "$WORK/logd/.list"
        while IFS= read -r raw; do
            [ -f "$raw" ] || continue
            HT_CAP_BYTES=8388608 capture "logd/${raw##*/}" cat "$raw"
        done < "$WORK/logd/.list"
        rm -f "$WORK/logd/.list"
    fi
fi
HT_CAP_BYTES=0

# ------------------------------------------------------------- HyperTweak ----
# Do not parse a Bundle's free-text preview as a shell path. Copy the known export
# location for the foreground Android user only after a successful provider reply.
if [ -n "$APP_UID" ]; then ANDROID_USER=$((APP_UID / 100000)); else ANDROID_USER=$(am get-current-user 2>/dev/null); fi
case "$ANDROID_USER" in ''|*[!0-9]*) echo 'Cannot establish Android user'; exit 66 ;; esac
HT_CAP_BYTES=8388608
capture hypertweak/provider.txt content call --user "$ANDROID_USER" --uri content://com.takekazex.hypertweak.logdump --method dump
if [ -f "$WORK/hypertweak/provider.txt" ] &&
    grep -q "path=" "$WORK/hypertweak/provider.txt" &&
    ! grep -q "error=" "$WORK/hypertweak/provider.txt"; then
    capture hypertweak/logs.txt cat "/data/user/$ANDROID_USER/com.takekazex.hypertweak/files/logs/latest.txt"
    # Keep every stamped export too: latest.txt is overwritten on each dump.
    ls -1t "/data/user/$ANDROID_USER/com.takekazex.hypertweak/files/logs/"hypertweak-logs-*.txt 2>/dev/null |
        head -n 3 > "$WORK/hypertweak/.list"
    while IFS= read -r export; do
        [ -f "$export" ] || continue
        HT_CAP_BYTES=2097152 capture "hypertweak/${export##*/}" cat "$export"
    done < "$WORK/hypertweak/.list"
    rm -f "$WORK/hypertweak/.list"
else
    echo "Provider unavailable or failed; installed version may lack export support." >> "$WORK/extra-status.txt"
fi
HT_CAP_BYTES=0

PREF="/data/user/$ANDROID_USER/com.takekazex.hypertweak/shared_prefs/hypertweak_settings.xml"
if [ -r "$PREF" ]; then
    # Numeric and boolean switches stay verbatim.
    capture hypertweak/settings-numeric.txt sh -c 'tr ">" "\n" < "$1" | sed -n "s/.*\(<boolean [^<]*\)$/\1>/p; s/.*\(<int [^<]*\)$/\1>/p; s/.*\(<long [^<]*\)$/\1>/p; s/.*\(<float [^<]*\)$/\1>/p"' sh "$PREF"
    # Short string values are now kept: string-backed switches live here and
    # were previously dropped, leaving their actual state unanswerable.
    # Android stores a string switch as <string name="K">V</string>, with the
    # value as element content rather than a value="" attribute, so parse the
    # element body. A multi-line value is joined with \n instead of being
    # silently reported as its first line.
    cat > "$WORK/.prefs.awk" <<'HT_AWK_EOF'
{
    line = $0
    sub(/^[ \t]+/, "", line)
    # A value may span lines, so continuation lines bypass the tag check.
    if (invalue) {
        value = value "\\n" line
        if (index(line, "</string>") > 0) {
            sub(/<\/string>.*$/, "", value)
            print key "=" value
            invalue = 0
        }
        next
    }
    if (index(line, "<string name=\"") == 1) {
        rest = substr(line, 15)
        q = index(rest, "\">")
        if (q > 0) {
            key = substr(rest, 1, q - 1)
            value = substr(rest, q + 2)
            if (index(value, "</string>") > 0) {
                sub(/<\/string>.*$/, "", value)
                print key "=" value
            } else {
                invalue = 1
            }
        }
    }
}
HT_AWK_EOF
    capture hypertweak/settings-strings.txt sh -c 'awk -f "$1" "$2" | grep -v "^debug_log" | cut -c1-240' sh "$WORK/.prefs.awk" "$PREF"
    rm -f "$WORK/.prefs.awk"
    # Key inventory proves which switches exist even when a value is filtered out.
    capture hypertweak/settings-keys.txt sh -c 'grep -o "name=\"[^\"]*\"" "$1" | sed "s/name=\"//;s/\"//" | sort -u' sh "$PREF"
    printf 'hypertweak_settings_bytes=%s\n' "$(wc -c < "$PREF")" >> "$WORK/extra-status.txt"
else
    echo "HyperTweak settings unavailable for current Android user." >> "$WORK/extra-status.txt"
fi

# ---------------------------------------------------------------- LSPosed ----
# Known LSPosed directory used by HyperTweak's own log reader. Other forks may
# use a different layout; absence is recorded rather than treated as success.
LOG_COUNT=0
HT_CAP_BYTES=2097152
for logdir in /data/adb/lspd/log /data/adb/lspd/log.old; do
    [ -d "$logdir" ] || continue
    # At most eight recent regular .log files per directory; preserve spaces in
    # filenames by reading one line at a time, without shell eval.
    ls -1t "$logdir"/*.log 2>/dev/null | head -n 8 > "$WORK/lsposed/file-list.tmp"
    while IFS= read -r logfile; do
        [ -f "$logfile" ] || continue
        name=${logfile##*/}
        dir_name=${logdir##*/}
        capture "lsposed/$dir_name-$name" cat "$logfile"
    done < "$WORK/lsposed/file-list.tmp"
    # Count in shell: a missing external tool must not abort the whole collector.
    while IFS= read -r _line; do
        [ -n "$_line" ] && LOG_COUNT=$((LOG_COUNT + 1))
    done < "$WORK/lsposed/file-list.tmp"
done
rm -f "$WORK/lsposed/file-list.tmp"
HT_CAP_BYTES=0
echo "lsposed_log_candidates=$LOG_COUNT (tails capped at 2 MiB each)" >> "$WORK/extra-status.txt"

# Our records are interleaved with other modules and can be cut off entirely by
# the tails. Re-extract this module's lines by module identity, not by feature
# name: the filter must stay correct for every feature, including new ones.
if ls "$WORK"/lsposed/*.log >/dev/null 2>&1; then
    grep -a -h -E "takekazex|hypertweak" \
        "$WORK"/lsposed/*.log 2>/dev/null | tail -c 4194304 > "$WORK/lsposed/hypertweak-filtered.txt" || :
    printf 'hypertweak_lsposed_lines=%s\n' "$(wc -l < "$WORK/lsposed/hypertweak-filtered.txt")" >> "$WORK/extra-status.txt"
fi

# ------------------------------------------------ ANR traces and tombstones ---
# DropBox only stores the ANR summary; the full trace often names the blocked
# thread and the lock holder.
if [ -d /data/anr ]; then
    ls -1t /data/anr/* 2>/dev/null | head -n 4 > "$WORK/anr/.list"
    while IFS= read -r trace; do
        [ -f "$trace" ] || continue
        HT_CAP_BYTES=1048576 capture "anr/${trace##*/}" cat "$trace"
    done < "$WORK/anr/.list"
    rm -f "$WORK/anr/.list"
    HT_CAP_BYTES=0
fi
if [ -d /data/tombstones ]; then
    ls -1t /data/tombstones/* 2>/dev/null | grep -v -E "\.pb$" | head -n 4 > "$WORK/tombstones/.list"
    while IFS= read -r stone; do
        [ -f "$stone" ] || continue
        HT_CAP_BYTES=1048576 capture "tombstones/${stone##*/}" cat "$stone"
    done < "$WORK/tombstones/.list"
    rm -f "$WORK/tombstones/.list"
    HT_CAP_BYTES=0
fi

# ----------------------------------------------------------- boot verdict ----
# The previous archive left "did the kernel panic?" as an open question. Answer
# it from the collected evidence and name the sources that were scanned.
printf '%s\n' "[HyperTweak] Classifying the previous boot..."
{
    echo "=== Boot evidence (sources may belong to different boots) ==="
    echo "Scanned sources:"
    for src in "$WORK"/pstore/* "$WORK/last_kmsg.txt" "$WORK/dmesg.txt" "$WORK"/logd/*.log; do
        [ -f "$src" ] || continue
        case "$src" in *NO_RECORDS.txt) continue ;; esac
        echo "  $src ($(wc -c < "$src") bytes)"
    done
    echo

    PANIC="Kernel panic|not syncing|Unable to handle kernel|Internal error: Oops|BUG: unable to handle|kernel BUG at|watchdog bite|watchdog: BUG|hard lockup|soft lockup|Synchronous External Abort|Fatal exception in interrupt|SysRq : Trigger a crash"
    ORDERLY="reboot: Restarting system with command|Reboot ending, jumping to kernel|reboot reason:|VolumeManager shutdown successed|Ready to unmount apexes|powerctl_shutdown_time_ms"

    PANIC_HITS=0
    ORDERLY_HITS=0
    for src in "$WORK"/pstore/* "$WORK/last_kmsg.txt" "$WORK/dmesg.txt" "$WORK"/logd/*.log; do
        [ -f "$src" ] || continue
        case "$src" in *NO_RECORDS.txt) continue ;; esac
        n=$(grep -a -c -E "$PANIC" "$src" 2>/dev/null)
        case "$n" in ''|*[!0-9]*) n=0 ;; esac
        PANIC_HITS=$((PANIC_HITS + n))
        n=$(grep -a -c -E "$ORDERLY" "$src" 2>/dev/null)
        case "$n" in ''|*[!0-9]*) n=0 ;; esac
        ORDERLY_HITS=$((ORDERLY_HITS + n))
    done

    echo "panic_signature_lines=$PANIC_HITS"
    echo "orderly_shutdown_lines=$ORDERLY_HITS"
    if [ "$PANIC_HITS" -gt 0 ]; then
        echo "verdict=PANIC-LIKE SIGNATURE PRESENT; review source boot and matched lines"
    elif [ "$ORDERLY_HITS" -gt 0 ]; then
        echo "verdict=ORDERLY SHUTDOWN SIGNATURE PRESENT; boot attribution requires review"
    else
        echo "verdict=INCONCLUSIVE (no shutdown data captured; pstore may be empty)"
    fi
    echo

    echo "=== Matched panic signatures (bounded) ==="
    for src in "$WORK"/pstore/* "$WORK/last_kmsg.txt" "$WORK/dmesg.txt" "$WORK"/logd/*.log; do
        [ -f "$src" ] || continue
        case "$src" in *NO_RECORDS.txt) continue ;; esac
        grep -a -n -m 20 -E "$PANIC" "$src" 2>/dev/null | cut -c1-300 | sed "s|^|${src##*/}: |"
    done
    echo

    echo "=== Matched orderly-shutdown markers (bounded) ==="
    for src in "$WORK"/pstore/* "$WORK/last_kmsg.txt" "$WORK"/logd/*.log; do
        [ -f "$src" ] || continue
        case "$src" in *NO_RECORDS.txt) continue ;; esac
        grep -a -n -m 15 -E "$ORDERLY" "$src" 2>/dev/null | cut -c1-300 | sed "s|^|${src##*/}: |"
    done
    echo

    echo "=== Reboot reason lines ==="
    for src in "$WORK"/pstore/* "$WORK/last_kmsg.txt" "$WORK"/logd/*.log; do
        [ -f "$src" ] || continue
        case "$src" in *NO_RECORDS.txt) continue ;; esac
        grep -a -h -E "reboot reason:|Restarting system with command" "$src" 2>/dev/null | cut -c1-300
    done | sort -u | head -n 20
    echo

    echo "=== Shutdown initiator (who asked the framework to reboot) ==="
    for src in "$WORK"/pstore/* "$WORK"/logd/*.log; do
        [ -f "$src" ] || continue
        case "$src" in *NO_RECORDS.txt) continue ;; esac
        grep -a -h -E "Binder shutdown checkpoint recorded|Unexpected reboot or shutdown reason|Reboot reason:|shutdown reason =" "$src" 2>/dev/null | cut -c1-300
    done | sort -u | head -n 20
    echo

    echo "=== Previous boot kernel uptime at shutdown ==="
    for src in "$WORK"/pstore/* "$WORK"/logd/*.log; do
        [ -f "$src" ] || continue
        case "$src" in *NO_RECORDS.txt) continue ;; esac
        grep -a -o -E "^\[ *[0-9]+\.[0-9]+\]" "$src" 2>/dev/null | tail -n 1
    done | sort -u | head -n 5
} > "$WORK/boot-verdict.txt" 2>&1

if ! command -v timeout >/dev/null 2>&1; then
    echo "timeout unavailable: command deadlines could not be enforced." >> "$WORK/extra-status.txt"
fi

# Facts only. The collector never prompts and never waits for input, so it can
# run unattended from a root shell and exit on its own.
{
    echo "=== Incident facts (auto-collected) ==="
    printf "Reboot reason reported by the system: %s\n" "$(getprop sys.boot.reason 2>/dev/null)"
    printf "Bootloader reason: %s\n" "$(getprop ro.boot.bootreason 2>/dev/null)"
    printf "Reboot reason history: %s\n" "$(getprop persist.sys.boot.reason.history 2>/dev/null)"
    printf "Current uptime: %s\n" "$(uptime 2>/dev/null)"
    printf "Current time: %s\n" "$(date 2>/dev/null)"
    printf "HyperTweak installed version: %s\n" "$(dumpsys package com.takekazex.hypertweak 2>/dev/null | grep -m 1 versionName | tr -s " " | sed "s/^ //")"
    printf "Launcher version: %s\n" "$(dumpsys package com.miui.home 2>/dev/null | grep -m 1 versionName | tr -s " " | sed "s/^ //")"
    printf "Foreground Android user: %s\n" "$ANDROID_USER"
} > "$WORK/incident-facts.txt" 2>&1

# A manifest makes gaps visible instead of looking like a clean collection.
{
    echo "=== Collected files ==="
    find "$WORK" -type f 2>/dev/null | sed "s|^$WORK/||" | sort
    echo
    echo "=== Failed captures ==="
    grep -E "exit=[1-9]" "$WORK/extra-status.txt" 2>/dev/null || echo "none"
    echo
    echo "=== Total size before packaging ==="
    du -sk "$WORK" 2>/dev/null
} > "$WORK/manifest.txt" 2>&1

printf '%s\n' "[HyperTweak] Packaging..."
{
    printf '{"schema":1,"collector":3,"based_on":2,"scope":"system","boot_verdict":"evidence_only","command_status_file":"extra-status.txt"}\n'
} > "$WORK/manifest.json"
# Refuse oversized collections before compression; leave the failure visible to the runner.
SIZE_KB=$(du -sk "$WORK" | cut -f1)
case "$SIZE_KB" in ''|*[!0-9]*) exit 68 ;; esac
[ "$SIZE_KB" -le 163840 ] || { echo 'Collection exceeds 160 MiB'; exit 68; }
if ! tar -czf "$ARCHIVE" -C "$WORK" .; then
    echo "[ERROR] Archive creation failed. Diagnostic files retained at:"
    echo "$WORK"
    exit 1
fi

# Confirm the archive is present, non-empty, and readable before cleanup.
if [ ! -s "$ARCHIVE" ] || ! tar -tzf "$ARCHIVE" >/dev/null 2>&1; then
    echo "[ERROR] Archive is missing or empty."
    echo "Temporary diagnostics: $WORK"
    exit 1
fi

if [ -n "$APP_UID" ]; then
    chown "$APP_UID:$APP_UID" "$ARCHIVE" || exit 67
    chmod 600 "$ARCHIVE" || exit 67
fi
rm -rf "$WORK"
echo
printf '%s\n' "====================================================="
printf '%s\n' "[HyperTweak] Diagnostic archive created successfully!"
printf '%s\n' "$ARCHIVE"
printf '%s\n' "Review for private information before sending."
printf '%s\n' "====================================================="
# Exit explicitly: a root shell wrapper must not be left waiting on this script.
exit 0
