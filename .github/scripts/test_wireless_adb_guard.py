import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


GUARD = Path(__file__).resolve().parents[2] / "app/src/main/assets/diagnostics/wireless-adb.sh"
SSID = '"Office WiFi"'
BSSID = "12:34:56:78:9a:bc"
IP = "192.0.2.5"

# Run the actual shell state machine with synthetic Wi-Fi, adbd and netfilter.
# A fake timed read exits the worker after one tick and records the polling interval.
BACKEND = r'''
flock() {
    case "$*" in '7'|'-u 7'|'-n 7') return 0;; *) return 1;; esac
}
stat() { echo 1; }
kill() { [ -f "runtime/0011223344556677.lease" ]; }
sleep() { command sleep 0.01; }
nohup() {
    if [ "$2" = app_process ] && [ "$HT_RELAY_SUPPORTED" = 1 ]; then
        printf '%s\n' "$$" > "${10}"
    fi
}
settings() { echo "$HT_SYSTEM_ADB"; }
read() {
    if [ "${2:-}" = -t ]; then
        printf '%s' "$3" > interval
        exit 0
    fi
    command read "$@"
}
timeout() {
    if [ "$2" = dumpsys ]; then printf '    tls_port=%s\n' "$HT_TLS_PORT"; return; fi
    printf '.\n' >> queries
    if [ -f changed ]; then
        cat changed
    else
        cat wifi
        if [ -f next_wifi ]; then mv next_wifi changed; fi
    fi
}
ip() { printf '3: wlan0 inet %s/24 scope global wlan0\n' "$HT_IFACE_IP"; }
getprop() {
    case "$1" in
        wifi.interface) echo wlan0;;
        ro.adb.secure) echo 1;;
        init.svc.adbd) if [ -f daemon-state ]; then cat daemon-state; else echo running; fi;;
        *) if [ -f "$1" ]; then cat "$1"; fi;;
    esac
}
setprop() {
    printf '%s %s\n' "$1" "$2" >> mutations
    case "$1" in
        ctl.stop) printf '%s' stopped > daemon-state;;
        ctl.start) printf '%s' running > daemon-state;;
        *) printf '%s' "$2" > "$1";;
    esac
}
od() {
    case "$*" in
        *-tx1*) echo '00 11 22 33 44 55 66 77';;
        *) echo 7;;
    esac
}
ss() {
    case "$*" in
        "-H -ltnp sport = :$HT_TLS_PORT")
            printf 'LISTEN 0 4 *:%s *:* users:(("adbd",pid=1,fd=5))\n' "$HT_TLS_PORT"
            ;;
        '-H -ltnp')
            if [ "$HT_VENDOR_WILDCARD" = 1 ] && [ "$(getprop init.svc.adbd)" = running ]; then
                printf 'LISTEN 0 4 *:5555 *:* users:(("adbd",pid=1,fd=5))\n'
            fi
            ;;
        '-H -xlnp')
            addresses=$(getprop service.adb.listen_addrs)
            if [ -n "$addresses" ] && [ "$(getprop init.svc.adbd)" = running ]; then
                printf 'u_str LISTEN 0 4 @%s 123 * 0 users:(("adbd",pid=1,fd=5))\n' "${addresses#localabstract:}"
            fi
            ;;
    esac
    return 0
}
iptables() { return 1; }
ip6tables() { return 1; }
. "$HT_GUARD"
'''


def wifi_status(ssid=SSID, bssid=BSSID, ip=IP):
    return (
        "Wifi is enabled\n"
        f"Wifi is connected to {ssid}\n"
        f"WifiInfo: SSID: {ssid}, BSSID: {bssid}, MAC: <none>, IP: /{ip}, "
        "Security type: 4, Supplicant state: COMPLETED, Wi-Fi standard: 6\n"
    )


class WirelessAdbGuardTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        source = GUARD.read_text().replace(
            "/data/adb/hypertweak-wireless-adb", shlex.quote(directory.name), 1
        ).replace("/dev/hypertweak-wireless-adb", shlex.quote(str(self.directory / "runtime")), 1)
        source = source.replace("/proc/uptime", shlex.quote(str(self.directory / "uptime")), 1)
        self.write("guard", source)
        self.write("uptime", "100.00 90.00\n")
        self.write("apk", f"{self.directory / 'guard'}\n")
        os.mkfifo(self.directory / "wake")
        self.write("wifi", wifi_status())
        self.write("policy", f"1\n5037\n{SSID}\n{BSSID}\n")
        self.write("owner-token", "local-test-token")
        self.write("owner", f"{self.directory / 'owner-token'}\nlocal-test-token\n")

    def write(self, name, value):
        (self.directory / name).write_text(value)

    def run_guard(self, operation="watch", *args, **overrides):
        env = {
            **os.environ,
            "HT_GUARD": str(self.directory / "guard"),
            "HT_IFACE_IP": IP,
            "HT_RELAY_SUPPORTED": "1",
            "HT_SYSTEM_ADB": "0",
            "HT_TLS_PORT": "39961",
            "HT_VENDOR_WILDCARD": "0",
            **overrides,
        }
        return subprocess.run(
            ["sh", "-c", BACKEND, "guard-test", operation, *args],
            env=env, capture_output=True, text=True, timeout=5,
        )

    def assert_closed(self):
        self.assertFalse((self.directory / "runtime/endpoint").exists())
        prop = self.directory / "service.adb.tcp.port"
        self.assertIn(prop.read_text() if prop.exists() else "", ("", "0"))

    def test_matching_pair_opens_without_firewall_and_uses_private_adbd(self):
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stderr)
        mutations = (self.directory / "mutations").read_text()
        self.assertIn("service.adb.listen_addrs localabstract:hypertweak-adbd-", mutations)
        self.assertNotIn("service.adb.tcp.port 5037", mutations)
        self.assertLess(mutations.index("ctl.stop adbd"), mutations.index("ctl.start adbd"))
        self.assertEqual(self.run_guard("status").stdout.splitlines()[6], f"{IP}:5037")

    def test_disconnect_or_either_identity_mismatch_closes_listener(self):
        for observed in (wifi_status(bssid="12:34:56:78:9a:bd"),
                         wifi_status(ssid='"Guest"'), "Wifi is not connected\n"):
            with self.subTest(observed=observed):
                self.write("wifi", wifi_status())
                self.assertEqual(self.run_guard().returncode, 0)
                self.assertTrue((self.directory / "runtime/endpoint").exists())
                self.write("wifi", observed)
                self.assertEqual(self.run_guard().returncode, 0)
                self.assert_closed()

    def test_roam_during_start_never_grants_access(self):
        self.write("next_wifi", wifi_status(bssid="12:34:56:78:9a:bd"))
        self.assertEqual(self.run_guard().returncode, 0)
        self.assert_closed()
        self.assertFalse(list((self.directory / "runtime").glob("*.lease")))

    def test_binding_fails_if_wifi_changed_during_authentication(self):
        result = self.run_guard("bind", "5037", '"Guest"', BSSID)
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertEqual((self.directory / "policy").read_text(), f"1\n5037\n{SSID}\n{BSSID}\n")
        self.assert_closed()

    def test_manual_only_session_closes_on_roam_and_does_not_auto_reopen(self):
        self.write("policy", "0\n0\n\n\n")
        result = self.run_guard("manual-on", "5037", SSID, BSSID)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.run_guard("status").stdout.splitlines()[6], f"{IP}:5037")
        self.write("wifi", wifi_status(bssid="12:34:56:78:9a:bd"))
        self.assertEqual(self.run_guard().returncode, 0)
        self.assert_closed()
        self.assertFalse((self.directory / "manual").exists())
        self.write("wifi", wifi_status())
        self.assertEqual(self.run_guard().returncode, 0)
        self.assert_closed()

    def test_manual_off_suspends_auto_until_a_new_connection_is_observed(self):
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertEqual((self.directory / "interval").read_text(), "1")
        self.assertEqual(self.run_guard("manual-off").returncode, 0)
        self.assertEqual(self.run_guard().returncode, 0)
        self.assert_closed()
        self.assertEqual((self.directory / "interval").read_text(), "10")
        self.write("wifi", "Incomplete Wi-Fi status\n")
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertTrue((self.directory / "paused").exists())
        self.write("wifi", "Wifi is not connected\n")
        self.assertEqual(self.run_guard().returncode, 0)
        self.write("wifi", wifi_status())
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertEqual(self.run_guard("status").stdout.splitlines()[6], f"{IP}:5037")

    def test_unbound_idle_worker_exits_without_querying_wifi(self):
        self.write("policy", "0\n0\n\n\n")
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertFalse((self.directory / "interval").exists())
        self.assertFalse((self.directory / "queries").exists())

    def test_always_mode_uses_random_port_and_rechecks_after_roam(self):
        self.write("policy", "0\n5037\n\n\n")
        self.assertEqual(self.run_guard("mode", "2").returncode, 0)
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertEqual(self.run_guard("status").stdout.splitlines()[6], f"{IP}:49159")
        new_bssid = "12:34:56:78:9a:bd"
        self.write("wifi", wifi_status(bssid=new_bssid))
        self.assertEqual(self.run_guard("status").stdout.splitlines()[6], "")
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertEqual((self.directory / "runtime/endpoint").read_text().splitlines()[-1], new_bssid)
        self.assertIn("service.adb.tcp.port 0", (self.directory / "mutations").read_text())
        self.write("wifi", "Wifi is not connected\n")
        self.assertEqual(self.run_guard().returncode, 0)
        self.assert_closed()

    def test_bound_mode_requires_saved_wifi(self):
        self.write("policy", "0\n5037\n\n\n")
        self.assertNotEqual(self.run_guard("mode", "1").returncode, 0)
        self.assertEqual(self.run_guard("bind", "5037", SSID, BSSID).returncode, 0)
        self.assertEqual(self.run_guard().returncode, 0)
        self.assert_closed()
        self.assertEqual(self.run_guard("mode", "1").returncode, 0)
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertEqual(self.run_guard("status").stdout.splitlines()[6], f"{IP}:5037")

    def test_boot_restore_applies_saved_mode(self):
        owner = str(self.directory / "owner-token")
        for mode, expected_port in ((1, 5037), (2, 49159)):
            with self.subTest(mode=mode):
                self.write("policy", f"{mode}\n5037\n{SSID}\n{BSSID}\n")
                self.write("paused", f"{SSID}\n{BSSID}\n")
                result = self.run_guard("restore", owner, "local-test-token", "5037")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.run_guard().returncode, 0)
                fields = self.run_guard("status").stdout.splitlines()
                self.assertEqual(fields[1], str(mode))
                self.assertEqual(fields[6], f"{IP}:{expected_port}")

    def test_package_update_preserves_manual_listener_but_boot_drops_it(self):
        self.write("policy", "0\n5037\n\n\n")
        self.assertEqual(self.run_guard("manual-on", "5037", SSID, BSSID).returncode, 0)
        owner = str(self.directory / "owner-token")
        self.assertEqual(self.run_guard("prepare", owner, "local-test-token", "5037").returncode, 0)
        self.assertEqual(self.run_guard("status").stdout.splitlines()[6], f"{IP}:5037")
        self.assertEqual(self.run_guard("restore", owner, "local-test-token", "5037").returncode, 0)
        self.assert_closed()

    def test_expired_endpoint_is_not_reported(self):
        self.assertEqual(self.run_guard().returncode, 0)
        for lease in (self.directory / "runtime").glob("*.lease"):
            lease.unlink()
        self.assertEqual(self.run_guard("status").stdout.splitlines()[6], "")

    def test_unavailable_relay_fails_closed(self):
        self.assertEqual(self.run_guard(HT_RELAY_SUPPORTED="0").returncode, 0)
        self.assert_closed()
        self.assertEqual((self.directory / "failure").read_text().strip(), "1")

    def test_ambiguous_wifi_or_wrong_interface_ip_fails_closed(self):
        self.write("wifi", wifi_status() + wifi_status(ssid='"Guest"'))
        self.assertEqual(self.run_guard().returncode, 0)
        self.assert_closed()
        self.write("wifi", wifi_status())
        self.assertEqual(self.run_guard(HT_IFACE_IP="192.0.2.6").returncode, 0)
        self.assert_closed()

    def test_removed_app_owner_revokes_existing_listener(self):
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertTrue((self.directory / "runtime/endpoint").exists())
        (self.directory / "owner-token").unlink()
        self.assertEqual(self.run_guard().returncode, 0)
        self.assert_closed()
        self.assertFalse((self.directory / "policy").exists())

    def test_vendor_that_also_opens_default_tcp_is_stopped(self):
        self.assertEqual(self.run_guard(HT_VENDOR_WILDCARD="1").returncode, 0)
        self.assert_closed()
        self.assertEqual((self.directory / "daemon-state").read_text(), "stopped")

    def test_upgrade_reuses_port_of_verified_wildcard_session(self):
        self.write("policy", "0\n0\n\n\n")
        self.write("manual", f"{SSID}\n{BSSID}\n0\n")
        self.write("guarded", "53221\n")
        self.write("endpoint", f"{IP} 53221 1800000000\n{SSID}\n{BSSID}\n")
        self.write("service.adb.tcp.port", "53221")
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertEqual(self.run_guard("status").stdout.splitlines()[6], f"{IP}:53221")
        self.assertEqual((self.directory / "service.adb.tcp.port").read_text(), "0")

    def test_system_transport_disables_legacy_and_preserves_policy(self):
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertEqual(self.run_guard("transport", "system").returncode, 0)
        self.assert_closed()
        self.assertEqual(self.run_guard("status").stdout.splitlines()[8], "system")
        native_status = self.run_guard("status", HT_SYSTEM_ADB="1").stdout.splitlines()
        self.assertEqual(native_status[6], f"{IP}:39961")
        self.assertEqual(self.run_guard().returncode, 0)
        self.assert_closed()
        self.assertNotEqual(self.run_guard("transport", "legacy", HT_SYSTEM_ADB="1").returncode, 0)
        self.assertEqual(self.run_guard("transport", "legacy").returncode, 0)
        self.assertEqual(self.run_guard().returncode, 0)
        self.assertEqual(self.run_guard("status").stdout.splitlines()[6], f"{IP}:5037")

    def test_old_persistent_port_is_cleared_without_arming(self):
        (self.directory / "policy").unlink()
        self.write("persist.adb.tcp.port", "5037")
        self.write("service.adb.tcp.port", "5037")
        result = self.run_guard("restore", str(self.directory / "owner-token"), "local-test-token", "0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_closed()
        self.assertEqual((self.directory / "persist.adb.tcp.port").read_text(), "")
        self.assertEqual((self.directory / "policy").read_text(), "0\n0\n\n\n")


if __name__ == "__main__":
    unittest.main()
