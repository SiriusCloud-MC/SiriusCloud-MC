package dev.sirius.cloud.protocol.connection;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkClientTest {

    @Test
    void formatsEndpoints() {
        assertEquals("10.0.0.2:1420", NetworkClient.endpoint("10.0.0.2", 1420));
        assertEquals("node.example:1420", NetworkClient.endpoint("node.example", 1420));
        assertEquals("[::1]:1420", NetworkClient.endpoint("::1", 1420));
        assertEquals("[::1]:1420", NetworkClient.endpoint("[::1]", 1420));
    }

    @Test
    void parsesWhatItFormats() {
        InetSocketAddress v4 = NetworkClient.parse("10.0.0.2:1421");
        assertEquals("10.0.0.2", v4.getHostString());
        assertEquals(1421, v4.getPort());

        InetSocketAddress v6 = NetworkClient.parse(NetworkClient.endpoint("::1", 1420));
        assertEquals(1420, v6.getPort());
        assertEquals(true, v6.getAddress().isLoopbackAddress());
    }

    @Test
    void rejectsWhatIsNotHostAndPort() {
        assertThrows(IllegalArgumentException.class, () -> NetworkClient.parse("no-port"));
        assertThrows(IllegalArgumentException.class, () -> NetworkClient.parse("host:"));
    }
}
