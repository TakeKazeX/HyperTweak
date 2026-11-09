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

read_wifi() {
    wifi_ssid=
    wifi_bssid=
    wifi_iface=
    wifi_ip=
    wifi_disconnected=0
    status=$(timeout 1 cmd wifi status) || return 1
    # Root can see multiple STA connections. Accept only one completed, unredacted pair.
    pair=$(printf '%s\n' "$status" | awk '
        /^Wifi is disabled$|^Wifi is not connected$/ { disconnected = 1; next }
        /^Wifi is connected to / { connected++; ssid = substr($0, 22); next }
        /^WifiInfo: SSID: / {
            infos++
            prefix = "WifiInfo: SSID: " ssid ", BSSID: "
            if (index($0, prefix) == 1) {
                rest = substr($0, length(prefix) + 1)
                mac = substr(rest, 1, 17)
                if (substr(rest, 18, 2) != ", " ||
                    rest !~ /, Supplicant state: COMPLETED, /) mac = ""
                if (match(rest, /, IP: \/[0-9.]+, /))
                    ip = substr(rest, RSTART + 7, RLENGTH - 9)
            }
        }
        END {
            if (disconnected && !connected) exit 2
            if (connected != 1 || infos != 1 || length(ssid) > 66 ||
                ssid !~ /^(".+"|0x[0-9a-fA-F]+)$/ ||
                tolower(mac) !~ /^[0-9a-f][0-9a-f](:[0-9a-f][0-9a-f]){5}$/ ||
                mac == "02:00:00:00:00:00" || mac == "00:00:00:00:00:00") exit 1
            print ssid "\n" tolower(mac) "\n" (ip == "" ? "-" : ip)
        }')
    wifi_result=$?
    if [ "$wifi_result" = 2 ]; then wifi_disconnected=1; fi
    [ "$wifi_result" = 0 ] || return 1
    {
        IFS= read -r wifi_ssid && IFS= read -r wifi_bssid && IFS= read -r reported_ip
    } <<PAIR
$pair
PAIR
    iface=$(getprop wifi.interface)
    iface=${iface:-wlan0}
    case "${iface#wlan}" in ''|*[!0-9]*) return 0;; esac
    [ "$iface" != "${iface#wlan}" ] || return 0
    # Avoid exposing the listener without a unique Wi-Fi IPv4 address.
    address=$(ip -4 -o addr show dev "$iface" scope global up 2>/dev/null |
        awk 'NR == 1 {split($4, cidr, "/"); print cidr[1]} END {if (NR != 1) exit 1}') || return 0
    case "$address" in ''|*[!0-9.]*) return 0;; esac
    [ "$address" = "$reported_ip" ] || return 0
    wifi_iface=$iface
    wifi_ip=$address
}

read_policy() {
    mode=0
    requested=0
    bound_ssid=
    bound_bssid=
    [ -f policy ] || return 0
    {
        IFS= read -r mode &&
        IFS= read -r requested &&
        IFS= read -r bound_ssid &&
        IFS= read -r bound_bssid
    } < policy || mode=0
    case "$mode" in 0|1|2) ;; *) mode=0;; esac
}

owner_valid() {
    [ -f owner ] || return 1
    {
        IFS= read -r owner_path && IFS= read -r owner_token
    } < owner || return 1
    [ -n "$owner_token" ] && [ "$(cat "$owner_path" 2>/dev/null)" = "$owner_token" ]
}

same_wifi() {
    [ -n "$2" ] && [ "$wifi_ssid" = "$1" ] && [ "$wifi_bssid" = "$2" ]
}

select_target() {
    target_ssid=$bound_ssid
    target_bssid=$bound_bssid
    target_port=$requested
    if [ -f manual ]; then
        {
            IFS= read -r target_ssid &&
            IFS= read -r target_bssid &&
            IFS= read -r target_port
        } < manual
    elif [ "$mode" = 0 ] || [ -f paused ]; then
        target_bssid=
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
        rm -f "$runtime/$lease_id.lease" || return 1
        for attempt in 1 2 3 4 5; do
            relay_running || break
            sleep 0.15
        done
        relay_running && return 1
        rm -f "$runtime/$lease_id.ready" "$endpoint_file"
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

pick_port() {
    for attempt in 1 2 3 4 5 6 7 8; do
        port=$target_port
        if [ "$port" = 0 ]; then
            number=$(od -An -N2 -tu2 /dev/urandom) || return 1
            port=$((49152 + number % 16384))
        fi
        is_port "$port" || return 1
        sockets=$(ss -H -ltn "sport = :$port") || return 1
        [ -z "$sockets" ] && return 0
        [ "$target_port" = 0 ] || break
    done
    return 1
}

renew_access() {
    read -r uptime remainder < /proc/uptime || return 1
    # /proc/uptime and Android elapsedRealtime both use boot time, including suspend.
    whole=${uptime%%.*}
    fraction=${uptime#*.}
    case "$whole:$fraction" in *[!0-9:]*) return 1;; esac
    [ -n "$whole" ] && [ "${#fraction}" = 2 ] || return 1
    millis=$((whole * 1000 + (1$fraction - 100) * 10))
    printf '%s\n' "$((millis + 4000))" > "$runtime/$lease_id.new" &&
        mv -f "$runtime/$lease_id.new" "$runtime/$lease_id.lease"
}

relay_ready() {
    case "$lease_id" in ''|*[!0-9a-f]*) return 1;; esac
    [ -f "$runtime/$lease_id.lease" ] && relay_running
}

