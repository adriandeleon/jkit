package dev.jkit.model;

import java.util.Collection;
import java.util.Locale;

/** "Did you mean" suggestions for mistyped names. */
public final class Suggest {
    private Suggest() { }

    /**
     * The candidate closest to {@code input}, or null when none is close enough. Close means an
     * edit distance of at most 1 for names up to 3 letters and 2 (or a third of the length) for
     * longer ones, so {@code correto} finds {@code corretto}, {@code zuul} finds {@code zulu}, and
     * {@code foo} finds nothing. A candidate that starts with the input ({@code graal}) counts too.
     */
    public static String closest(String input, Collection<String> candidates) {
        String in = input.toLowerCase(Locale.ROOT);
        if (in.length() >= 3) {
            for (String c : candidates) {
                if (c.toLowerCase(Locale.ROOT).startsWith(in)) {
                    return c;
                }
            }
        }
        int limit = in.length() <= 3 ? 1 : Math.max(2, in.length() / 3);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = distance(in, c.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        return bestDistance <= limit ? best : null;
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
