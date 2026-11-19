#!/system/bin/sh
# This script runs as root. Its directory, policy and runtime state are root-owned.
set -f
umask 077
export LC_ALL=C
cd /data/adb/hypertweak-wireless-adb || exit 1

runtime=/dev/hypertweak-wireless-adb
endpoint_file=$runtime/endpoint
chain=HT_WIRELESS_ADB
listen_addrs=service.adb.listen_addrs
property=service.adb.tcp.port
persistent=persist.adb.tcp.port

is_port() {
    case "$1" in ''|*[!0-9]*|??????*) return 1;; esac
    [ "$1" -ge 1 ] && [ "$1" -le 65535 ]
}

# Every external command costs a fork and exec (about 12-15 ms of CPU here), so the periodic
# path parses with shell builtins and spawns only `cmd`. Pass `verify` to also confirm, with
# `ip`, that the interface holds a single matching IPv4 address; done before opening a listener.
read_wifi() {
    wifi_ssid=
    wifi_bssid=
    wifi_iface=
    wifi_ip=
    wifi_disconnected=0
    status=$(timeout 1 cmd wifi status) || return 1
    # Root can see multiple STA connections. Accept only one completed, unredacted pair.
    connected=0
    infos=0
    disconnected=0
    ssid=
    mac=
    ip=
    saved_ifs=$IFS
    IFS='
'
    for line in $status; do
        case "$line" in
            'Wifi is disabled'|'Wifi is not connected') disconnected=1;;
            'Wifi is connected to '*) connected=$((connected + 1)); ssid=${line#Wifi is connected to };;
            'WifiInfo: SSID: '*)
                infos=$((infos + 1))
                prefix="WifiInfo: SSID: $ssid, BSSID: "
                case "$line" in
                    "$prefix"*)
                        rest=${line#"$prefix"}
                        mac=${rest%%, *}
                        case "$rest" in *', Supplicant state: COMPLETED, '*) ;; *) mac=;; esac
                        case "$rest" in *', IP: /'*) ip=${rest#*, IP: /}; ip=${ip%%,*};; esac
                        ;;
                esac
                ;;
        esac
    done
    IFS=$saved_ifs
    if [ "$disconnected" = 1 ] && [ "$connected" = 0 ]; then wifi_disconnected=1; return 1; fi
    [ "$connected" = 1 ] && [ "$infos" = 1 ] && [ "${#ssid}" -le 66 ] || return 1
    case "$ssid" in
        '"'?*'"') ;;
        0x?*) case "${ssid#0x}" in *[!0-9a-fA-F]*) return 1;; esac;;
        *) return 1;;
    esac
    typeset -l lower=$mac
    case "$lower" in
        [0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]) ;;
        *) return 1;;
    esac
    case "$lower" in 02:00:00:00:00:00|00:00:00:00:00:00) return 1;; esac
    wifi_ssid=$ssid
    wifi_bssid=$lower
    case "$ip" in ''|*[!0-9.]*) return 0;; esac
    [ -n "$wifi_interface" ] || wifi_interface=$(getprop wifi.interface)
    iface=${wifi_interface:-wlan0}
    case "${iface#wlan}" in ''|*[!0-9]*) return 0;; esac
    [ "$iface" != "${iface#wlan}" ] || return 0
    if [ "$1" = verify ]; then
        # Avoid exposing the listener without a unique Wi-Fi IPv4 address.
        address=$(ip -4 -o addr show dev "$iface" scope global up 2>/dev/null |
            awk 'NR == 1 {split($4, cidr, "/"); print cidr[1]} END {if (NR != 1) exit 1}') || return 0
        [ "$address" = "$ip" ] || return 0
    fi
    # Unverified addresses are only compared with a running relay, which itself exits as soon
    # as its address leaves the interface.
    wifi_iface=$iface
    wifi_ip=$ip
}

# policy: mode, requested port, then up to $max_bound "ssid" / "bssid" line pairs. The previous
# single-binding format (one pair, possibly empty) reads unchanged.
max_bound=3

