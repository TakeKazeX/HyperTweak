package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

/** Private, versioned, bounded wallpaper pixels. An ID mismatch is an ordinary cache miss. */
final class WallpaperCacheFile {
    private static final int MAGIC = 0x48545750;
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = 24;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private final File file;

    WallpaperCacheFile(File file) { this.file = file; }

    byte[] read(int wallpaperId) throws IOException {
        if (wallpaperId <= 0 || !file.isFile()) return null;
        long length = file.length();
        if (length <= HEADER_SIZE || length > HEADER_SIZE + MAX_BYTES) return null;
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file.toPath()))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readInt() != wallpaperId) return null;
            int size = input.readInt();
            long checksum = input.readLong();
            if (size <= 0 || size > MAX_BYTES || length != HEADER_SIZE + (long) size) return null;
            byte[] pixels = new byte[size];
            input.readFully(pixels);
            CRC32 crc = new CRC32();
            crc.update(pixels);
            return crc.getValue() == checksum ? pixels : null;
        }
    }

    void write(int wallpaperId, byte[] pixels) throws IOException {
        if (wallpaperId <= 0 || pixels.length == 0 || pixels.length > MAX_BYTES) throw new IOException("Invalid wallpaper cache record");
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) throw new IOException("Wallpaper cache directory unavailable");
        File pending = File.createTempFile("wallpaper-", ".tmp", parent);
        try {
            CRC32 crc = new CRC32();
            crc.update(pixels);
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(pending.toPath()))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(wallpaperId);
                output.writeInt(pixels.length);
                output.writeLong(crc.getValue());
                output.write(pixels);
            }
            try {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(pending.toPath()); }
    }
}
