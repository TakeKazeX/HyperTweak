#!/usr/bin/env python3
"""Hot-reload leak regression check for HyperTweak on a connected, rooted device.

  rounds  Install the APK N times as LSPosed hot reloads and, after each, report reload errors,
          crashes, host pid changes and, per scoped process, live module loaders, in-memory DEX,
          and HyperTweak threads. A leak shows as loaders/DEX that grow by one generation per round
          and do not fall back after the final settle.
          loaders=? means no trace appeared: the host intercepts SIGQUIT (xmsf's XCrash
          writes its own); its DEX figure still applies.
  heap    Dump one process's heap and print the strong paths that keep retired module
          generations alive. Pauses the process for seconds: a touch on SystemUI during the dump
          ANRs and restarts it, so it asks for confirmation.
  paths   The same analysis for an existing, hprof-conv converted dump.

The device shell must be able to signal the hosts (root adbd or `su`). Never restarts a host,
never touches LSPosed configuration. Installs only the APK it is given.
"""
import argparse, collections, os, re, shlex, struct, subprocess, sys, time

PROCESSES = ["com.android.systemui", "system_server", "com.miui.powerkeeper", "com.xiaomi.xmsf",
             "com.miui.securitycenter"]
READY = "hot reload replacement ready process="
ERRORS = re.compile(r"Failed to call onHotReload|missing settings participants| E HyperTweak")
# Heap edge labels of other modules known to retain our loaders; skipped in the path report.
FOREIGN_EDGES: set = set()