read_policy() {
    mode=0
    requested=0
    bound_count=0
    unset bound_ssids bound_bssids
    [ -f policy ] || return 0
    {
        if IFS= read -r mode && IFS= read -r requested; then
            while [ "$bound_count" -lt "$max_bound" ] && IFS= read -r pair_ssid && IFS= read -r pair_bssid; do
                [ -n "$pair_bssid" ] || continue
                bound_ssids[bound_count]=$pair_ssid
                bound_bssids[bound_count]=$pair_bssid
                bound_count=$((bound_count + 1))
            done
        else
            mode=0
        fi
    } < policy
    case "$mode" in 0|1|2) ;; *) mode=0;; esac
    if [ "$mode" = 1 ] && [ "$bound_count" = 0 ]; then mode=0; fi
}

# Sets bound_index to the binding that equals the given SSID and BSSID.
find_bound() {
    bound_index=0
    while [ "$bound_index" -lt "$bound_count" ]; do
        if [ -n "$2" ] && [ "${bound_ssids[bound_index]}" = "$1" ] &&
            [ "${bound_bssids[bound_index]}" = "$2" ]; then
            return 0
        fi
        bound_index=$((bound_index + 1))
    done
    return 1
}

owner_valid() {
    [ -f owner ] || return 1
    {
        IFS= read -r owner_path && IFS= read -r owner_token
    } < owner || return 1
    [ -n "$owner_token" ] && [ -f "$owner_path" ] || return 1
    # The token file has no trailing newline: `read` then reports EOF but still fills the variable.
    owner_check=
    IFS= read -r owner_check < "$owner_path"
    [ "$owner_check" = "$owner_token" ]
}

same_wifi() {
    [ -n "$2" ] && [ "$wifi_ssid" = "$1" ] && [ "$wifi_bssid" = "$2" ]
}

select_target() {
    target_ssid=
    target_bssid=
    target_port=$requested
    if [ -f manual ]; then
        {
            IFS= read -r target_ssid &&
            IFS= read -r target_bssid &&
            IFS= read -r target_port
        } < manual
    elif [ "$mode" = 0 ] || [ -f paused ]; then
        target_bssid=
    elif [ "$mode" = 1 ]; then
        # The access point in use is the target when it is any of the bound ones.
        if find_bound "$wifi_ssid" "$wifi_bssid"; then
            target_ssid=$wifi_ssid
            target_bssid=$wifi_bssid
        fi
    elif [ "$mode" = 2 ]; then
        target_ssid=$wifi_ssid
        target_bssid=$wifi_bssid
        target_port=0
    fi
}

matches() {
    [ -n "$wifi_ip" ] && same_wifi "$target_ssid" "$target_bssid"
}

read_transport() {
    transport=legacy
    if [ -f transport ]; then IFS= read -r transport < transport; fi
}

backend_listening() {
    sockets=$(ss -H -xlnp) || return 1
    printf '%s\n' "$sockets" | awk -v name="@$backend" '
        index($0, "\"adbd\"") { for (i = 1; i <= NF; i++) if ($i == name) found=1 }
        END { exit !found }'
}

