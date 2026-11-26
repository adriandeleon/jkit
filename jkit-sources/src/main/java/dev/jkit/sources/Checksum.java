package dev.jkit.sources;

import dev.jkit.model.JkitException;
import dev.jkit.model.Visible;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A published checksum: the hash and its algorithm. Reads checksum files in the formats vendors
 * use: a bare hash, GNU {@code hash  file} (one or many lines), and BSD {@code SHA256 (file) = hash}.
 */
public record Checksum(String algorithm, String hash) {
    private static final Pattern BSD = Pattern.compile("^([A-Za-z0-9-]+)\\s*\\((.*)\\)\\s*=\\s*([0-9a-fA-F]+)$");
    /** md5 is the shortest hash anyone publishes; shorter hex words ("cafe") are text, not hashes. */
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{32,}");

    /**
     * The hash for {@code fileName} in a checksum file: the line naming it, or the first hash when
     * no line names a file. A file with no hash, or whose lines name only other files, is an error,
     * never "no checksum": that would install unverified when a proxy answers with a text page. The algorithm
     * comes from the hash's length when it is unambiguous; else from the file (BSD), else
     * {@code declared} (what the source says), else sha256.
     */
    public static Checksum parse(String text, String fileName, String declared) {
        return parse(text, fileName, declared, false);
    }

    /**
     * Like {@link #parse(String, String, String)} for a checksum file named for its algorithm
     * ({@code .sha512}, {@code .sha256}): a BSD line naming a weaker one ({@code MD5 (file) = ...})
     * is refused. foojay's {@code checksum_type} is only a hint, so its checksums use the lenient form.
     */
    public static Checksum parseStrict(String text, String fileName, String declared) {
        return parse(text, fileName, declared, true);
    }

    private static Checksum parse(String text, String fileName, String declared, boolean strict) {
        String bsdAlgorithm = null;
        String hash = null;
        String firstHash = null;
        boolean namesFiles = false;
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
            namesFiles |= lineFile != null && !lineFile.isBlank();
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
        if (hash == null && firstHash == null) {
            throw new JkitException("the checksum file for " + fileName + " holds no checksum",
                    "a proxy or captive portal may have answered instead; try again on another network");
        }
        if (hash == null) {
            if (namesFiles && fileName != null) {
                throw new JkitException("the checksum file doesn't list " + fileName);
            }
            hash = firstHash;
        }
        if (strict && bsdAlgorithm != null && declared != null && !declared.isBlank()
                && strength(normalize(bsdAlgorithm)) < strength(normalize(declared))) {
            // "MD5 (file) = ..." in a .sha512 file: the label must not lower what the file promises
            throw new JkitException("the " + normalize(declared) + " checksum file for " + fileName + " holds a "
                    + normalize(bsdAlgorithm) + " hash instead");
        }
        return of(hash, bsdAlgorithm != null ? bsdAlgorithm : declared);
    }

    /** A hash given directly; null when it is empty. */
    public static Checksum of(String hash, String declared) {
        if (hash == null || hash.isBlank()) {
            return null;
        }
        String h = hash.strip().toLowerCase(Locale.ROOT);
        if (!h.matches("[0-9a-f]+")) {
            throw new JkitException("the published checksum isn't a hex hash: " + Visible.of(
                    h.length() > 40 ? h.substring(0, 40) + "…" : h),
                    "a proxy or captive portal may have answered instead; try again on another network");
        }
        String byLength = switch (h.length()) {
            case 32 -> "md5";
            case 40 -> "sha1";
            case 64 -> "sha256";
            case 96 -> "sha384";
            case 128 -> "sha512";
            default -> null;
        };
        String named = declared == null || declared.isBlank() ? null : normalize(declared);
        if (named != null && byLength != null && strength(byLength) < strength(named)) {
            // a .sha512 file holding an md5 must not pass as a (weak) checksum; a stronger hash than
            // declared is fine (foojay's checksum_type is sometimes wrong in that direction)
            throw new JkitException("the published " + named + " checksum is " + h.length()
                    + " hex digits long, which is " + byLength + ", not " + named);
        }
        String algorithm = byLength != null ? byLength : named != null ? named : "sha256";
        return new Checksum(algorithm, h);
    }

    private static int strength(String algorithm) {
        return switch (algorithm) {
            case "md5" -> 1;
            case "sha1" -> 2;
            case "sha256" -> 3;
            case "sha384" -> 4;
            case "sha512" -> 5;
            default -> 0;
        };
    }

    private static String normalize(String algorithm) {
        return algorithm.toLowerCase(Locale.ROOT).replace("-", "");
    }

    private static String baseName(String path) {
        String p = path.strip().replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }
}
