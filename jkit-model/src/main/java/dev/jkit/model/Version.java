package dev.jkit.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A version such as {@code 21.0.13+9}, {@code 3.9.11} or {@code 4.0.0-rc-7}: numeric components,
 * an optional pre-release suffix and an optional build number after {@code +}.
 */
public final class Version implements Comparable<Version> {
    private static final Pattern PRE = Pattern.compile(
            ".*(-ea|alpha|beta|-rc|rc-?\\d|-m\\d|milestone|snapshot).*");

    private final String raw;
    private final int[] parts;
    private final int[] build;
    private final boolean preRelease;
    /** The pre-release tag after the numbers, lower case: {@code rc-7}, {@code ea}; empty for a release. */
    private final String suffix;

    private Version(String raw, int[] parts, int[] build, boolean preRelease, String suffix) {
        this.raw = raw;
        this.parts = parts;
        this.build = build;
        this.preRelease = preRelease;
        this.suffix = suffix;
    }

    private static final java.util.regex.Pattern SAFE_TEXT =
            java.util.regex.Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+_~-]{0,63}");

    /**
     * Whether a version from a source is plain enough to become a folder name, a PATH entry and a
     * line of output: no path or PATH separators ({@code /}, {@code :}, {@code ;}), no spaces, no
     * control characters. Sources drop entries that fail it; a mirror could otherwise put
     * {@code ./bin} on PATH with a version such as {@code 3.9.99.:.}.
     */
    public static boolean isSafeText(String raw) {
        return raw != null && SAFE_TEXT.matcher(raw).matches();
    }

    public static Version parse(String raw) {
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        boolean pre = PRE.matcher(lower).matches();
        String core = text;
        String buildText = "";
        int plus = text.indexOf('+');
        if (plus >= 0) {
            core = text.substring(0, plus);
            buildText = text.substring(plus + 1);
        }
        // "8u472" -> 8.0.472
        core = core.replaceFirst("^(\\d+)u(\\d+)", "$1.0.$2");
        int dash = core.indexOf('-');
        String suffix = "";
        if (dash >= 0) {
            suffix = core.substring(dash + 1).toLowerCase(Locale.ROOT);
            core = core.substring(0, dash);
        }
        return new Version(text, numbers(core), numbers(buildText), pre, pre ? suffix : "");
    }

    /**
     * Java's pre-9 numbering as later versions write it: {@code 1.8} → {@code 8},
     * {@code 1.8.0_432} → {@code 8.0_432}. Other text is returned as is. For JDK versions only:
     * Gradle 1.8 is a real Gradle version.
     */
    public static String fromLegacyJava(String text) {
        if (text.matches("1\\.[1-8](\\D.*)?")) {
            return text.substring(2);
        }
        return text;
    }

    /** True when {@code text} starts with a number, as every version jkit installs does. */
    public static boolean looksLikeVersion(String text) {
        String t = text.trim();
        return !t.isEmpty() && Character.isDigit(t.charAt(0)) && parse(t).partCount() > 0;
    }

    private static int[] numbers(String text) {
        List<Integer> out = new ArrayList<>();
        for (String piece : text.split("[.\\-+_]")) {
            if (piece.isEmpty() || !piece.chars().allMatch(Character::isDigit)) {
                break;
            }
            try {
                out.add(Integer.parseInt(piece));
            } catch (NumberFormatException e) {
                break;
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public String raw() { return raw; }

    public boolean isPreRelease() { return preRelease; }

    /** Component {@code i}, or 0 when absent. */
    public int part(int i) { return i < parts.length ? parts[i] : 0; }

    public int partCount() { return parts.length; }

    /**
     * True when {@code spec} is empty, equals this version, or is a component-wise prefix:
     * {@code 21} matches {@code 21.0.13+9}; {@code 21.0.5} matches {@code 21.0.5.1+2}. Missing
     * components count as 0, so {@code 21.0.0} matches {@code 21+35}. A spec with a pre-release
     * tag matches only that tag: {@code 4.0.0-rc-4} doesn't match {@code 4.0.0-rc-7}.
     */
    public boolean matches(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("latest")) {
            return true;
        }
        if (spec.equals(raw)) {
            return true;
        }
        Version want = parse(spec);
        if (want.parts.length == 0) {
            return false;
        }
        for (int i = 0; i < want.parts.length; i++) {
            if (want.parts[i] != part(i)) {
                return false;
            }
        }
        if (want.preRelease && !want.suffix.equals(suffix)) {
            return false;
        }
        if (want.build.length > 0) {
            return Arrays.equals(want.build, Arrays.copyOf(build, want.build.length));
        }
        return true;
    }

    @Override
    public int compareTo(Version other) {
        int n = Math.max(parts.length, other.parts.length);
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(part(i), other.part(i));
            if (c != 0) {
                return c;
            }
        }
        if (preRelease != other.preRelease) {
            return preRelease ? -1 : 1;
        }
        int m = Math.max(build.length, other.build.length);
        for (int i = 0; i < m; i++) {
            int a = i < build.length ? build[i] : 0;
            int b = i < other.build.length ? other.build[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        int c = naturalCompare(suffix, other.suffix);
        return c != 0 ? c : raw.compareTo(other.raw);
    }

    /** Text compared run by run, numbers by value: rc-2 before rc-10, alpha before beta before rc. */
    static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ei = i;
                while (ei < a.length() && Character.isDigit(a.charAt(ei))) {
                    ei++;
                }
                int ej = j;
                while (ej < b.length() && Character.isDigit(b.charAt(ej))) {
                    ej++;
                }
                java.math.BigInteger na = new java.math.BigInteger(a.substring(i, ei));
                java.math.BigInteger nb = new java.math.BigInteger(b.substring(j, ej));
                int c = na.compareTo(nb);
                if (c != 0) {
                    return c;
                }
                i = ei;
                j = ej;
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version v && v.raw.equals(raw);
    }

    @Override
    public int hashCode() { return raw.hashCode(); }

    @Override
    public String toString() { return raw; }
}
