package dev.sirius.cloud.wrapper.setup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Question-and-answer prompting for the wrapper.
 *
 * <p>Plain streams rather than JLine: the wrapper has no interactive console of
 * its own, setup runs before anything starts logging from another thread, and
 * a line editor is not worth a megabyte in every wrapper jar for six questions.
 *
 * <p>End-of-input is treated as "accept the default" throughout, which is what
 * makes this safe when stdin turns out not to be a terminal after all — the
 * prompts answer themselves instead of blocking forever.
 */
public final class Prompter {

    private final BufferedReader reader;
    private final PrintStream out;
    private boolean inputExhausted;

    public Prompter() {
        this.reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        this.out = System.out;
    }

    /**
     * Whether it is worth asking at all.
     *
     * <p>A null console means stdin is redirected. Newer JVMs may report one
     * regardless, which is harmless here: the EOF handling below turns every
     * question into its default rather than hanging.
     */
    public boolean isInteractive() {
        return System.console() != null;
    }

    public void print(String line) {
        out.println(line);
    }

    public void heading(String text) {
        out.println();
        out.println("── " + text + " ──");
    }

    public String ask(String question, String defaultValue) {
        if (inputExhausted) {
            return defaultValue;
        }

        out.print("  " + question + (defaultValue.isEmpty() ? ": " : " [" + defaultValue + "]: "));
        out.flush();

        try {
            String line = reader.readLine();
            if (line == null) {
                inputExhausted = true;
                out.println();
                return defaultValue;
            }
            return line.isBlank() ? defaultValue : line.trim();
        } catch (IOException exception) {
            inputExhausted = true;
            return defaultValue;
        }
    }

    public boolean confirm(String question, boolean defaultYes) {
        String answer = ask(question, defaultYes ? "Y/n" : "y/N")
                .trim().toLowerCase(Locale.ROOT);
        if (answer.startsWith("y")) {
            return true;
        }
        if (answer.startsWith("n")) {
            return false;
        }
        return defaultYes;
    }

    public int askInt(String question, int defaultValue, int minimum, int maximum) {
        while (true) {
            String answer = ask(question, String.valueOf(defaultValue));
            try {
                int value = Integer.parseInt(answer.trim());
                if (value < minimum || value > maximum) {
                    out.println("  Please enter a number between " + minimum + " and " + maximum + ".");
                    continue;
                }
                return value;
            } catch (NumberFormatException exception) {
                if (inputExhausted) {
                    return defaultValue;
                }
                out.println("  '" + answer + "' is not a number.");
            }
        }
    }
}
