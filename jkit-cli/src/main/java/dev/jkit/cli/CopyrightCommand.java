package dev.jkit.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "copyright", description = "Prints copyright and contact information.")
final class CopyrightCommand implements Callable<Integer> {

    static final String YEAR = "2026";
    static final String AUTHOR = "Adrián Arturo De León Saldivar";
    static final String EMAIL = "adrian.deleon@gmail.com";
    static final String WEBSITE = "https://jkit-project.dev";

    @Override
    public Integer call() {
        System.out.println("jkit " + VersionProvider.version());
        System.out.println("Copyright (c) " + YEAR + " " + AUTHOR + " <" + EMAIL + ">");
        System.out.println("Released under the MIT License.");
        System.out.println("Website: " + WEBSITE);
        return 0;
    }
}
