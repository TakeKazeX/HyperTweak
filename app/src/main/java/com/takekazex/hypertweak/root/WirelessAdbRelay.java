package com.takekazex.hypertweak.root;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import androidx.annotation.Keep;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Root-only app_process entry point. The LAN socket exists only while its lease is valid. */
@Keep
public final class WirelessAdbRelay {
    private static final int LEASE_MS = 4_000;
    private static final int MAX_SESSIONS = 4;

    private WirelessAdbRelay() {}

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0 || args.length != 6) System.exit(1);
        String iface = args[0];
        InetAddress address = InetAddress.getByName(args[1]);
        int port = Integer.parseInt(args[2]);
        if (!(address instanceof Inet4Address) || !iface.matches("wlan[0-9]+") ||
                port < 1 || port > 65535 || !args[3].matches("hypertweak-adbd-[0-9a-f]{16}")) {
            System.exit(1);
        }
        Path lease = Path.of(args[4]);
        Path ready = Path.of(args[5]);
        if (remaining(lease) <= 0) System.exit(1);
        FileDescriptor server = Os.socket(
            OsConstants.AF_INET, OsConstants.SOCK_STREAM | OsConstants.SOCK_NONBLOCK, 0
        );
        try {
            Os.class.getMethod("setsockoptIfreq", FileDescriptor.class, int.class, int.class, String.class)
                .invoke(null, server, OsConstants.SOL_SOCKET, OsConstants.SO_BINDTODEVICE, iface);
            Os.setsockoptInt(server, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1);
            Os.bind(server, address, port);
            Os.listen(server, MAX_SESSIONS);
            Files.write(ready, (Process.myPid() + "\n").getBytes(StandardCharsets.US_ASCII));
            Semaphore slots = new Semaphore(MAX_SESSIONS);
            StructPollfd poll = new StructPollfd();
            poll.fd = server;
            poll.events = (short) OsConstants.POLLIN;
            StructPollfd[] descriptors = {poll};
            int wait;
            while ((wait = remaining(lease)) > 0) {
                if (Os.poll(descriptors, wait) == 0) continue;
                if (remaining(lease) <= 0 || (poll.revents & OsConstants.POLLIN) == 0) break;
                FileDescriptor client;
                try {
                    client = Os.accept(server, null);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue;
                    throw e;
                }
                if (slots.tryAcquire()) {
                    daemon(() -> forward(client, args[3], slots));
                } else {
                    Os.close(client);
                }
            }
        } finally {
            Os.close(server);
            Files.deleteIfExists(ready);
        }
        // Closing the process also closes already accepted sockets, including stalled clients.
        System.exit(0);
    }

    private static int remaining(Path lease) {
        try {
            long remaining = Long.parseLong(new String(Files.readAllBytes(lease), StandardCharsets.US_ASCII).trim()) - SystemClock.elapsedRealtime();
            return remaining > 0 && remaining <= LEASE_MS ? (int) Math.min(remaining, 500) : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private static void forward(FileDescriptor client, String backend, Semaphore slots) {
        LocalSocket upstream = new LocalSocket();
        AtomicInteger directions = new AtomicInteger(2);
        Runnable finish = () -> {
            if (directions.decrementAndGet() != 0) return;
            try { upstream.close(); } catch (IOException ignored) {}
            try { Os.close(client); } catch (Exception ignored) {}
            slots.release();
        };
        InputStream fromAdbd;
        OutputStream toAdbd;
        try {
            upstream.connect(new LocalSocketAddress(backend, LocalSocketAddress.Namespace.ABSTRACT));
            int uid = upstream.getPeerCredentials().getUid();
            if (uid != 0 && uid != 2000) throw new IOException("Unexpected adbd socket owner");
            fromAdbd = upstream.getInputStream();
            toAdbd = upstream.getOutputStream();
        } catch (IOException e) {
            finish.run();
            finish.run();
            return;
        }
        InputStream fromClient = new FileInputStream(client);
        OutputStream toClient = new FileOutputStream(client);
        daemon(() -> {
            try {
                fromAdbd.transferTo(toClient);
            } catch (IOException ignored) {
            } finally {
                try { Os.shutdown(client, OsConstants.SHUT_WR); } catch (Exception ignored) {}
                finish.run();
            }
        });
        try {
            fromClient.transferTo(toAdbd);
        } catch (IOException ignored) {
        } finally {
            try { upstream.shutdownOutput(); } catch (IOException ignored) {}
            finish.run();
        }
    }

    private static void daemon(Runnable action) {
        Thread thread = new Thread(action, "HyperTweakAdbRelay");
        thread.setDaemon(true);
        thread.start();
    }
}
