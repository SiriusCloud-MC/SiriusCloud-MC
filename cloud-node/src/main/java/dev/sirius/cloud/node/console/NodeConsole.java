package dev.sirius.cloud.node.console;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.node.command.CommandManager;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The node's interactive console.
 *
 * <p>JLine earns its dependency through one feature: {@code printAbove}. Log
 * lines and piped service output arrive constantly and from other threads, and
 * without it every one of them would land in the middle of whatever the user is
 * typing. Here they scroll past above a prompt that stays put.
 */
public final class NodeConsole implements AutoCloseable {

    private static final String PROMPT = new AttributedStringBuilder()
            .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
            .append("sirius")
            .style(AttributedStyle.DEFAULT)
            .append("@")
            .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN))
            .append("node")
            .style(AttributedStyle.DEFAULT)
            .append("> ")
            .toAnsi();

    private final CommandManager commands;
    private final Terminal terminal;
    private final LineReader reader;

    private volatile boolean running = true;

    public NodeConsole(CommandManager commands, Path historyFile) throws IOException {
        this.commands = commands;

        this.terminal = TerminalBuilder.builder()
                .name("SiriusCloud")
                .system(true)
                // Falls back to a plain stream when there is no real TTY —
                // running under systemd, a Windows service, or a piped shell
                // should degrade, not throw.
                .dumb(true)
                .build();

        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("SiriusCloud")
                .completer(new CommandCompleter(commands))
                // Without this, a '!' anywhere in a typed command is treated as
                // a history expansion and mangles the line.
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build();

        CloudLogger.sink(this::print);
    }

    /** Thread-safe: log lines may arrive from any Netty or process-pump thread. */
    public void print(String line) {
        try {
            reader.printAbove(line);
        } catch (Exception exception) {
            // Terminal already torn down (shutdown race). Fall back to stdout.
            System.out.println(line);
        }
    }

    public Consumer<String> sink() {
        return this::print;
    }

    /** Blocks the calling thread until the console stops. */
    public void run(Runnable onExit) {
        while (running) {
            String line;
            try {
                line = reader.readLine(PROMPT);
            } catch (UserInterruptException exception) {
                // Ctrl+C
                onExit.run();
                return;
            } catch (EndOfFileException exception) {
                // Ctrl+D, or stdin closed because we are running headless.
                onExit.run();
                return;
            }

            if (line == null || line.isBlank()) {
                continue;
            }
            commands.dispatch(line);
        }
    }

    public void stop() {
        running = false;
    }

    @Override
    public void close() {
        running = false;
        CloudLogger.sink(System.out::println);
        try {
            terminal.close();
        } catch (IOException ignored) {
            // Nothing useful to do while shutting down.
        }
    }
}