def adb(args, serial, check=True, timeout=600):
    cmd = ["adb"] + (["-s", serial] if serial else []) + args
    result = subprocess.run(cmd, text=True, errors="replace", capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise SystemExit(f"{shlex.join(cmd)} failed: {(result.stderr or result.stdout).strip()}")
    return result.stdout


def shell(script, serial, root, timeout=600):
    return adb(["shell", script if root else shlex.join(["su", "-c", script])], serial, timeout=timeout)


def pids(name, serial, root):
    return shell(f"pidof {shlex.quote(name)} || true", serial, root).split()


# `kill -10` makes ART run a full GC; two passes let finalizers and cleaners drain between them.
# `kill -3` writes a trace whose class-loader section lists every live loader by class.
MEASURE = r'''
for p in $PIDS; do kill -10 $p 2>/dev/null; done; sleep 8
for p in $PIDS; do kill -10 $p 2>/dev/null; done; sleep 8
for p in $PIDS; do
  before=$(ls -t /data/anr/trace_* 2>/dev/null | head -1)
  kill -3 $p; i=0; f=$before
  while [ "$f" = "$before" ] && [ $i -lt 15 ]; do sleep 1; i=$((i+1)); f=$(ls -t /data/anr/trace_* 2>/dev/null | head -1); done
  sleep 2
  [ "$f" = "$before" ] && n=? || n=$(awk '/Dumping registered class loaders/,/Done dumping/' $f | grep -c " $LOADER:")
  kb=$(awk '/dalvik-DEX data/{f=1;next} /^[0-9a-f]+-/{f=0} f&&/^Size:/{s+=$2} END{print s+0}' /proc/$p/smaps)
  th=$(cat /proc/$p/task/*/comm 2>/dev/null | grep '^HyperTweak' | sort | uniq -c | tr -s ' ' | tr '\n' ';')
  echo "$p loaders=$n dexMB=$((kb/1024)) threads=[$th]"
done
'''


def measure(serial, root, loader):
    named = [(name, pid) for name in PROCESSES for pid in pids(name, serial, root)]
    if not named:
        return
    script = f"PIDS={shlex.quote(' '.join(p for _, p in named))}; LOADER={shlex.quote(loader)}\n" + MEASURE
    rows = {line.split()[0]: line for line in shell(script, serial, root).splitlines() if line.strip()}
    for name, pid in named:
        print(f"  {name:28} pid={rows.get(pid, pid + ' unmeasured')}")


def rounds(args):
    host_pids = lambda: {n: pids(n, args.serial, args.root_adbd) for n in ("system_server", "com.android.systemui")}
    if args.out:
        os.makedirs(args.out, exist_ok=True)
    start = host_pids()
    print("before", start)
    measure(args.serial, args.root_adbd, args.loader_class)
    for r in range(1, args.rounds + 1):
        adb(["logcat", "-c"], args.serial)
        installed = adb(["install", "-r", args.apk], args.serial, check=False).strip().splitlines()[-1:]
        print(f"=== round {r} {time.strftime('%H:%M:%S')} install: {' '.join(installed)}")
        deadline = time.time() + 120
        while time.time() < deadline and READY + "com.android.systemui" not in adb(["logcat", "-d"], args.serial):
            time.sleep(2)
        time.sleep(args.settle)
        log = adb(["logcat", "-d"], args.serial)
        if args.out:
            with open(os.path.join(args.out, f"round{r}.log"), "w") as f:
                f.write(log)
        ht = [line for line in log.splitlines() if "HyperTweak" in line]
        print(f"  reloadErrors={sum(bool(ERRORS.search(l)) for l in ht)} "
              f"fatal={log.count('FATAL EXCEPTION')} signal={log.count('Fatal signal')} "
              f"dexReleased={log.count('released retired module dex')} hosts={host_pids()}")
        measure(args.serial, args.root_adbd, args.loader_class)
    print(f"=== final settle {args.final_settle}s")
    time.sleep(args.final_settle)
    measure(args.serial, args.root_adbd, args.loader_class)
    end = host_pids()
    if end != start:
        print(f"WARNING: a host restarted during the run: {start} -> {end}")


def heap(args):
    pid = (pids(args.process, args.serial, args.root_adbd) or [None])[0]
    if pid is None:
        raise SystemExit(f"{args.process} is not running")
    if not args.yes:
        answer = input(f"Dumping {args.process} pauses it for several seconds; do not touch the device. Continue? [y/N] ")
        if answer.strip().lower() != "y":
            return
    remote = f"/data/local/tmp/ht-{pid}.hprof"
    shell(f"rm -f {remote}; am dumpheap {pid} {remote}", args.serial, args.root_adbd)
    shell(f"while [ ! -s {remote} ]; do sleep 1; done; s0=0; while true; do s=$(stat -c %s {remote}); "
          f"[ \"$s\" = \"$s0\" ] && break; s0=$s; sleep 3; done", args.serial, args.root_adbd)
    os.makedirs(args.out, exist_ok=True)
    raw = os.path.join(args.out, f"{args.process}-{pid}.hprof")
    conv = raw.replace(".hprof", "-conv.hprof")
    adb(["pull", remote, raw], args.serial)
    shell(f"rm -f {remote}", args.serial, args.root_adbd)
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    subprocess.run([os.path.join(sdk, "platform-tools", "hprof-conv"), raw, conv], check=True)
    analyze(conv, args.limit)


# ---- HPROF analysis ----------------------------------------------------------------------------

TYPE_SIZE = {2: 4, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
ROOT_KINDS = {0xFF: (4, "unknown"), 0x01: (8, "jni_global"), 0x02: (12, "jni_local"), 0x03: (12, "java_frame"),
              0x04: (8, "native_stack"), 0x05: (4, "sticky_class"), 0x06: (8, "thread_block"),
              0x07: (4, "monitor"), 0x08: (12, "thread_obj"), 0x89: (4, "interned"), 0x8a: (4, "finalizing"),
              0x8b: (4, "debugger"), 0x8d: (4, "vm_internal"), 0x8e: (12, "jni_monitor"), 0x90: (4, "unreachable")}
REFERENCES = {"java.lang.ref.Reference", "java.lang.ref.WeakReference", "java.lang.ref.SoftReference",
              "java.lang.ref.PhantomReference", "java.lang.ref.FinalizerReference", "sun.misc.Cleaner"}


class Heap:
    def __init__(self, path):
        data = self.data = open(path, "rb").read()
        p = data.index(b"\0") + 1
        if struct.unpack(">I", data[p:p + 4])[0] != 4:
            raise SystemExit("expected 4-byte ids; convert the dump with hprof-conv first")
        p += 12
        self.strings, self.names, self.classes, self.instances, self.arrays, self.roots = {}, {}, {}, {}, {}, []
        self.field_cache = {}
        u4 = self.u4
        while p < len(data):
            tag, length, body = data[p], u4(p + 5), p + 9
            p = body + length
            if tag == 0x01:
                self.strings[u4(body)] = data[body + 4:body + length].decode("utf-8", "replace")
            elif tag == 0x02:
                self.names[u4(body + 4)] = u4(body + 12)
            elif tag in (0x0C, 0x1C):
                self._segment(body, body + length)

    def u4(self, offset):
        return struct.unpack_from(">I", self.data, offset)[0]

    def _segment(self, q, end):
        data, u4 = self.data, self.u4
        while q < end:
            sub = data[q]; q += 1
            if sub in ROOT_KINDS:
                size, kind = ROOT_KINDS[sub]; self.roots.append((u4(q), kind)); q += size
            elif sub == 0x20:
                cid, sup, loader = u4(q), u4(q + 8), u4(q + 12); q += 36
                n = struct.unpack_from(">H", data, q)[0]; q += 2
                for _ in range(n):
                    t = data[q + 2]; q += 3 + TYPE_SIZE[t]
                n = struct.unpack_from(">H", data, q)[0]; q += 2
                statics = []
                for _ in range(n):
                    name, t = u4(q), data[q + 4]
                    if t == 2: statics.append((name, u4(q + 5)))
                    q += 5 + TYPE_SIZE[t]
                n = struct.unpack_from(">H", data, q)[0]; q += 2
                fields = [(u4(q + 5 * i), data[q + 5 * i + 4]) for i in range(n)]; q += 5 * n
                self.classes[cid] = (sup, loader, fields, statics)
            elif sub == 0x21:
                oid, cid, n = u4(q), u4(q + 8), u4(q + 12)
                self.instances[oid] = (cid, q + 16); q += 16 + n
            elif sub == 0x22:
                oid, n = u4(q), u4(q + 8)
                self.arrays[oid] = [u4(q + 16 + 4 * i) for i in range(n)]; q += 16 + 4 * n
            elif sub == 0x23:
                n, t = u4(q + 8), data[q + 12]; q += 13 + n * TYPE_SIZE[t]
            elif sub == 0xFE:
                q += 8
            else:
                raise SystemExit(f"unknown heap sub-record 0x{sub:x}")

    def class_name(self, cid):
        return self.strings.get(self.names.get(cid), f"?{cid:x}")

    def fields(self, cid):
        cache = self.field_cache
        if cid not in cache:
            out, c = [], cid
            while c in self.classes:
                out += self.classes[c][2]; c = self.classes[c][0]
            cache[cid] = out
        return cache[cid]

    def is_reference(self, cid):
        while cid in self.classes:
            if self.class_name(cid) in REFERENCES: return True
            cid = self.classes[cid][0]
        return False

    def edges(self, oid):
        if oid in self.instances:
            cid, off = self.instances[oid]
            yield cid, "<class>"
            weak = self.is_reference(cid)
            for name, t in self.fields(cid):
                if t == 2:
                    value, label = self.u4(off), self.strings.get(name, "?")
                    if value and not (weak and label == "referent"): yield value, label
                off += TYPE_SIZE[t]
        elif oid in self.arrays:
            for i, value in enumerate(self.arrays[oid]):
                if value: yield value, f"[{i}]"
        elif oid in self.classes:
            sup, loader, _, statics = self.classes[oid]
            if loader: yield loader, "<loader>"
            if sup: yield sup, "<super>"
            for name, value in statics:
                if value: yield value, "static " + self.strings.get(name, "?")

    def describe(self, oid):
        if oid in self.instances: return f"{self.class_name(self.instances[oid][0])}@{oid:x}"
        if oid in self.classes: return f"class {self.class_name(oid)}@{oid:x}"
        if oid in self.arrays: return f"Object[]@{oid:x}"
        return f"@{oid:x}"


def analyze(path, limit):
    heap = Heap(path)
    # A module generation is a loader that defined the kept entry class. A substring of the package
    # would also count sibling apps such as com.takekazex.hypertweak.lab.
    modules = {loader for cid, (_, loader, _, _) in heap.classes.items()
               if loader and heap.class_name(cid) == "com.takekazex.hypertweak.hook.HookEntry"}
    parent, queue = {}, collections.deque()
    for root, kind in heap.roots:
        if root not in parent:
            parent[root] = (None, kind); queue.append(root)
    while queue:
        oid = queue.popleft()
        for value, label in heap.edges(oid):
            if label not in FOREIGN_EDGES and value not in parent:
                parent[value] = (oid, label); queue.append(value)
    live = [loader for loader in modules if loader in parent]
    print(f"module generations in heap={len(modules)} strongly reachable={len(live)} "
          f"(one is the current generation; foreign edges skipped: {sorted(FOREIGN_EDGES)})")
    for loader in live[:limit]:
        chain, oid = [], loader
        while oid is not None:
            up, label = parent[oid]; chain.append((label, heap.describe(oid))); oid = up
        print(f"\n=== {heap.describe(loader)}")
        for label, what in reversed(chain):
            print(f"    {label} -> {what}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial")
    parser.add_argument("--root-adbd", action="store_true", help="adbd already runs as UID 0")
    sub = parser.add_subparsers(dest="command", required=True)
    r = sub.add_parser("rounds")
    r.add_argument("apk")
    r.add_argument("--rounds", type=int, default=3)
    r.add_argument("--settle", type=int, default=60, help="seconds after SystemUI reports the replacement")
    r.add_argument("--final-settle", type=int, default=120)
    r.add_argument("--loader-class", default="p1",
                   help="class of LSPosed's in-memory module loader as named in kill -3 traces")
    r.add_argument("--out", help="directory for per-round logcat")
    h = sub.add_parser("heap")
    h.add_argument("process")
    h.add_argument("--out", default=".")
    h.add_argument("--limit", type=int, default=20)
    h.add_argument("--yes", action="store_true", help="skip the do-not-touch confirmation")
    p = sub.add_parser("paths")
    p.add_argument("hprof")
    p.add_argument("--limit", type=int, default=20)
    args = parser.parse_args()
    if args.command == "rounds": rounds(args)
    elif args.command == "heap": heap(args)
    else: analyze(args.hprof, args.limit)


if __name__ == "__main__":
    sys.exit(main())
