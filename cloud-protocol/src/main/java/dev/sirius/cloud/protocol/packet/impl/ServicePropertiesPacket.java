package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Anyone to node: merge these properties into a service. A null value removes.
 *
 * <p>Sent as a query so the caller learns whether it was refused - writing
 * another service's properties, or a reserved {@code cloud:} key.
 */
public final class ServicePropertiesPacket extends Packet {

    private UUID serviceId;
    private Map<String, String> properties;

    public ServicePropertiesPacket() {
    }

    public ServicePropertiesPacket(UUID serviceId, Map<String, String> properties) {
        this.serviceId = serviceId;
        this.properties = properties;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public Map<String, String> properties() {
        return properties == null ? Map.of() : properties;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeUniqueId(serviceId);
        buf.writeVarInt(properties().size());
        for (Map.Entry<String, String> entry : properties().entrySet()) {
            buf.writeString(entry.getKey());
            buf.writeNullable(entry.getValue(), DataBuf::writeString);
        }
    }

    @Override
    public void read(DataBuf buf) {
        this.serviceId = buf.readUniqueId();
        int size = buf.readVarInt();
        // LinkedHashMap because it tolerates null values, which mean "remove".
        this.properties = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            properties.put(buf.readString(), buf.readNullable(DataBuf::readString));
        }
    }
}
