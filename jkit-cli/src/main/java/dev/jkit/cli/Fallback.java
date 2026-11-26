package dev.jkit.cli;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A charset that writes what its base charset can't hold as ASCII instead of {@code ?}. The
 * Windows console's code pages (437, 850) have no {@code → … ✓ ○ ●}, and LANG=C has no
 * non-ASCII at all, so jkit's text arrives as {@code ?} there otherwise: {@code →} is written
 * {@code ->}, a letter with an accent is the letter without it, and anything else is still
 * {@code ?}. Characters the charset can encode are written as they are.
 *
 * <p>For {@code --json}, a character the charset can't hold is written as a {@code \}{@code uXXXX}
 * escape instead: outside strings JSON is all ASCII, so the character is in a string, where
 * the escape stands for exactly that character. A substitute would change the data.
 */
final class Fallback extends Charset {
    /** Each one's ASCII stand-in: the characters jkit's own messages use, and common punctuation. */
    private static final Map<Character, String> ASCII = Map.ofEntries(
            Map.entry('\u2192', "->"), // →
            Map.entry('\u2190', "<-"), // ←
            Map.entry('\u2191', "^"), // ↑
            Map.entry('\u2193', "v"), // ↓
            Map.entry('\u2026', "..."), // …
            Map.entry('\u2713', "+"), // ✓
            Map.entry('\u2717', "x"), // ✗
            Map.entry('\u25CB', "o"), // ○
            Map.entry('\u25CF', "*"), // ●
            Map.entry('\u2022', "*"), // •
            Map.entry('\u203E', "~"), // ‾
            Map.entry('\u00B7', "-"), // ·
            Map.entry('\u2013', "-"), // –
            Map.entry('\u2014', "--"), // —
            Map.entry('\u2018', "'"), // ‘
            Map.entry('\u2019', "'"), // ’
            Map.entry('\u201C', "\""), // “
            Map.entry('\u201D', "\""), // ”
            Map.entry('\u00D7', "x"), // ×
            Map.entry('\u2264', "<="), // ≤
            Map.entry('\u2265', ">="), // ≥
            Map.entry('\u00A0', " ")); // no-break space

    /** U+00C0 to U+00FF without their accents (À is A, ñ is n), for a charset with no letters past ASCII. */
    static final String LATIN1_LETTERS = "AAAAAAACEEEEIIIIDNOOOOOxOUUUUY?saaaaaaaceeeeiiiidnooooo?ouuuuy?y";

    private final Charset base;
    private final boolean json;

    private Fallback(Charset base, boolean json) {
        super("x-jkit-" + (json ? "json-" : "") + base.name(), null);
        this.base = base;
        this.json = json;
    }

    /**
     * {@code charset}, or for one that can't hold every character (anything but UTF-8 and
     * UTF-16), the same with ASCII stand-ins ({@code json}: escapes) for what it can't hold.
     */
    static Charset of(Charset charset, boolean json) {
        String name = charset.name();
        if (name.startsWith("UTF-") || name.startsWith("x-jkit-") || !asciiCompatible(charset)) {
            return charset;
        }
        return new Fallback(charset, json);
    }

    /** Whether ASCII text is the same bytes in {@code charset}, as in every console code page. */
    private static boolean asciiCompatible(Charset charset) {
        if (!charset.canEncode()) {
            return false;
        }
        try {
            String ascii = "azAZ09 -~\n";
            ByteBuffer bytes = charset.newEncoder().encode(CharBuffer.wrap(ascii));
            byte[] written = new byte[bytes.remaining()];
            bytes.get(written);
            return new String(written, StandardCharsets.ISO_8859_1).equals(ascii);
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    @Override
    public boolean contains(Charset cs) {
        return base.contains(cs);
    }

    @Override
    public CharsetDecoder newDecoder() {
        return base.newDecoder();
    }

    @Override
    public CharsetEncoder newEncoder() {
        return new Encoder();
    }

    /** What to write for {@code text} (one character, or a surrogate pair) that the base can't hold. */
    String standIn(String text) {
        if (json) {
            StringBuilder sb = new StringBuilder();
            for (char c : text.toCharArray()) {
                sb.append(String.format("\\u%04x", (int) c));
            }
            return sb.toString();
        }
        if (text.length() == 1) {
            char c = text.charAt(0);
            String known = ASCII.get(c);
            if (known != null) {
                return known;
            }
            if (c >= 0xc0 && c <= 0xff) {
                return String.valueOf(LATIN1_LETTERS.charAt(c - 0xc0));
            }
        }
        return "?";
    }

    private final class Encoder extends CharsetEncoder {
        /** Only asked what the base can hold; never encodes, so it is never mid-encoding. */
        private final CharsetEncoder check = base.newEncoder();
        private final CharsetEncoder encoder = base.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);

        Encoder() {
            // a stand-in is at most 6 characters per char: \\u00e9
            super(Fallback.this, base.newEncoder().averageBytesPerChar(), base.newEncoder().maxBytesPerChar() * 6);
        }

        @Override
        protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) {
            while (in.hasRemaining()) {
                int start = in.position();
                char c = in.get();
                if (c < 0x80) {
                    if (!out.hasRemaining()) {
                        in.position(start);
                        return CoderResult.OVERFLOW;
                    }
                    out.put((byte) c);
                    continue;
                }
                String text = String.valueOf(c);
                if (Character.isHighSurrogate(c)) {
                    if (!in.hasRemaining()) {
                        in.position(start); // the low half comes with the next write
                        return CoderResult.UNDERFLOW;
                    }
                    char low = in.get();
                    if (Character.isLowSurrogate(low)) {
                        text = text + low;
                    } else {
                        in.position(start + 1);
                    }
                }
                boolean encodable = !Character.isSurrogate(text.charAt(text.length() - 1)) || text.length() == 2;
                String written = encodable && check.canEncode(text) ? text : standIn(text);
                ByteBuffer bytes;
                try {
                    encoder.reset();
                    bytes = encoder.encode(CharBuffer.wrap(written));
                } catch (CharacterCodingException e) {
                    bytes = ByteBuffer.wrap(new byte[] {'?'}); // REPLACE above: not thrown
                }
                if (out.remaining() < bytes.remaining()) {
                    in.position(start);
                    return CoderResult.OVERFLOW;
                }
                out.put(bytes);
            }
            return CoderResult.UNDERFLOW;
        }
    }
}
