package dev.sirius.cloud.wrapper.process;

import com.google.gson.GsonBuilder;
import dev.sirius.cloud.api.service.ServiceType;
import dev.sirius.cloud.api.group.ServiceGroup;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.api.service.ServiceState;
import dev.sirius.cloud.wrapper.config.WrapperConfig;
import dev.sirius.cloud.wrapper.template.TemplateManager;
import dev.sirius.cloud.wrapper.util.FileUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One spawned Minecraft server: its directory, its process, and its console.
 *
 * <p>Everything here is written to behave identically on Linux and Windows.
 * The two places that genuinely differ are documented where they occur:
 * graceful shutdown in {@link #stop(boolean)} and directory cleanup in
 * {@link #cleanup()}.
 */
public final class ServiceProcess {

    private static final CloudLogger LOGGER = CloudLogger.of("Service");

    /** How long a service gets to save and exit before we escalate. */
    private static final int GRACEFUL_STOP_SECONDS = 40;

    /** How long {@code destroy()} gets before {@code destroyForcibly()}. */
    private static final int TERMINATE_SECONDS = 10;

    /**
     * Console backlog kept per service so attaching shows recent context
     * instead of an empty screen. Bounded, because a busy server would
     * otherwise turn this into a slow memory leak.
     */
    private static final int CONSOLE_BACKLOG_LINES = 200;

    /** Console lines included in a crash report. Enough for a stack trace, not a log dump. */
    private static final int CRASH_REPORT_LINES = 20;

    private final ServiceInfo info;
    private final ServiceGroup group;
    private final WrapperConfig config;
    private final Path directory;

    /** Resolved before construction; see JavaRuntimeResolver for why it is not the wrapper's own. */
    private final String javaExecutable;

    private final Consumer<String> consoleSink;
    private final BiConsumer<ServiceState, Integer> stateSink;
    private final BiConsumer<Integer, List<String>> crashSink;

    private final AtomicBoolean stopRequested = new AtomicBoolean();

    /** Guarded by itself. */
    private final Deque<String> consoleBacklog = new ArrayDeque<>(CONSOLE_BACKLOG_LINES);

    /** True only while somebody is attached; see {@link #streaming(boolean)}. */
    private volatile boolean streaming;

    private volatile Process process;
    private volatile BufferedWriter processInput;

    public ServiceProcess(ServiceInfo info,
                          ServiceGroup group,
                          WrapperConfig config,
                          Path directory,
                          String javaExecutable,
                          Consumer<String> consoleSink,
                          BiConsumer<ServiceState, Integer> stateSink,
                          BiConsumer<Integer, List<String>> crashSink) {
        this.info = info;
        this.group = group;
        this.config = config;
        this.directory = directory;
        this.javaExecutable = javaExecutable;
        this.consoleSink = consoleSink;
        this.stateSink = stateSink;
        this.crashSink = crashSink;
    }

    public ServiceInfo info() {
        return info;
    }

    public Path directory() {
        return directory;
    }

    public boolean isAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    /** Lays out the working directory and spawns the JVM. */
    public void start(Path serverJar,
                      Path pluginJar,
                      TemplateManager templates,
                      String token,
                      String nodeHost,
                      int nodePort,
                      String forwardingSecret) throws IOException {

        prepareDirectory(templates);

        Files.copy(serverJar, directory.resolve("server.jar"), StandardCopyOption.REPLACE_EXISTING);

        if (pluginJar != null && Files.isRegularFile(pluginJar)) {
            Path plugins = directory.resolve(group.type().pluginDirectory());
            Files.createDirectories(plugins);
            Files.copy(pluginJar, plugins.resolve("cloud-plugin.jar"), StandardCopyOption.REPLACE_EXISTING);
        } else {
            LOGGER.warn("No cloud plugin jar available; {} will never report READY", info.name());
        }

        if (group.type() == ServiceType.PROXY) {
            ServiceConfigurator.writeVelocityConfig(directory, info, group, forwardingSecret);
        } else {
            ServiceConfigurator.writeEula(directory);
            ServiceConfigurator.writeServerProperties(directory, info, group, config.serviceBindAddress());
            ServiceConfigurator.enableVelocityForwarding(directory, forwardingSecret);
        }

        writeConnectionFile(token, nodeHost, nodePort);

        spawn();
    }

    private void prepareDirectory(TemplateManager templates) throws IOException {
        if (!group.staticService()) {
            // Dynamic services start from a clean slate every time.
            FileUtil.deleteRecursively(directory);
        }
        Files.createDirectories(directory);
        templates.apply(group, directory);
    }

    /**
     * Drops the credentials the in-service plugin needs to dial home.
     *
     * <p>The token is single-use and bound to this service's id, so the file
     * being readable inside the service directory grants nothing beyond being
     * this service.
     */
    private void writeConnectionFile(String token, String nodeHost, int nodePort) throws IOException {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("serviceId", info.uniqueId().toString());
        connection.put("serviceName", info.name());
        connection.put("groupName", info.groupName());
        connection.put("token", token);
        connection.put("nodeHost", nodeHost);
        connection.put("nodePort", nodePort);

        FileUtil.writeString(directory.resolve("cloud-connection.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(connection));
    }

    private void spawn() throws IOException {
        List<String> command = new ArrayList<>();

        command.add(javaExecutable);
        command.add("-Xms" + group.minMemory() + "M");
        command.add("-Xmx" + info.memory() + "M");
        command.addAll(List.of(config.defaultJvmArguments()));
        command.addAll(group.jvmArguments());
        command.add("-jar");
        command.add("server.jar");

        // Velocity takes no arguments; "nogui" is a vanilla/Paper flag and
        // Velocity treats an unknown argument as an error.
        if (group.type() != ServiceType.PROXY) {
            command.add("nogui");
        }

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                // One stream to pump instead of two, and the interleaving
                // matches what an operator would see on a normal console.
                .redirectErrorStream(true);

        // Arguments are passed as a list, never a shell string: no quoting
        // rules to get wrong, and no difference between cmd.exe and sh.
        process = builder.start();

        processInput = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        startConsolePump();
        watchForExit();

        LOGGER.info("Spawned {} (pid {}, {}MB, {})",
                info.name(), process.pid(), info.memory(), javaExecutable);
        stateSink.accept(ServiceState.STARTING, -1);
    }

    private void startConsolePump() {
        Thread.ofVirtual().name("console-" + info.name()).start(() -> {
            // Explicit UTF-8: the platform's native encoding is not UTF-8 on
            // most Windows installs, and decoding server output with it turns
            // every non-ASCII character into a replacement mark.
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    // Strip a trailing CR: Windows-side tooling writes CRLF and
                    // readLine only consumes the LF when the stream is raw.
                    appendConsole(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                }
            } catch (IOException exception) {
                LOGGER.debug("Console pump for {} ended: {}", info.name(), exception.getMessage());
            }
        });
    }

    private void watchForExit() {
        process.onExit().thenAccept(exited -> {
            int exitCode = exited.exitValue();
            boolean expected = stopRequested.get();

            LOGGER.info("{} exited with code {}{}",
                    info.name(), exitCode, expected ? "" : " (unexpected)");

            if (!expected) {
                // Push the tail of the console unasked. Nobody was attached —
                // a service that fails during startup is dead before anyone
                // could be — so this is the only chance to say what went wrong.
                crashSink.accept(exitCode, tailOfConsole());
            }

            stateSink.accept(expected ? ServiceState.STOPPED : ServiceState.CRASHED, exitCode);
            cleanup();
        });
    }

    /**
     * Records a console line, and forwards it only if somebody is attached.
     *
     * <p>Service output is not pushed to the node by default. Thirty servers
     * each logging steadily would mean a continuous stream across the network
     * for the node to immediately throw away, and it would bury the node's own
     * output. The backlog is kept locally and replayed on attach instead.
     */
    private void appendConsole(String line) {
        synchronized (consoleBacklog) {
            if (consoleBacklog.size() >= CONSOLE_BACKLOG_LINES) {
                consoleBacklog.removeFirst();
            }
            consoleBacklog.addLast(line);
        }

        if (streaming) {
            consoleSink.accept(line);
        }
    }

    /** The last few console lines, for a crash report. */
    private List<String> tailOfConsole() {
        List<String> backlog = consoleBacklog();
        return backlog.size() <= CRASH_REPORT_LINES
                ? backlog
                : backlog.subList(backlog.size() - CRASH_REPORT_LINES, backlog.size());
    }

    /** Snapshot of the backlog, oldest first. */
    public List<String> consoleBacklog() {
        synchronized (consoleBacklog) {
            return List.copyOf(consoleBacklog);
        }
    }

    /** Turns live forwarding on or off. Set when a console attaches or detaches. */
    public void streaming(boolean streaming) {
        this.streaming = streaming;
    }

    public boolean streaming() {
        return streaming;
    }

    /** Writes a line to the service's stdin. */
    public void sendCommand(String command) {
        BufferedWriter writer = processInput;
        if (writer == null || !isAlive()) {
            return;
        }
        try {
            writer.write(command);
            writer.newLine();
            writer.flush();
        } catch (IOException exception) {
            LOGGER.debug("Could not write to {}: {}", info.name(), exception.getMessage());
        }
    }

    /**
     * Stops the service. Blocking — call it off the network thread.
     *
     * <p>The graceful path is to write the shutdown command to stdin and wait.
     * That is not a stylistic choice: {@code Process.destroy()} sends SIGTERM on
     * Linux, which Paper handles by saving and exiting, but on Windows it maps
     * to {@code TerminateProcess}, which cannot be caught or handled at all.
     * A "graceful" stop built on {@code destroy()} would therefore silently
     * corrupt worlds on Windows while looking perfectly fine on Linux. Writing
     * {@code stop} to stdin is correct on both, so there is one code path.
     */
    public void stop(boolean force) {
        stopRequested.set(true);

        Process current = process;
        if (current == null || !current.isAlive()) {
            return;
        }

        if (!force) {
            sendCommand(group.type().shutdownCommand());
            try {
                if (current.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                    return;
                }
                LOGGER.warn("{} ignored '{}' for {}s, terminating it",
                        info.name(), group.type().shutdownCommand(), GRACEFUL_STOP_SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        current.destroy();
        try {
            if (!current.waitFor(TERMINATE_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("{} survived termination, killing it", info.name());
                current.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    private void cleanup() {
        try {
            if (processInput != null) {
                processInput.close();
            }
        } catch (IOException ignored) {
            // Already closed with the process.
        }

        if (!group.staticService()) {
            // Deletion is retried internally: on Windows the OS may still hold
            // handles to this directory's files for a moment after exit.
            FileUtil.deleteRecursively(directory);
        }
    }
}
