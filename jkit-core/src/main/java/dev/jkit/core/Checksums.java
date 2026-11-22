package dev.jkit.core;

import dev.jkit.model.JkitException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class Checksums {
    private Checksums() { }

    /** sha256 and up; md5 and sha1 only catch a broken download. */
    static boolean strong(String algorithm) {
        String a = algorithm.toLowerCase(Locale.ROOT).replace("-", "");
        return a.equals("sha256") || a.equals("sha384") || a.equals("sha512");
    }

    static void verify(Path file, String algorithm, String expected, String label) throws IOException {
        String javaName = switch (algorithm.toLowerCase(Locale.ROOT).replace("-", "")) {
            case "sha256" -> "SHA-256";
            case "sha384" -> "SHA-384";
            case "sha512" -> "SHA-512";
            case "sha1" -> "SHA-1";
            case "md5" -> "MD5";
            default -> throw new IOException("unknown checksum type " + algorithm);
        };
        String actual;
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(javaName);
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            actual = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        if (!actual.equalsIgnoreCase(expected.trim())) {
            throw new JkitException("checksum mismatch for " + label + " (" + algorithm + ")",
                    "expected " + expected, "actual   " + actual, "nothing was installed");
        }
    }
}
