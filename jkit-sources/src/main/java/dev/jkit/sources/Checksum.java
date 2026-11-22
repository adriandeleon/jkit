package dev.jkit.sources;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A published checksum: the hash and its algorithm. Reads checksum files in the formats vendors
 * use: a bare hash, GNU {@code hash  file} (one or many lines), and BSD {@code SHA256 (file) = hash}.
 */
public record Checksum(String algorithm, String hash) {
    private static final Pattern BSD = Pattern.compile("^([A-Za-z0-9-]+)\\s*\\((.*)\\)\\s*=\\s*([0-9a-fA-F]+)$");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");

    /**
     * The hash for {@code fileName} in a checksum file, or null when there is none. The algorithm
     * comes from the hash's length when it is unambiguous; else from the file (BSD), else
     * {@code declared} (what the source says), else sha256.
     */
    public static Checksum parse(String text, String fileName, String declared) {
        String bsdAlgorithm = null;
        String hash = null;
        String firstHash = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher bsd = BSD.matcher(line);
            String lineHash;
            String lineFile;
            String lineAlgorithm = null;
            if (bsd.matches()) {
                lineAlgorithm = bsd.group(1);
                lineFile = bsd.group(2);
                lineHash = bsd.group(3);
            } else {
                String[] parts = line.split("\\s+", 2);
                lineHash = parts[0];
                lineFile = parts.length > 1 ? parts[1].replaceFirst("^\\*", "") : null;
            }
            if (!HEX.matcher(lineHash).matches()) {
                continue;
            }
            if (firstHash == null) {
                firstHash = lineHash;
                bsdAlgorithm = lineAlgorithm;
            }
            if (fileName != null && lineFile != null && baseName(lineFile).equals(fileName)) {
                hash = lineHash;
                bsdAlgorithm = lineAlgorithm;
                break;
            }
        }
        if (hash == null) {
            hash = firstHash;
        }
        if (hash == null) {
            return null;
        }
        return of(hash, bsdAlgorithm != null ? bsdAlgorithm : declared);
    }

    /** A hash given directly; null when it is empty. */
    public static Checksum of(String hash, String declared) {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        String h = hash.strip().toLowerCase(Locale.ROOT);
        String byLength = switch (h.length()) {
            case 32 -> "md5";
            case 40 -> "sha1";
            case 64 -> "sha256";
            case 96 -> "sha384";
            case 128 -> "sha512";
            default -> null;
        };
        String algorithm = byLength != null ? byLength
                : declared == null || declared.isBlank() ? "sha256" : normalize(declared);
        return new Checksum(algorithm, h);
    }

    private static String normalize(String algorithm) {
        return algorithm.toLowerCase(Locale.ROOT).replace("-", "");
    }

    private static String baseName(String path) {
        String p = path.strip().replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }
}