read_tls_port() {
    tls_port=$(getprop service.adb.tls.port)
    if ! is_port "$tls_port"; then
        # Some builds keep this in the framework's ADB service instead of the property.
        tls_port=$(timeout 1 dumpsys adb | awk '
            /^[[:space:]]*tls_port=[0-9]+[[:space:]]*$/ {
                sub(/^[[:space:]]*tls_port=/, ""); sub(/[[:space:]]+$/, ""); port=$0; count++
            }
            END { if (count == 1) print port }')
    fi
    is_port "$tls_port"
}

backend_is_private() {
    backend_listening || return 1
    sockets=$(ss -H -ltnp) || return 1
    tls_port=
    if [ "$(getprop persist.adb.tls_server.enable)" = 1 ]; then
        if ! read_tls_port; then tls_port=; fi
    fi
    printf '%s\n' "$sockets" | awk -v tls="$tls_port" '
        index($0, "\"adbd\"") && (tls == "" || $4 !~ (":" tls "$")) { exposed=1 }
        END { exit exposed }'
}

wait_for_daemon() {
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        [ "$(getprop init.svc.adbd)" = "$1" ] && return 0
        sleep 0.2
    done
    return 1
}

ensure_private_backend() {
    [ "$(getprop ro.adb.secure)" = 1 ] || return 1
    addresses=$(getprop "$listen_addrs")
    backend=
    if [ -f backend ]; then IFS= read -r backend < backend; fi
    if [ -n "$addresses" ] && [ "$addresses" != "localabstract:$backend" ]; then return 1; fi
    if [ -n "$backend" ] && [ "$addresses" = "localabstract:$backend" ] && backend_is_private; then return 0; fi
    if [ -z "$backend" ]; then
        id=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n') || return 1
        [ "${#id}" = 16 ] || return 1
        backend=hypertweak-adbd-$id
        printf '%s\n' "$backend" > backend || return 1
    fi
    # Wait for the old process to exit before clearing its TCP properties or firewall.
    # The explicit Unix socket also prevents adbd's no-USB TCP/5555 fallback.
    setprop ctl.stop adbd && wait_for_daemon stopped || return 1
    setprop "$listen_addrs" "localabstract:$backend" &&
        setprop "$property" 0 && setprop "$persistent" '' &&
        setprop ctl.start adbd || return 1
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        backend_is_private && return 0
        sleep 0.2
    done
    setprop ctl.stop adbd && wait_for_daemon stopped
    return 1
}

revoke_relay() {
    if [ -f "$endpoint_file" ]; then
        read -r relay_ip relay_port lease_id < "$endpoint_file"
        case "$lease_id" in
            ''|*[!0-9a-f]*) return 1;;
        esac
        # Any invocation may revoke. Only the worker holds the liveness pipe, so stop the relay
        # by pid; it also exits on its own when the worker goes away.
        if relay_running; then kill "$relay_pid" 2>/dev/null; fi
        for attempt in 1 2 3 4 5 6 7 8 9 10; do
            relay_running || break
            sleep 0.1
        done
        if relay_running; then kill -9 "$relay_pid" 2>/dev/null; sleep 0.1; fi
        relay_running && return 1
        # .lease is left by builds that renewed a timed lease.
        rm -f "$runtime/$lease_id.lease" "$runtime/$lease_id.alive" "$runtime/$lease_id.ready" "$endpoint_file"
        log_event "closed ${close_reason:-revoked} port=$relay_port"
    fi
}

# Bounded event log on tmpfs; status reports the newest entry.
log_event() {
    read -r uptime remainder < /proc/uptime 2>/dev/null || uptime=0
    printf '%s %s\n' "${uptime%%.*}" "$*" >> "$runtime/events" 2>/dev/null || return 0
    lines=$(wc -l < "$runtime/events" 2>/dev/null) || return 0
    if [ "$lines" -gt 64 ]; then
        tail -n 32 "$runtime/events" > "$runtime/events.new" && mv -f "$runtime/events.new" "$runtime/events"
    fi
}

close_listener() {
    revoke_relay || return 1
    configured=$(getprop "$property")
    saved=$(getprop "$persistent")
    if is_port "$configured" || is_port "$saved" || [ -f guarded ]; then
        ensure_private_backend || return 1
        # Old wildcard transport is gone. Retire only our old chain.
        for family in iptables ip6tables; do
            if "$family" -w 1 -S "$chain" >/dev/null 2>&1; then
                "$family" -w 1 -D INPUT -j "$chain" 2>/dev/null
                "$family" -w 1 -F "$chain" && "$family" -w 1 -X "$chain" || return 1
            fi
        done
        rm -f endpoint guarded
    fi
}

port_free() {
    is_port "$1" || return 1
    sockets=$(ss -H -ltn "sport = :$1") || return 1
    [ -z "$sockets" ]
}

# An automatic port is chosen once per access point and reused while it stays free, so a
# reopened listener keeps the address the host already uses.
remembered_port() {
    [ -f ports ] || return 1
    remembered=$(awk -v bssid="$target_bssid" '$1 == bssid { port = $2 } END { if (port == "") exit 1; print port }' ports) || return 1
    is_port "$remembered"
}

remember_port() {
    [ "$target_port" = 0 ] && [ -n "$target_bssid" ] || return 0
    { [ -f ports ] && awk -v bssid="$target_bssid" '$1 != bssid' ports | tail -n 15; printf '%s %s\n' "$target_bssid" "$port"; } > ports.new &&
        mv -f ports.new ports
}

