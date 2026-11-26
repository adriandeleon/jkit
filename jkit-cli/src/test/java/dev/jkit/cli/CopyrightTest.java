package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class CopyrightTest {

    @Test
    void printsAuthorEmailAndWebsite() {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        int code;
        try {
            code = Main.run("copyright");
        } finally {
            System.setOut(original);
        }
        String out = buf.toString();
        assertThat(code).isZero();
        assertThat(out).contains("Copyright (c) 2026 Adrián Arturo De León Saldivar <adrian.deleon@gmail.com>")
                .contains("MIT License")
                .contains("https://jkit-project.dev");
    }
}
