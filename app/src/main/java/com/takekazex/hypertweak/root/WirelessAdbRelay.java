package com.takekazex.hypertweak.root;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
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
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Root-only app_process entry point. The LAN socket exists only while the guard worker that
 * started it is alive: the worker holds the only writer of the liveness pipe, so its exit (or
 * re-exec) hangs the pipe up and the relay closes every session. Pipe hang-up does not depend on
 * clocks, so a long suspend never revokes a session that is still allowed.
 */
@Keep
public final class WirelessAdbRelay {
    private static final int MAX_SESSIONS = 4;
    /** How often the bound interface address is rechecked while idle (monotonic, awake time). */
    private static final int ADDRESS_CHECK_MS = 2_000;
    // Linux values; android.system.OsConstants does not expose the keepalive tuning options.
    private static final int TCP_KEEPIDLE = 4;
    private static final int TCP_KEEPINTVL = 5;
    private static final int TCP_KEEPCNT = 6;

    private WirelessAdbRelay() {}

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0 || args.length != 7) System.exit(1);
        String iface = args[0];
        InetAddress address = InetAddress.getByName(args[1]);
        int port = Integer.parseInt(args[2]);
        if (!(address instanceof Inet4Address) || !iface.matches("wlan[0-9]+") ||
                port < 1 || port > 65535 || !args[3].matches("hypertweak-adbd-[0-9a-f]{16}")) {
            System.exit(1);
        }
        Path ready = Path.of(args[5]);
        // The worker opened the writer before starting us, so a later hang-up is meaningful.
        FileDescriptor alive = Os.open(args[4], OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
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
            StructPollfd listen = new StructPollfd();
            listen.fd = server;
            listen.events = (short) OsConstants.POLLIN;
            StructPollfd owner = new StructPollfd();
            owner.fd = alive;
            owner.events = (short) OsConstants.POLLIN;
            StructPollfd[] descriptors = {listen, owner};
            while (true) {
                int events;
                try {
                    events = Os.poll(descriptors, ADDRESS_CHECK_MS);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR) continue;
                    throw e;
                }
                // Any event on the pipe means the worker is gone or revoked us; it never writes.
                if (owner.revents != 0) break;
                if (!bound(iface, address)) {
                    // The worker polls slowly while a listener is steady; tell it to reopen now.
                    wake(args[6]);
                    break;
                }
                if (events == 0 || (listen.revents & OsConstants.POLLIN) == 0) continue;
                FileDescriptor client;
                try {
                    client = Os.accept(server, null);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue;
                    throw e;
                }
                if (slots.tryAcquire()) {
                    keepAlive(client);
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

    /** Never blocks: without a reader the open fails and the worker's next tick takes over. */
    private static void wake(String fifo) {
        try {
            FileDescriptor fd = Os.open(fifo, OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
            try {
                Os.write(fd, new byte[] {'\n'}, 0, 1);
            } finally {
                Os.close(fd);
            }
        } catch (ErrnoException | java.io.InterruptedIOException ignored) {
        }
    }

    /** The relay's address must still belong to its interface; DHCP changes retire it. */
    private static boolean bound(String iface, InetAddress address) {
        try {
            NetworkInterface network = NetworkInterface.getByName(iface);
            if (network == null || !network.isUp()) return false;
            for (InetAddress candidate : Collections.list(network.getInetAddresses())) {
                if (candidate.equals(address)) return true;
            }
            return false;
        } catch (SocketException e) {
            return false;
        }
    }

    /** Keeps idle sessions in AP/NAT tables and reaps peers that vanished without a FIN. */
    private static void keepAlive(FileDescriptor client) {
        try {
            Os.setsockoptInt(client, OsConstants.SOL_SOCKET, OsConstants.SO_KEEPALIVE, 1);
            Os.setsockoptInt(client, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, 120);
            Os.setsockoptInt(client, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, 15);
            Os.setsockoptInt(client, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, 4);
        } catch (ErrnoException ignored) {
            // Keepalive is an optimisation; the session works without it.
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
