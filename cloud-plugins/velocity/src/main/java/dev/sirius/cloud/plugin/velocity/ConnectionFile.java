package dev.sirius.cloud.plugin.velocity;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The {@code cloud-connection.json} the wrapper drops into a service directory
 * just before spawning it.
 *
 * <p>Its absence is not an error: it simply means this server was started by
 * hand rather than by the cloud, and the plugin stays dormant.
 */
public final class ConnectionFile {

    private String serviceId;
    private String serviceName;
    private String groupName;
    private String token;
    private String nodeHost;
    private int nodePort;

    public static ConnectionFile read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ConnectionFile file = new Gson().fromJson(reader, ConnectionFile.class);
            if (file == null || file.serviceId == null || file.token == null) {
                throw new IOException("cloud-connection.json is missing required fields");
            }
            return file;
        }
    }

    public UUID serviceId() {
        return UUID.fromString(serviceId);
    }

    public String serviceName() {
        return serviceName;
    }

    public String groupName() {
        return groupName;
    }

    public String token() {
        return token;
    }

    public String nodeHost() {
        return nodeHost;
    }

    public int nodePort() {
        return nodePort;
    }
}
