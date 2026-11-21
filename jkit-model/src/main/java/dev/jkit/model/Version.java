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

    private Version(String raw, int[] parts, int[] build, boolean preRelease) {
        this.raw = raw;
        this.parts = parts;
        this.build = build;
        this.preRelease = preRelease;
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
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        return new Version(text, numbers(core), numbers(buildText), pre);
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
     * {@code 21} matches {@code 21.0.13+9}; {@code 21.0.5} matches {@code 21.0.5.1+2}.
     */
    public boolean matches(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("latest")) {
            return true;
        }
        if (spec.equals(raw)) {
            return true;
        }
        Version want = parse(spec);
        if (want.parts.length == 0 || want.parts.length > parts.length) {
            return false;
        }
        for (int i = 0; i < want.parts.length; i++) {
            if (want.parts[i] != parts[i]) {
                return false;
            }
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
        return raw.compareTo(other.raw);
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