pick_port() {
    if [ "$target_port" = 0 ] && remembered_port && port_free "$remembered"; then
        port=$remembered
        return 0
    fi
    for attempt in 1 2 3 4 5 6 7 8; do
        port=$target_port
        if [ "$port" = 0 ]; then
            number=$(od -An -N2 -tu2 /dev/urandom) || return 1
            port=$((49152 + number % 16384))
        fi
        port_free "$port" && return 0
        [ "$target_port" = 0 ] || break
    done
    return 1
}

relay_ready() {
    case "$lease_id" in ''|*[!0-9a-f]*) return 1;; esac
    relay_running
}

# Periodic liveness without spawning: the relay removes its ready file on a clean exit, and a
# killed relay leaves a pid that `kill -0` (a builtin) rejects. Revocation keeps the strict check.
relay_alive() {
    case "$lease_id" in ''|*[!0-9a-f]*) return 1;; esac
    read -r relay_pid 2>/dev/null < "$runtime/$lease_id.ready" || return 1
    case "$relay_pid" in ''|*[!0-9]*) return 1;; esac
    kill -0 "$relay_pid" 2>/dev/null
}

relay_running() {
    read -r relay_pid 2>/dev/null < "$runtime/$lease_id.ready" || return 1
    case "$relay_pid" in ''|*[!0-9]*) return 1;; esac
    # A recycled pid must never be treated as, or killed as, the relay.
    tr '\0' ' ' < "/proc/$relay_pid/cmdline" 2>/dev/null | grep -q ' com.takekazex.hypertweak.root.WirelessAdbRelay '
}

open_listener() {
    expected_iface=$wifi_iface
    expected_ip=$wifi_ip
    ensure_private_backend || return 1
    read_wifi verify && matches && [ "$wifi_iface" = "$expected_iface" ] &&
        [ "$wifi_ip" = "$expected_ip" ] || return 1
    IFS= read -r apk < apk || return 1
    [ -r "$apk" ] || return 1
    lease_id=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n') || return 1
    [ "${#lease_id}" = 16 ] || return 1
    {
        printf '%s %s %s\n' "$wifi_ip" "$port" "$lease_id"
        printf '%s\n' "$target_ssid" "$target_bssid"
    } > "$endpoint_file.new" && mv -f "$endpoint_file.new" "$endpoint_file" || return 1
    # The worker keeps the only writer of this pipe on fd 5. mksh opens persistent descriptors
    # close-on-exec, so no child inherits it; the relay sees hang-up as soon as the worker exits,
    # is killed or re-executes itself. Unlike a timed lease this does not expire during suspend.
    alive=$runtime/$lease_id.alive
    rm -f "$alive" && mkfifo -m 600 "$alive" && exec 5<>"$alive" || return 1
    CLASSPATH="$apk" nohup setsid app_process /system/bin \
        com.takekazex.hypertweak.root.WirelessAdbRelay "$wifi_iface" "$wifi_ip" "$port" "$backend" \
        "$alive" "$runtime/$lease_id.ready" "$PWD/wake" </dev/null >/dev/null 2>&1 9>&- &
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        if relay_ready; then
            remember_port
            log_event "opened $wifi_ip:$port"
            return 0
        fi
        sleep 0.2
    done
    return 1
}

read_endpoint() {
    {
        read -r live_ip live_port lease_id &&
        IFS= read -r live_ssid &&
        IFS= read -r live_bssid
    } < "$endpoint_file" && same_wifi "$live_ssid" "$live_bssid"
}

