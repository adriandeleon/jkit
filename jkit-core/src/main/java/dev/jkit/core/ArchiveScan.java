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

    /** A path or link target: Linux allows 4096 bytes. */
    static final int MAX_NAME = 4096;

    /**
     * Every tar header, read the way commons-compress reads it. Only the entry types JDK, Maven,
     * Gradle and sbt archives use are allowed (files, links, folders, GNU long names, PAX headers);
     * anything else (Solaris X headers, sparse files, devices) is refused rather than guessed at,
     * so this scan and the real read can't disagree. Names stay under {@link #MAX_NAME}, at most
     * {@code maxEntries} entries, at most {@code maxBytes} of data in all.
     */
    static void tarGz(Path archive, int maxEntries, long maxBytes) throws IOException {
        try (InputStream in = new GzipCompressorInputStream(new BufferedInputStream(Files.newInputStream(archive)), true)) {
            byte[] header = new byte[512];
            int entries = 0;
            long total = 0;
            long paxSize = -1;
            String longName = null;
            String paxPath = null;
            // the extended headers (x, L, K) seen since the last entry, and global (g) headers in all
            String pending = "";
            int globals = 0;
            while (true) {
                if (in.readNBytes(header, 0, 512) < 512 || isZero(header)) {
                    return; // end of archive (or a short one, which the real read reports)
                }
                if (!checksumOk(header)) {
                    throw Archives.unsafe("a tar header is damaged");
                }
                char type = (char) header[156];
                long size = size(header);
                if (size < 0) {
                    throw Archives.unsafe("a tar entry has a negative size");
                }
                if (type == 'x' || type == 'L' || type == 'K') {
                    // commons-compress reads these recursively and applies them inside out, so two of
                    // a kind before one entry (or both a PAX path and a long name) mean different
                    // names and sizes to it and to this scan: refuse rather than guess its order
                    if (pending.indexOf(type) >= 0) {
                        throw Archives.unsafe("an entry in it has two '" + type + "' headers");
                    }
                    pending += type;
                    if (++entries > maxEntries) {
                        throw Archives.unsafe("more than " + maxEntries + " entries");
                    }
                    total += size;
                } else if (type == 'g') {
                    if (++globals > 8) {
                        throw Archives.unsafe("it has more than 8 global headers");
                    }
                    total += size;
                }
                switch (type) {
                    case 'L', 'K' -> {
                        if (size > MAX_NAME + 1) {
                            throw Archives.unsafe("a tar header claims a " + size + "-byte name");
                        }
                        byte[] name = in.readNBytes((int) size);
                        skip(in, pad(size) - size);
                        if (type == 'L') {
                            longName = cString(name, 0, name.length);
                        }
                        continue;
                    }
                    case 'x', 'g' -> {
                        if (size > MAX_META_HEADER) {
                            throw Archives.unsafe("a tar header claims " + size + " bytes");
                        }
                        byte[] meta = in.readNBytes((int) size);
                        skip(in, pad(size) - size);
                        String[] path = new String[1];
                        long sz = pax(meta, type == 'g', path);
                        if (type == 'x') {
                            paxSize = sz;
                            paxPath = path[0];
                        }
                        continue;
                    }
                    case '0', '\0', '7', '1', '2', '5' -> {
                        // an ordinary entry, below
                    }
                    case 'S' -> throw Archives.unsafe("it holds a sparse file, which jkit doesn't unpack");
                    default -> throw Archives.unsafe("it holds a tar entry of type '"
                            + (type >= 0x20 && type < 0x7f ? String.valueOf(type) : String.format("\\x%02x", (int) type))
                            + "', which jkit doesn't unpack");
                }
                if (++entries > maxEntries) {
                    throw Archives.unsafe("more than " + maxEntries + " entries");
                }
                if (paxPath != null && longName != null) {
                    throw Archives.unsafe("an entry in it has both a PAX path and a long name");
                }
                long data = paxSize >= 0 ? paxSize : size;
                String name = paxPath != null ? paxPath : longName != null ? longName : cString(header, 0, 100);
                paxSize = -1;
                paxPath = null;
                longName = null;
                pending = "";
                if (data > 0 && (type == '5' || name.endsWith("/"))) {
                    // commons-compress never reads a folder's data, so it would parse that data as
                    // headers this scan never saw: refuse rather than read two different archives
                    throw Archives.unsafe("a folder entry in it carries data");
                }
                total += data;
                if (total > maxBytes) {
                    throw Archives.unsafe("it unpacks to more than " + (maxBytes >> 30) + " GB");
                }
                // a link header may still carry data, which the reader skips too
                skip(in, pad(data));
            }
        }
    }

    /**
     * A PAX header's records, parsed as commons-compress does: each is {@code <length> key=value\n},
     * the length counting the whole record, and the last {@code size} wins. Returns that size or -1, and puts a {@code path} record in {@code path[0]}.
     * Sparse-file records and over-long names are refused; a global header may not set a size or a name.
     */
    private static long pax(byte[] meta, boolean global, String[] path) {
        long size = -1;
        int i = 0;
        while (i < meta.length) {
            int sp = i;
            while (sp < meta.length && meta[sp] >= '0' && meta[sp] <= '9' && sp - i < 10) {
                sp++;
            }
            if (sp == i || sp >= meta.length || meta[sp] != ' ') {
                if (meta[i] == 0) {
                    break; // padding
                }
                throw Archives.unsafe("a PAX header is damaged");
            }
            int length = Integer.parseInt(new String(meta, i, sp - i, StandardCharsets.US_ASCII));
            int recordEnd = i + length;
            if (length <= sp - i + 1 || recordEnd > meta.length || meta[recordEnd - 1] != '\n') {
                throw Archives.unsafe("a PAX header is damaged");
            }
            String record = new String(meta, sp + 1, recordEnd - sp - 2, StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq < 0) {
                throw Archives.unsafe("a PAX header is damaged");
            }
            String key = record.substring(0, eq);
            String value = record.substring(eq + 1);
            if (key.startsWith("GNU.sparse")) {
                throw Archives.unsafe("it holds a sparse file, which jkit doesn't unpack");
            }
            if ((key.equals("path") || key.equals("linkpath")) && value.length() > MAX_NAME) {
                throw Archives.unsafe("a tar entry has a " + value.length() + "-character name");
            }
            if (key.equals("path")) {
                path[0] = value;
            }
            if (global && (key.equals("path") || key.equals("linkpath"))) {
                // commons-compress merges these into the next entry's own headers in an order of its own
                throw Archives.unsafe("a global PAX header sets a name");
            }
            if (key.equals("size")) {
                if (global) {
                    throw Archives.unsafe("a global PAX header sets a size");
                }
                try {
                    size = Long.parseLong(value.trim());
                } catch (NumberFormatException e) {
                    throw Archives.unsafe("a PAX header is damaged");
                }
                if (size < 0) {
                    throw Archives.unsafe("a tar entry has a negative size");
                }
            }
            i = recordEnd;
        }
        return size;
    }

    /**
     * The central directory, walked the way ZipFile walks it: from the offset the end record gives,
     * header after header until one isn't a directory header, whatever count the end record claims.
     * At most {@code maxEntries} headers and {@link #MAX_CENTRAL_DIRECTORY} bytes.
     */
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
            long offset = Integer.toUnsignedLong(buf.getInt(eocd + 16));
            if (eocd >= 20 && buf.getInt(eocd - 20) == 0x07064b50) {
                long zip64 = buf.getLong(eocd - 20 + 8);
                if (zip64 >= 0 && zip64 + 56 <= length) {
                    ByteBuffer z = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
                    ch.position(zip64);
                    while (z.hasRemaining() && ch.read(z) > 0) {
                        // fill
                    }
                    if (z.getInt(0) == 0x06064b50) {
                        offset = z.getLong(48);
                    }
                }
            }
            if (offset < 0 || offset >= length) {
                return; // ZipFile reports the damage
            }
            ch.position(offset);
            InputStream in = new BufferedInputStream(java.nio.channels.Channels.newInputStream(ch), 64 * 1024);
            byte[] fixed = new byte[46];
            ByteBuffer h = ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN);
            long entries = 0;
            long bytes = 0;
            java.util.List<Long> locals = new java.util.ArrayList<>();
            while (in.readNBytes(fixed, 0, 46) == 46 && h.getInt(0) == 0x02014b50) {
                if (++entries > maxEntries) {
                    throw Archives.unsafe("more than " + maxEntries + " entries");
                }
                int nameLength = Short.toUnsignedInt(h.getShort(28));
                int extraLength = Short.toUnsignedInt(h.getShort(30));
                int commentLength = Short.toUnsignedInt(h.getShort(32));
                bytes += 46 + nameLength + extraLength + commentLength;
                if (bytes > MAX_CENTRAL_DIRECTORY) {
                    throw Archives.unsafe("its directory is over " + (MAX_CENTRAL_DIRECTORY >> 20) + " MB");
                }
                skip(in, nameLength);
                byte[] extra = in.readNBytes(extraLength);
                skip(in, commentLength);
                locals.add(localOffset(h, extra));
            }
            localHeaders(ch, length, locals);
        }
    }

    /** Where an entry's local header is: the 32-bit field, or its zip64 extra when that is all ones. */
    private static long localOffset(ByteBuffer central, byte[] extra) {
        long offset = Integer.toUnsignedLong(central.getInt(42));
        if (offset != 0xffffffffL) {
            return offset;
        }
        boolean bigSize = central.getInt(24) == -1;
        boolean bigCompressed = central.getInt(20) == -1;
        ByteBuffer e = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
        while (e.remaining() >= 4) {
            int id = Short.toUnsignedInt(e.getShort());
            int size = Short.toUnsignedInt(e.getShort());
            if (size > e.remaining()) {
                break;
            }
            if (id == 0x0001) {
                int skip = (bigSize ? 8 : 0) + (bigCompressed ? 8 : 0);
                if (size >= skip + 8) {
                    return e.getLong(e.position() + skip);
                }
                break;
            }
            e.position(e.position() + size);
        }
        return offset;
    }

    /**
     * ZipFile reads every entry's local header, extra field and all, and keeps it: thousands of
     * entries pointing at one header with a 64 KB extra field would take gigabytes from a small
     * file. So each entry must have a local header of its own, ending before the next one starts.
     */
    private static void localHeaders(SeekableByteChannel ch, long length, java.util.List<Long> offsets) throws IOException {
        long[] sorted = offsets.stream().mapToLong(Long::longValue).sorted().toArray();
        ByteBuffer local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sorted.length; i++) {
            if (i > 0 && sorted[i] == sorted[i - 1]) {
                throw Archives.unsafe("entries in it share or overlap a local header");
            }
            if (sorted[i] < 0 || sorted[i] + 30 > length) {
                return; // ZipFile reports the damage
            }
            local.clear();
            ch.position(sorted[i]);
            while (local.hasRemaining() && ch.read(local) > 0) {
                // fill
            }
            long end = sorted[i] + 30 + Short.toUnsignedInt(local.getShort(26)) + Short.toUnsignedInt(local.getShort(28));
            if (i + 1 < sorted.length && end > sorted[i + 1]) {
                throw Archives.unsafe("entries in it share or overlap a local header");
            }
        }
    }

    /** Text up to the first NUL, as tar stores names. */
    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
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
}
