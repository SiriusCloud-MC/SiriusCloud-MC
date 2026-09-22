package dev.sirius.cloud.wrapper.setup;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.SystemMemory;
import dev.sirius.cloud.driver.config.JsonConfig;
import dev.sirius.cloud.wrapper.config.WrapperConfig;
import dev.sirius.cloud.wrapper.java.JavaRuntime;
import dev.sirius.cloud.wrapper.java.JavaRuntimeResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Interactive configuration for the wrapper on first start.
 *
 * <p>Exists mostly for one question: the shared secret. Without it, standing up
 * a cloud meant reading a generated UUID off the node's console and hand-typing
 * it into a JSON file — the single most error-prone step in the whole setup,
 * and one whose failure mode was an endless rejected-handshake loop.
 */
public final class WrapperSetup {

    private static final CloudLogger LOGGER = CloudLogger.of("Setup");

    private final Prompter prompter;
    private final WrapperConfig config;
    private final Path configFile;
    private final JavaRuntimeResolver javaRuntimes;

    public WrapperSetup(Prompter prompter,
                        WrapperConfig config,
                        Path configFile,
                        JavaRuntimeResolver javaRuntimes) {
        this.prompter = prompter;
        this.config = config;
        this.configFile = configFile;
        this.javaRuntimes = javaRuntimes;
    }

    public void run() throws IOException {
        if (!prompter.isInteractive()) {
            LOGGER.info("No terminal to ask on; using defaults from config.json.");
            return;
        }

        prompter.heading("Wrapper setup");
        prompter.print("  This machine runs the servers. Everything lands in");
        prompter.print("  wrapper/config.json, and Enter accepts the suggested value.");
        prompter.print("");

        config.name(prompter.ask("Name for this wrapper", config.name()));

        prompter.print("");
        prompter.print("  The node prints its address and secret when it starts.");
        config.nodeHost(prompter.ask("Node address", config.nodeHost()));
        config.nodePort(prompter.askInt("Node port", config.nodePort(), 1, 65535));

        String secret = prompter.ask("Node secret", config.secret());
        while (secret.isBlank()) {
            prompter.print("  Without it the node will reject this wrapper.");
            prompter.print("  Copy the 'Wrapper secret' line from the node's console.");
            String retry = prompter.ask("Node secret", "");
            if (retry.isBlank()) {
                // Refusing to continue would be worse than letting them fix the
                // file by hand; the rejection message already says what to do.
                LOGGER.warn("Left blank. Set 'secret' in wrapper/config.json before starting again.");
                break;
            }
            secret = retry;
        }
        config.secret(secret);

        prompter.print("");
        prompter.print("  This machine has " + SystemMemory.describeTotal() + " of RAM.");
        config.maxMemory(prompter.askInt(
                "How much may this machine use for servers, in MB",
                SystemMemory.suggestedBudgetMegabytes(config.maxMemory()), 256, 1024 * 1024));

        prompter.print("");
        askJavaRuntime();

        JsonConfig.save(configFile, config);
        prompter.print("");
        LOGGER.info("Saved. Wrapper '{}' will connect to {}:{} with a {}MB budget.",
                config.name(), config.nodeHost(), config.nodePort(), config.maxMemory());
    }

    /**
     * Asks which JVM services should use, showing what is actually installed
     * so the answer is a choice rather than a guess.
     */
    private void askJavaRuntime() {
        List<JavaRuntime> installed = javaRuntimes.discover();

        if (installed.isEmpty()) {
            prompter.print("  No JVMs were detected on this machine.");
        } else {
            prompter.print("  JVMs found: " + installed.stream()
                    .map(runtime -> "Java " + runtime.feature())
                    .distinct()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("none"));
        }
        prompter.print("  Minecraft 26.x and newer needs Java 25 or above.");

        config.serviceJavaVersion(prompter.askInt(
                "Minimum Java version for servers", config.serviceJavaVersion(), 8, 99));

        Optional<JavaRuntime> chosen = javaRuntimes.find(config.serviceJavaVersion());
        if (chosen.isPresent()) {
            prompter.print("  Servers will run on " + chosen.get() + ".");
            return;
        }

        prompter.print("  Nothing installed satisfies that.");
        if (prompter.confirm("Point at a JVM manually instead?", false)) {
            config.javaExecutable(prompter.ask("Full path to the java binary", ""));
        } else {
            LOGGER.warn("Install Java {} before starting servers.", config.serviceJavaVersion());
        }
    }
}
