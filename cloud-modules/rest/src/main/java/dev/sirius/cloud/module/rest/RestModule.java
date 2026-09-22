package dev.sirius.cloud.module.rest;

import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.module.CloudModule;
import dev.sirius.cloud.api.module.ModuleContext;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exposes the cloud over HTTP, and serves a panel for it.
 *
 * <p>The first module, and a deliberate choice of first: it uses nothing but
 * {@code CloudDriver}, so it doubles as a check that the module contract is
 * actually sufficient to build against before four more features depend on it.
 */
public final class RestModule implements CloudModule {

    private static final CloudLogger LOGGER = CloudLogger.of("RestApi");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RestServer server;

    @Override
    public void onEnable(ModuleContext context) {
        RestConfig config;
        Path configFile = context.dataDirectory().resolve("config.json");
        try {
            config = loadConfig(configFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + configFile + ": " + exception.getMessage());
        }

        server = new RestServer(config, context.driver());
        try {
            server.start();
        } catch (IOException exception) {
            // Almost always the port being taken. Say that, rather than a stack
            // trace, and leave the rest of the node running.
            throw new IllegalStateException("Could not listen on "
                    + config.bindAddress() + ":" + config.port() + " - " + exception.getMessage());
        }

        LOGGER.info("API on http://{}:{}/api/v1/", config.bindAddress(), config.port());
        if (config.panel()) {
            LOGGER.info("Panel on http://{}:{}/", config.bindAddress(), config.port());
        }
        LOGGER.info("API token: {}", config.token());
        LOGGER.info("It is stored in modules/rest/config.json; the panel asks for it once.");

        if (config.isExposed()) {
            // Worth saying loudly: this endpoint can start and stop servers and
            // move players, and the token is the only thing in front of it.
            LOGGER.warn("The API is bound to {}, not loopback - anything that can reach it "
                    + "can control this cloud.", config.bindAddress());
            LOGGER.warn("Put it behind a reverse proxy with TLS, or bind it to 127.0.0.1.");
        }
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop();
            LOGGER.info("API stopped");
        }
    }

    /**
     * Loads the config, writing a fresh one — token and all — on first start.
     *
     * <p>Rewritten after loading too, so a config from an older version gains
     * new fields with their defaults instead of silently using nulls.
     */
    private static RestConfig loadConfig(Path path) throws IOException {
        RestConfig config = null;

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, RestConfig.class);
            }
        }
        if (config == null) {
            config = new RestConfig();
        }
        if (config.token() == null || config.token().isBlank()) {
            config.token(java.util.UUID.randomUUID().toString());
        }

        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
        return config;
    }
}
