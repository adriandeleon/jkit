package dev.jkit.cli;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Shows control characters as text ({@code \x1b}) instead of sending them to the terminal. Text jkit
 * prints comes partly from outside: project files in a cloned repo (printed on every {@code cd}),
 * foojay's vendor and version names, file names. An escape sequence among them could clear the
 * screen, retitle the window, plant a link or, in some terminals, write the clipboard. Tab, newline
 * and carriage return (the progress bar) pass. Works on UTF-8 bytes: C1 controls are {@code C2 80..9F}.
 */
final class SafeOutput extends FilterOutputStream {
    private boolean pendingC2;

    SafeOutput(OutputStream out) {
        super(out);
    }

    /** A print stream over {@code out} that writes through this filter, in {@code out}'s charset. */
    static PrintStream wrap(PrintStream out) {
        return new PrintStream(new SafeOutput(out), true, out.charset());
    }

    @Override
    public void write(int b) throws IOException {
        b &= 0xff;
        if (pendingC2) {
            pendingC2 = false;
            if (b >= 0x80 && b <= 0x9f) {
                escape(b);
                return;
            }
            out.write(0xc2);
        }
        if (b == 0xc2) {
            pendingC2 = true;
        } else if ((b < 0x20 && b != '\t' && b != '\n' && b != '\r') || b == 0x7f) {
            escape(b);
        } else {
            out.write(b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {
            write(b[i]);
        }
    }

    private void escape(int b) throws IOException {
        out.write(String.format("\\x%02x", b).getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public void flush() throws IOException {
        if (pendingC2) {
            pendingC2 = false;
            out.write(0xc2); // a lone C2 at the end of a write: not a C1 control after all
        }
        super.flush();
    }
}
