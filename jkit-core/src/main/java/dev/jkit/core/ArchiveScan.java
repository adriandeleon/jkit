package dev.jkit.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Checks an archive's structure before commons-compress reads it, because the library builds each
 * entry in memory first: a 1 MB tar whose long-name header claims 1 GiB, or a zip whose central
 * directory lists millions of entries, would use gigabytes before {@link Archives}' own limits
 * get a chance to count anything. Reads only headers and sizes, never the data.
 */
final class ArchiveScan {
    /** GNU long names, PAX headers: real ones are a few hundred bytes. */
    static final int MAX_META_HEADER = 64 * 1024;
    /** The zip central directory: about 100 bytes per entry. */
    static final long MAX_CENTRAL_DIRECTORY = 64L << 20;

    private ArchiveScan() { }

    /** Every tar header: meta headers small, at most {@code maxEntries} entries, at most {@code maxBytes} of data. */
    static void tarGz(Path archive, int maxEntries, long maxBytes) throws IOException {
        try (InputStream in = new GzipCompressorInputStream(new BufferedInputStream(Files.newInputStream(archive)), true)) {
            byte[] header = new byte[512];
            int entries = 0;
            long total = 0;
            long paxSize = -1;
            while (true) {
                if (in.readNBytes(header, 0, 512) < 512 || isZero(header)) {
                    return; // end of archive (or a short one, which the real read reports)
                }
                if (!checksumOk(header)) {
                    throw Archives.unsafe("a tar header is damaged");
                }
                char type = (char) header[156];
                long size = size(header);
                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    if (size > MAX_META_HEADER) {
                        throw Archives.unsafe("a tar header claims " + size + " bytes");
                    }
                    byte[] meta = in.readNBytes((int) size);
                    skip(in, pad(size) - size);
                    if (type == 'x') {
                        paxSize = paxSize(meta);
                    }
                    continue;
                }
                if (++entries > maxEntries) {
                    throw Archives.unsafe("more than " + maxEntries + " entries");
                }
                long data = paxSize >= 0 ? paxSize : size;
                paxSize = -1;
                if (data < 0) {
                    throw Archives.unsafe("a tar entry has a negative size");
                }
                boolean hasData = type == '0' || type == '\0' || type == '7';
                if (hasData) {
                    total += data;
                    if (total > maxBytes) {
                        throw Archives.unsafe("it unpacks to more than " + (maxBytes >> 30) + " GB");
                    }
                }
                // a link or folder header may still carry data the reader has to skip
                skip(in, pad(data));
                if (!hasData) {
                    total += data;
                    if (total > maxBytes) {
                        throw Archives.unsafe("it holds more than " + (maxBytes >> 30) + " GB");
                    }
                }
            }
        }
    }

    /** The entry count and central directory size from the end records, before ZipFile reads the directory. */
    static void zip(Path archive, int maxEntries) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(archive)) {
            long length = ch.size();
            int tail = (int) Math.min(length, 22 + 65535);
            ByteBuffer buf = ByteBuffer.allocate(tail).order(ByteOrder.LITTLE_ENDIAN);
            ch.position(length - tail);
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // fill
            }
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (buf.getInt(i) == 0x06054b50) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                return; // not a zip: ZipFile says so
            }
            long entries = Short.toUnsignedInt(buf.getShort(eocd + 10));
            long dirSize = Integer.toUnsignedLong(buf.getInt(eocd + 12));
            if ((entries == 0xffff || dirSize == 0xffffffffL) && eocd >= 20 && buf.getInt(eocd - 20) == 0x07064b50) {
                long zip64 = buf.getLong(eocd - 20 + 8);
                if (zip64 >= 0 && zip64 + 56 <= length) {
                    ByteBuffer z = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
                    ch.position(zip64);
                    while (z.hasRemaining() && ch.read(z) > 0) {
                        // fill
                    }
                    if (z.getInt(0) == 0x06064b50) {
                        entries = z.getLong(32);
                        dirSize = z.getLong(40);
                    }
                }
            }
            if (entries > maxEntries || entries < 0) {
                throw Archives.unsafe("more than " + maxEntries + " entries");
            }
            if (dirSize > MAX_CENTRAL_DIRECTORY || dirSize < 0) {
                throw Archives.unsafe("its directory is over " + (MAX_CENTRAL_DIRECTORY >> 20) + " MB");
            }
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean checksumOk(byte[] h) {
        long stored = octal(h, 148, 8);
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < 512; i++) {
            byte b = i >= 148 && i < 156 ? (byte) ' ' : h[i];
            unsigned += b & 0xff;
            signed += b;
        }
        return stored == unsigned || stored == signed;
    }

    /** The size field: octal text, or GNU base-256 when the first byte has its top bit set. */
    private static long size(byte[] h) throws IOException {
        if ((h[124] & 0x80) != 0) {
            long v = 0;
            for (int i = 125; i < 136; i++) {
                if ((v >>> 55) != 0) {
                    throw Archives.unsafe("a tar size is too large");
                }
                v = (v << 8) | (h[i] & 0xff);
            }
            return v;
        }
        return octal(h, 124, 12);
    }

    private static long octal(byte[] h, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = h[i];
            if (c == 0 || c == ' ') {
                if (v != 0) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                break;
            }
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static long pad(long size) {
        return (size + 511) / 512 * 512;
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) {
                    return;
                }
                s = 1;
            }
            n -= s;
        }
    }

    /** The "size" record of a PAX header, or -1. */
    private static long paxSize(byte[] meta) {
        String text = new String(meta, StandardCharsets.UTF_8);
        for (String record : text.split("\n")) {
            int sp = record.indexOf(' ');
            String kv = sp >= 0 ? record.substring(sp + 1) : record;
            if (kv.startsWith("size=")) {
                try {
                    return Long.parseLong(kv.substring(5).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
