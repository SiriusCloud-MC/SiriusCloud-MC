package dev.sirius.cloud.protocol.packet;

import dev.sirius.cloud.protocol.packet.impl.AcknowledgePacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleCommandPacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleHistoryPacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleLinePacket;
import dev.sirius.cloud.protocol.packet.impl.ConsoleSubscribePacket;
import dev.sirius.cloud.protocol.packet.impl.GroupListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.GroupListResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakePacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakeResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.HeartbeatPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerConnectRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerDisconnectPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerKickPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerListResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerLoginPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerMessagePacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerSnapshotPacket;
import dev.sirius.cloud.protocol.packet.impl.PlayerSwitchServerPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceAvailabilityPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceCrashReportPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceListRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.ServicePlayerUpdatePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceListResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceReadyPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartRequestPacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStartResponsePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStateUpdatePacket;
import dev.sirius.cloud.protocol.packet.impl.ServiceStopPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Maps packet classes to wire ids and back.
 *
 * <p>Registration is explicit and lives in one place on purpose: the entire
 * protocol is readable in a single screen, ids never shift because someone
 * renamed a class, and there is no classpath scanning to slow startup or
 * misbehave inside a plugin classloader.
 *
 * <p>Ids are grouped by direction. Never renumber an existing id — append.
 */
public final class PacketRegistry {

    private final Map<Integer, Supplier<? extends Packet>> byId = new HashMap<>();
    private final Map<Class<? extends Packet>, Integer> byClass = new HashMap<>();

    public <T extends Packet> PacketRegistry register(int id, Class<T> type, Supplier<T> factory) {
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("Packet id 0x" + Integer.toHexString(id) + " is already taken by "
                    + byId.get(id).get().getClass().getSimpleName());
        }
        if (byClass.containsKey(type)) {
            throw new IllegalArgumentException(type.getSimpleName() + " is already registered");
        }
        byId.put(id, factory);
        byClass.put(type, id);
        return this;
    }

    public int idOf(Packet packet) {
        Integer id = byClass.get(packet.getClass());
        if (id == null) {
            throw new IllegalArgumentException("Unregistered packet type " + packet.getClass().getName());
        }
        return id;
    }

    public Packet create(int id) {
        Supplier<? extends Packet> factory = byId.get(id);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown packet id 0x" + Integer.toHexString(id));
        }
        return factory.get();
    }

    public int size() {
        return byId.size();
    }

    /** The protocol every SiriusCloud process speaks. */
    public static PacketRegistry standard() {
        return new PacketRegistry()
                // 0x00-0x0F — connection lifecycle
                .register(0x01, HandshakePacket.class, HandshakePacket::new)
                .register(0x02, HandshakeResponsePacket.class, HandshakeResponsePacket::new)
                .register(0x03, HeartbeatPacket.class, HeartbeatPacket::new)
                .register(0x04, AcknowledgePacket.class, AcknowledgePacket::new)

                // 0x10-0x1F — node to wrapper
                .register(0x10, ServiceStartPacket.class, ServiceStartPacket::new)
                .register(0x11, ServiceStopPacket.class, ServiceStopPacket::new)
                .register(0x12, ConsoleCommandPacket.class, ConsoleCommandPacket::new)
                .register(0x13, ConsoleSubscribePacket.class, ConsoleSubscribePacket::new)
                .register(0x14, ServiceAvailabilityPacket.class, ServiceAvailabilityPacket::new)

                // 0x20-0x2F — wrapper/service to node
                .register(0x20, ServiceStateUpdatePacket.class, ServiceStateUpdatePacket::new)
                .register(0x21, ServiceReadyPacket.class, ServiceReadyPacket::new)
                .register(0x22, ConsoleLinePacket.class, ConsoleLinePacket::new)
                .register(0x23, ConsoleHistoryPacket.class, ConsoleHistoryPacket::new)
                .register(0x24, ServiceCrashReportPacket.class, ServiceCrashReportPacket::new)
                .register(0x25, ServicePlayerUpdatePacket.class, ServicePlayerUpdatePacket::new)

                // 0x30-0x3F — driver queries
                .register(0x30, ServiceListRequestPacket.class, ServiceListRequestPacket::new)
                .register(0x31, ServiceListResponsePacket.class, ServiceListResponsePacket::new)
                .register(0x32, ServiceStartRequestPacket.class, ServiceStartRequestPacket::new)
                .register(0x33, ServiceStartResponsePacket.class, ServiceStartResponsePacket::new)
                .register(0x34, GroupListRequestPacket.class, GroupListRequestPacket::new)
                .register(0x35, GroupListResponsePacket.class, GroupListResponsePacket::new)

                // 0x40-0x4F - players
                .register(0x40, PlayerLoginPacket.class, PlayerLoginPacket::new)
                .register(0x41, PlayerDisconnectPacket.class, PlayerDisconnectPacket::new)
                .register(0x42, PlayerSwitchServerPacket.class, PlayerSwitchServerPacket::new)
                .register(0x43, PlayerSnapshotPacket.class, PlayerSnapshotPacket::new)
                .register(0x44, PlayerConnectRequestPacket.class, PlayerConnectRequestPacket::new)
                .register(0x45, PlayerMessagePacket.class, PlayerMessagePacket::new)
                .register(0x46, PlayerKickPacket.class, PlayerKickPacket::new)
                .register(0x47, PlayerListRequestPacket.class, PlayerListRequestPacket::new)
                .register(0x48, PlayerListResponsePacket.class, PlayerListResponsePacket::new);
    }
}