tick() {
    read_wifi
    # Unknown Wi-Fi (query timeout, reassociation, IP not yet confirmed right after resume) keeps
    # the current state for a bounded number of awake ticks. A disconnect or another access point
    # is acted on at once.
    if [ "$wifi_disconnected" != 1 ] && { [ -z "$wifi_bssid" ] || [ -z "$wifi_ip" ]; } &&
        { [ -f "$endpoint_file" ] || [ -f manual ]; }; then
        unsure=$((unsure + 1))
        if [ "$unsure" -lt "$unsure_limit" ]; then return 0; fi
        close_reason=wifi-unknown
    else
        unsure=0
        close_reason=
    fi
    if [ -f paused ]; then
        {
            IFS= read -r paused_ssid && IFS= read -r paused_bssid
        } < paused || { paused_ssid=; paused_bssid=; }
        if [ "$wifi_disconnected" = 1 ] ||
            { [ -n "$wifi_bssid" ] && ! same_wifi "$paused_ssid" "$paused_bssid"; }; then
            rm -f paused
        fi
    fi
    select_target
    if [ -f manual ] && ! matches; then
        rm -f manual
        select_target
    fi
    if ! matches || [ "$target_port" = -1 ]; then
        if [ -f "$endpoint_file" ] || [ -f guarded ] || is_port "$(getprop "$property")" || is_port "$(getprop "$persistent")"; then
            if [ -z "$close_reason" ]; then
                if [ "$wifi_disconnected" = 1 ]; then close_reason=wifi-disconnected
                elif [ -n "$wifi_bssid" ] && ! same_wifi "$target_ssid" "$target_bssid"; then close_reason=other-wifi
                else close_reason=policy; fi
            fi
            close_listener || return 1
        fi
        return 0
    fi
    if [ -f "$endpoint_file" ] && read_endpoint; then
        if [ "$live_ip" = "$wifi_ip" ] && is_port "$live_port" && relay_alive; then
            return 0
        fi
        # Same access point but the relay stopped (address change or crash). The remembered
        # automatic port brings it back on the same port.
        close_reason=relay-restart
    fi
    # Preserve the address of an already verified session when replacing the old wildcard backend.
    migration_port=
    if [ -f guarded ] && [ -f endpoint ]; then
        {
            read -r old_ip old_port old_expiry &&
            IFS= read -r old_ssid &&
            IFS= read -r old_bssid
        } < endpoint
        if [ "$old_ip" = "$wifi_ip" ] && same_wifi "$old_ssid" "$old_bssid" && is_port "$old_port"; then
            migration_port=$old_port
        fi
    fi
    close_listener || return 1
    target_port=${migration_port:-$target_port}
    if ! pick_port; then return 2; fi
    if ! open_listener; then
        revoke_relay
        return 1
    fi
}

start_worker() {
    [ -p wake ] || mkfifo wake || return 1
    if flock -n 7 7>worker.lock; then
        nohup setsid sh ./guard watch </dev/null >/dev/null 2>&1 9>&- &
    fi
    # Open both ends so a command never blocks when there is no worker yet.
    wake_worker
}

wake_worker() {
    if [ -p wake ]; then printf '\n' 6<>wake >&6; fi
}

write_policy() {
    {
        printf '%s\n' "$mode" "$requested"
        index=0
        while [ "$index" -lt "$bound_count" ]; do
            printf '%s\n' "${bound_ssids[index]}" "${bound_bssids[index]}"
            index=$((index + 1))
        done
    } > policy.new && mv -f policy.new policy
}

valid_requested() {
    [ "$1" = 0 ] || [ "$1" = -1 ] || is_port "$1"
}