relay_running() {
    read -r relay_pid < "$runtime/$lease_id.ready" || return 1
    case "$relay_pid" in ''|*[!0-9]*) return 1;; esac
    kill -0 "$relay_pid" 2>/dev/null
}

open_listener() {
    expected_iface=$wifi_iface
    expected_ip=$wifi_ip
    ensure_private_backend || return 1
    read_wifi && matches && [ "$wifi_iface" = "$expected_iface" ] &&
        [ "$wifi_ip" = "$expected_ip" ] || return 1
    IFS= read -r apk < apk || return 1
    [ -r "$apk" ] || return 1
    lease_id=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n') || return 1
    [ "${#lease_id}" = 16 ] || return 1
    {
        printf '%s %s %s\n' "$wifi_ip" "$port" "$lease_id"
        printf '%s\n' "$target_ssid" "$target_bssid"
    } > "$endpoint_file.new" && mv -f "$endpoint_file.new" "$endpoint_file" || return 1
    renew_access || return 1
    CLASSPATH="$apk" nohup setsid app_process /system/bin \
        com.takekazex.hypertweak.root.WirelessAdbRelay "$wifi_iface" "$wifi_ip" "$port" "$backend" \
        "$runtime/$lease_id.lease" "$runtime/$lease_id.ready" </dev/null >/dev/null 2>&1 9>&- &
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        relay_ready && return 0
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
    if [ -f paused ]; then
        {
            IFS= read -r paused_ssid && IFS= read -r paused_bssid
        } < paused || { paused_ssid=$bound_ssid; paused_bssid=$bound_bssid; }
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
            close_listener || return 1
        fi
        return 0
    fi
    if [ -f "$endpoint_file" ] && read_endpoint; then
        if [ "$live_ip" = "$wifi_ip" ] && is_port "$live_port" && relay_ready && renew_access; then
            return 0
        fi
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
    printf '%s\n' "$mode" "$requested" "$bound_ssid" "$bound_bssid" > policy.new &&
        mv -f policy.new policy
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
        image=$(stat -c %i ./guard) || exit 1
        [ -p wake ] || exit 1
        exec 6<>wake || exit 1
        trap 'flock 7 7>&9 && close_listener; exit' INT TERM
        while :; do
            flock 7 7>&9 || exit 1
            if [ "$(stat -c %i ./guard)" != "$image" ]; then
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
            interval=10
            if [ -f "$endpoint_file" ] || [ -f guarded ] || [ -f manual ] || [ "$mode" = 0 ]; then interval=1; fi
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
                previous_mode=$mode
                valid_requested "$2" || exit 1
                if [ -n "$3$4" ]; then
                    read_wifi && same_wifi "$3" "$4" || exit 3
                elif [ "$mode" = 1 ]; then
                    mode=0
                fi
                requested=$2
                bound_ssid=$3
                bound_bssid=$4
                write_policy || exit 1
                rm -f paused failure
                if [ "$previous_mode" = 1 ] && [ ! -f manual ] && ! close_listener; then
                    start_worker
                    exit 1
                fi
                ;;
            mode)
                case "$2" in
                    0|2) ;;
                    1) [ -n "$bound_bssid" ] || exit 1 ;;
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
                close_listener || exit 1
                printf '%s\n' "$3" "$4" "$manual_port" > manual || exit 1
                tick
                result=$?
                if [ "$result" != 0 ] || [ ! -f manual ] || [ ! -f "$endpoint_file" ]; then
                    rm -f manual
                    [ "$result" != 0 ] || result=1
                    printf '%s\n' "$result" > failure
                    if [ "$mode" != 0 ] || [ -f guarded ]; then start_worker; fi
                    exit 1
                fi
                rm -f paused failure
                ;;
            manual-off)
                # Suspend auto-start on this association. A different observed Wi-Fi clears it.
                if [ "$mode" != 0 ]; then
                    if [ -f "$endpoint_file" ] && { read_wifi; read_endpoint; } && [ -n "$wifi_bssid" ]; then
                        printf '%s\n' "$wifi_ssid" "$wifi_bssid" > paused
                    elif [ "$mode" = 1 ]; then
                        printf '%s\n' "$bound_ssid" "$bound_bssid" > paused
                    fi
                fi
                rm -f manual failure
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
        rm -f policy owner manual paused transport
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
        printf '%s\n' state "$mode" "$bound_ssid" "$bound_bssid" "$wifi_ssid" "$wifi_bssid" "$endpoint" "$error" "$transport" end
        ;;
    *) exit 1;;
esac
