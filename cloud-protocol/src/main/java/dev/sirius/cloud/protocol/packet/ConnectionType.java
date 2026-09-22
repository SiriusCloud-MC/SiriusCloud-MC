package dev.sirius.cloud.protocol.packet;

/** Who is on the other end of a connection. Established during the handshake. */
public enum ConnectionType {

    /** A machine agent that spawns service processes. Authenticates with the shared secret. */
    WRAPPER,

    /** A running Minecraft server or proxy. Authenticates with its one-time token. */
    SERVICE,

    /** An external tool or module. Authenticates with the shared secret. */
    API
}
