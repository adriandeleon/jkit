package dev.jkit.core;

import dev.jkit.model.JkitException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * The small text files jkit reads and edits (project files, config.toml, shell startup files):
 * read in whatever encoding they are in, size-limited, and written back in that same encoding.
 */
public final class TextFiles {
    /** The largest project or config file jkit reads. */
    static final int MAX_SIZE = 256 * 1024;

    private TextFiles() { }

    /**
     * How a file's text is stored: its charset, and whether a byte-order mark comes first. Text read
     * with {@link #text} and written back with {@link #write} keeps both, so every byte an edit
     * doesn't change stays as it was.
     */
    public record Encoding(Charset charset, boolean bom) {
        static final Encoding UTF_8 = new Encoding(StandardCharsets.UTF_8, false);
    }

    /**
     * The file as text: UTF-8 or UTF-16 by byte-order mark (PowerShell 5's {@code echo 21 > .java-version}
     * writes UTF-16), else UTF-8, else Latin-1. Never a decoding error.
     */
    public static String text(Path file) throws IOException {
        byte[] bytes = bytes(file);
        Encoding encoding = encoding(bytes);
        int skip = !encoding.bom() ? 0 : encoding.charset().equals(StandardCharsets.UTF_8) ? 3 : 2;
        return new String(bytes, skip, bytes.length - skip, encoding.charset());
    }

    /** The encoding {@link #text} reads the file with; UTF-8 without a mark for a file not there yet. */
    public static Encoding encoding(Path file) throws IOException {
        return Files.isRegularFile(file) ? encoding(bytes(file)) : Encoding.UTF_8;
    }

    /**
     * Writes text {@link #text} read from {@code file}, edited, back in the file's own encoding,
     * all at once ({@link AtomicFile}); a new file is UTF-8. A charset that can't hold the new text
     * (a Latin-1 file given a character it lacks) is refused, and the file stays as it was.
     */
    public static void write(Path file, String text, boolean followLinks) throws IOException {
        Encoding encoding = !followLinks && Files.isSymbolicLink(file) ? Encoding.UTF_8 : encoding(file);
        try {
            AtomicFile.write(file, encoding.bom() ? "﻿" + text : text, encoding.charset(), followLinks);
        } catch (CharacterCodingException e) {
            throw new JkitException(file + " is in " + encoding.charset().name() + ", which can't hold the new text;"
                    + " nothing changed", "save it as UTF-8, then run the command again");
        }
    }

    private static byte[] bytes(Path file) throws IOException {
        if (Files.exists(file) && !Files.isRegularFile(file)) {
            // a named pipe would block every cd, waiting for a writer
            throw new JkitException(file + " is not a regular file");
        }
        byte[] bytes;
        try (var in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_SIZE + 1);
        }
        if (bytes.length > MAX_SIZE) {
            // project files are a few lines; a huge one would make every cd slow, or run out of memory
            throw new JkitException(file + " is over " + MAX_SIZE / 1024 + " KiB, too big for a project or config file");
        }
        return bytes;
    }

    /** UTF-8 or UTF-16 by byte-order mark, else UTF-8 when the bytes are valid UTF-8, else Latin-1. */
    public static Encoding encoding(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            return new Encoding(StandardCharsets.UTF_8, true);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return new Encoding(StandardCharsets.UTF_16LE, true);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new Encoding(StandardCharsets.UTF_16BE, true);
        }
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            return Encoding.UTF_8;
        } catch (CharacterCodingException e) {
            return new Encoding(StandardCharsets.ISO_8859_1, false);
        }
    }

    /** TOML parsed; deep nesting ({@code a = [[[[...}) is an error, not a crash. */
    static TomlParseResult parseToml(Path file, String text) {
        try {
            return Toml.parse(text);
        } catch (StackOverflowError e) {
            throw new JkitException(file + ": invalid TOML: nested too deeply");
        }
    }
}
