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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The node's interactive console.
 *
 * <p>JLine earns its dependency through {@code printAbove}: log lines arrive
 * constantly and from other threads, and without it every one would land in the
 * middle of whatever is being typed.
 *
 * <p>The console has two modes. Normally it dispatches node commands. While
 * <em>attached</em> to a service it becomes that service's console — output is
 * streamed in and every line typed is forwarded to the server, exactly as if
 * you were sitting at its terminal. {@code #detach} (or Ctrl+C) returns.
 */
public final class NodeConsole implements AutoCloseable {

    /** Escapes recognised while attached. Prefixed so they cannot collide with server commands. */
    private static final Set<String> DETACH_WORDS = Set.of("#detach", "#exit", "#quit", "#back");

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

    /**
     * False when there is no real TTY — piped stdin, systemd, a Windows
     * service, a Docker container without {@code -t}.
     *
     * <p>In that case JLine redraws the prompt around every line it prints,
     * which is right for a terminal and useless in a log file: each entry ends
     * up prefixed with a stray prompt. So a non-interactive console drops the
     * prompt entirely and writes plainly, and the log reads as a log.
     */
    private final boolean interactive;

    private volatile boolean running = true;
    private volatile ConsoleAttachment attachment;

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
                .completer(new CommandCompleter(commands, this::isAttached))
                // Without this, a '!' anywhere in a typed command is treated as
                // a history expansion and mangles the line. Server commands
                // contain '!' often enough for that to matter.
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build();

        this.interactive = !terminal.getType().startsWith(Terminal.TYPE_DUMB);

        CloudLogger.sink(this::print);
    }

    /** Thread-safe: log lines may arrive from any Netty or process-pump thread. */
    public void print(String line) {
        try {
            if (interactive) {
                reader.printAbove(line);
            } else {
                synchronized (terminal) {
                    terminal.writer().println(line);
                    terminal.writer().flush();
                }
            }
        } catch (Exception exception) {
            // Terminal already torn down (shutdown race). Fall back to stdout.
            System.out.println(line);
        }
    }

    // ---------------------------------------------------------------- attach

    public boolean isAttached() {
        return attachment != null;
    }

    public UUID attachedService() {
        ConsoleAttachment current = attachment;
        return current == null ? null : current.serviceId();
    }

    /** Switches the console over to a service. Replaces any existing attachment. */
    public void attach(ConsoleAttachment next) {
        detach();
        attachment = next;

        print("");
        print(banner("── attached to " + next.serviceName()
                + " ── type #detach (or press Ctrl+C) to return ──"));
    }

    /** Ends the current attachment, if any. */
    public void detach() {
        ConsoleAttachment current = attachment;
        if (current == null) {
            return;
        }
        attachment = null;

        try {
            current.onDetach().run();
        } finally {
            print(banner("── detached from " + current.serviceName() + " ──"));
            print("");
        }
    }

    /** Detaches only if currently attached to this service. */
    public void detachIfAttachedTo(UUID serviceId) {
        ConsoleAttachment current = attachment;
        if (current != null && current.serviceId().equals(serviceId)) {
            print(banner("── " + current.serviceName() + " is gone ──"));
            detach();
        }
    }

    /** Prints a line of service output, if it belongs to the attached service. */
    public void printServiceLine(UUID serviceId, String line) {
        ConsoleAttachment current = attachment;
        if (current != null && current.serviceId().equals(serviceId)) {
            print(line);
        }
    }

    /** Replays a service's buffered backlog on attach. */
    public void printServiceBacklog(UUID serviceId, List<String> lines) {
        ConsoleAttachment current = attachment;
        if (current == null || !current.serviceId().equals(serviceId)) {
            return;
        }
        if (lines.isEmpty()) {
            print(banner("── no console history yet ──"));
            return;
        }
        lines.forEach(this::print);
        print(banner("── end of " + lines.size() + " buffered line(s), now live ──"));
    }

    // ------------------------------------------------------------------ loop

    /** Blocks the calling thread until the console stops. */
    public void run(Runnable onExit) {
        while (running) {
            ConsoleAttachment current = attachment;

            String line;
            try {
                line = reader.readLine(prompt(current));
            } catch (UserInterruptException exception) {
                // Ctrl+C detaches rather than killing the node — while you are
                // attached it reads as "leave this server", and shutting the
                // whole cloud down instead would be a nasty surprise.
                if (isAttached()) {
                    detach();
                    continue;
                }
                onExit.run();
                return;
            } catch (EndOfFileException exception) {
                // Ctrl+D, or stdin closed because we are running headless.
                onExit.run();
                return;
            }

            if (line == null) {
                continue;
            }

            if (current != null) {
                handleAttachedInput(current, line);
                continue;
            }

            if (!line.isBlank()) {
                commands.dispatch(line);
            }
        }
    }

    private void handleAttachedInput(ConsoleAttachment current, String line) {
        if (DETACH_WORDS.contains(line.trim().toLowerCase(java.util.Locale.ROOT))) {
            detach();
            return;
        }
        // A blank line is forwarded as-is: some server prompts expect one.
        current.input().accept(line);
    }

    private String prompt(ConsoleAttachment current) {
        if (!interactive) {
            return "";
        }
        return current == null ? PROMPT : servicePrompt(current.serviceName());
    }

    private static String servicePrompt(String serviceName) {
        return new AttributedStringBuilder()
                .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW))
                .append(serviceName)
                .style(AttributedStyle.DEFAULT)
                .append("> ")
                .toAnsi();
    }

    private static String banner(String text) {
        return new AttributedStringBuilder()
                .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE))
                .append(text)
                .style(AttributedStyle.DEFAULT)
                .toAnsi();
    }

    public void stop() {
        running = false;
    }

    @Override
    public void close() {
        running = false;
        attachment = null;
        CloudLogger.sink(System.out::println);
        try {
            terminal.close();
        } catch (IOException ignored) {
            // Nothing useful to do while shutting down.
        }
    }
}
