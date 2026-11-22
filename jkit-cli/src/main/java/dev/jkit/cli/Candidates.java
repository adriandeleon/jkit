package dev.jkit.cli;

import java.util.Iterator;
import java.util.List;

/** Fixed word lists for shell completion. Completion runs in the shell, so it can't ask foojay. */
final class Candidates {
    private Candidates() { }

    static final class Tools implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            return List.of("jdk", "maven", "gradle", "sbt", "graalvm").iterator();
        }
    }

    /** The common vendors; any other name foojay knows still works when typed in full. */
    static final class Vendors implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            return List.of("temurin", "corretto", "zulu", "liberica", "oracle", "openjdk", "graalvm-ce",
                    "graalvm", "liberica-nik", "mandrel", "sapmachine", "semeru", "microsoft", "dragonwell",
                    "kona", "jetbrains", "bisheng", "redhat").iterator();
        }
    }

    static final class Shells implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            return List.of("bash", "zsh", "fish", "pwsh").iterator();
        }
    }
}