# Android mksh marks persistent descriptors close-on-exec. Pass a duplicate to toybox flock.
mkdir -p "$runtime" && chmod 700 "$runtime" || exit 1
exec 9>operation.lock || exit 1
case "$1" in
    watch)
        exec 8>worker.lock || exit 1
        flock -n 7 7>&8 || exit 0
        # fd 4 pins the script this worker runs; -ef against it detects a replaced file without
        # spawning stat on every tick.
        exec 4< ./guard || exit 1
        [ -p wake ] || exit 1
        exec 6<>wake || exit 1
        trap 'flock 7 7>&9 && { close_reason=worker-stopped; close_listener; }; exit' INT TERM
        unsure=0
        # Awake ticks of unknown Wi-Fi tolerated (1 s apart): about 20 s of real awake time.
        unsure_limit=20
        while :; do
            flock 7 7>&9 || exit 1
            if ! [ ./guard -ef "/proc/$$/fd/4" ]; then
                # An APK update atomically replaced the worker. Hand off the locks and reload it.
                flock -u 7 7>&8 && flock -u 7 7>&9 || exit 1
                exec sh ./guard watch
            fi
            read_policy
            if ! owner_valid; then
                rm -f policy manual paused transport
                mode=0
            fi
            read_transport
            if [ "$transport" != legacy ] || { [ "$mode" = 0 ] && [ ! -f manual ]; }; then
                if close_listener; then
                    rm -f failure
                    flock -u 7 7>&8
                    exit 0
                fi
                result=1
            else
                tick
                result=$?
            fi
            if [ "$result" = 0 ]; then rm -f failure; else printf '%s\n' "$result" > failure; fi
            # An open relay exits by itself when its address leaves the interface or the worker
            # goes away, and wakes the worker when it does, so a steady listener only needs a
            # slow access point check.
            interval=10
            if [ -f "$endpoint_file" ]; then interval=15; fi
            if [ "$unsure" -gt 0 ] || [ "$result" != 0 ] || [ -f guarded ] ||
                { [ -f manual ] && [ ! -f "$endpoint_file" ]; }; then interval=1; fi
            flock -u 7 7>&9
            read -r -t "$interval" wake_signal <&6
        done
        ;;
    prepare|restore)
        flock 7 7>&9 || exit 1
        [ -n "$3" ] && [ "$(cat "$2" 2>/dev/null)" = "$3" ] || exit 1
        if ! owner_valid || [ "$2" != "$owner_path" ] || [ "$3" != "$owner_token" ]; then
            rm -f policy manual paused transport
        fi
        printf '%s\n' "$2" "$3" > owner.new && mv -f owner.new owner || exit 1
        read_policy
        valid_requested "$4" || exit 1
        requested=$4
        if [ "$1" = restore ] || [ ! -f policy ]; then
            rm -f manual paused
            write_policy || exit 1
            if ! close_listener; then start_worker; exit 1; fi
        else
            write_policy || exit 1
        fi
        read_transport
        if [ "$transport" = legacy ] && { [ "$mode" != 0 ] || [ -f manual ]; }; then start_worker; fi
        ;;
    transport)
        flock 7 7>&9 || exit 1
        owner_valid || exit 1
        read_transport
        case "$2" in
            system)
                printf '%s\n' system > transport || exit 1
                rm -f manual paused
                if ! close_listener; then start_worker; exit 1; fi
                wake_worker
                ;;
            legacy)
                native_enabled=$(settings get global adb_wifi_enabled) || exit 1
                { [ "$native_enabled" = 0 ] || [ "$native_enabled" = null ]; } &&
                    [ "$(getprop persist.adb.tls_server.enable)" != 1 ] || exit 4
                printf '%s\n' legacy > transport || exit 1
                read_policy
                if [ "$mode" != 0 ]; then start_worker; fi
                ;;
            *) exit 1;;
        esac
        ;;
    bind|mode|manual-on|manual-off|port)
        flock 7 7>&9 || exit 1
        owner_valid || exit 1
        read_transport
        [ "$transport" = legacy ] || exit 1
        read_policy
        case "$1" in
            bind)
                [ -n "$3" ] && [ -n "$4" ] || exit 1
                case "$2" in
                    add)
                        # Only the access point in use can be bound, so the user proves presence.
                        read_wifi && same_wifi "$3" "$4" || exit 3
                        if ! find_bound "$3" "$4"; then
                            [ "$bound_count" -lt "$max_bound" ] || exit 5
                            bound_ssids[bound_count]=$3
                            bound_bssids[bound_count]=$4
                            bound_count=$((bound_count + 1))
                        fi
                        ;;
                    remove)
                        find_bound "$3" "$4" || exit 0
                        while [ $((bound_index + 1)) -lt "$bound_count" ]; do
                            bound_ssids[bound_index]=${bound_ssids[bound_index + 1]}
                            bound_bssids[bound_index]=${bound_bssids[bound_index + 1]}
                            bound_index=$((bound_index + 1))
                        done
                        bound_count=$((bound_count - 1))
                        unset bound_ssids[bound_count] bound_bssids[bound_count]
                        # The worker closes a listener that this binding was keeping open.
                        if [ "$mode" = 1 ] && [ "$bound_count" = 0 ]; then mode=0; fi
                        ;;
                    *) exit 1;;
                esac
                write_policy || exit 1
                rm -f paused failure
                ;;
            mode)
                case "$2" in
                    0|2) ;;
                    1) [ "$bound_count" -gt 0 ] || exit 1 ;;
                    *) exit 1 ;;
                esac
                [ "$mode" != "$2" ] || exit 0
                mode=$2
                write_policy || exit 1
                rm -f paused failure
                if [ "$mode" = 2 ]; then rm -f manual; fi
                if [ ! -f manual ] && ! close_listener; then start_worker; exit 1; fi
                ;;
            manual-on)
                manual_port=$2
                if [ "$mode" = 2 ]; then manual_port=0; fi
                { [ "$manual_port" = 0 ] || is_port "$manual_port"; } || exit 1
                read_wifi && same_wifi "$3" "$4" && [ -n "$wifi_ip" ] || exit 3
                [ -p wake ] || mkfifo wake || exit 1
                close_reason=manual-restart; close_listener || exit 1
                printf '%s\n' "$3" "$4" "$manual_port" > manual || exit 1
                rm -f paused failure
                # Only the long-lived worker may own the relay: it holds the liveness pipe.
                flock -u 7 7>&9 || exit 1
                start_worker
                for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
                    sleep 0.2
                    [ -f manual ] || break
                    [ -f failure ] && break
                    if [ -f "$endpoint_file" ] && read -r live_ip live_port lease_id < "$endpoint_file" && relay_ready; then
                        exit 0
                    fi
                done
                flock 7 7>&9 || exit 1
                if [ -f "$endpoint_file" ] && read -r live_ip live_port lease_id < "$endpoint_file" && relay_ready; then
                    exit 0
                fi
                rm -f manual
                [ -f failure ] || printf '%s\n' 1 > failure
                close_reason=manual-failed; close_listener
                wake_worker
                exit 1
                ;;
            manual-off)
                # Suspend auto-start on this association. A different observed Wi-Fi clears it.
                if [ "$mode" != 0 ]; then
                    if [ -f "$endpoint_file" ] && { read_wifi; read_endpoint; } && [ -n "$wifi_bssid" ]; then
                        printf '%s\n' "$wifi_ssid" "$wifi_bssid" > paused
                    elif [ "$mode" = 1 ] && read_wifi && find_bound "$wifi_ssid" "$wifi_bssid"; then
                        printf '%s\n' "$wifi_ssid" "$wifi_bssid" > paused
                    fi
                fi
                rm -f manual failure
                close_reason=manual-off
                if ! close_listener; then start_worker; exit 1; fi
                ;;
            port)
                valid_requested "$2" || exit 1
                [ "$2" != "$requested" ] || exit 0
                requested=$2
                write_policy || exit 1
                ;;
        esac
        if [ "$mode" != 0 ] || [ -f manual ]; then start_worker; else wake_worker; fi
        ;;
    reset)
        flock 7 7>&9 || exit 1
        rm -f policy owner manual paused transport ports
        close_reason=reset
        close_listener || exit 1
        rm -f failure
        wake_worker
        ;;
    status)
        read_policy
        read_transport
        endpoint=
        if [ "$transport" = legacy ]; then
            read_wifi
            select_target
            if matches && [ -f "$endpoint_file" ] && read_endpoint &&
                [ "$live_ip" = "$wifi_ip" ] && is_port "$live_port" && relay_ready; then
                endpoint="$live_ip:$live_port"
            fi
        elif [ "$(settings get global adb_wifi_enabled)" = 1 ]; then
            if read_tls_port && read_wifi && [ -n "$wifi_ip" ]; then
                tls_socket=$(ss -H -ltnp "sport = :$tls_port") || tls_socket=
                if printf '%s\n' "$tls_socket" | grep -Fq '"adbd"'; then
                    endpoint="$wifi_ip:$tls_port"
                fi
            fi
        fi
        error=$(cat failure 2>/dev/null)
        {
            printf '%s\n' state "$mode" "$bound_count"
            index=0
            while [ "$index" -lt "$bound_count" ]; do
                printf '%s\n' "${bound_ssids[index]}" "${bound_bssids[index]}"
                index=$((index + 1))
            done
            printf '%s\n' "$wifi_ssid" "$wifi_bssid" "$endpoint" "$error" "$transport" end
        }
        ;;
    *) exit 1;;
esac
