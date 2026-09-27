package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reply to {@link DataRequestPacket}. Which fields matter depends on the operation. */
public final class DataResponsePacket extends Packet {

    private boolean ok;
    private String error;
    private String value;
    private boolean flag;
    private long number;
    private Map<String, String> entries;

    public DataResponsePacket() {
    }

    public static DataResponsePacket failure(String error) {
        DataResponsePacket packet = new DataResponsePacket();
        packet.ok = false;
        packet.error = error;
        return packet;
    }

    public static DataResponsePacket success() {
        DataResponsePacket packet = new DataResponsePacket();
        packet.ok = true;
        return packet;
    }

    public DataResponsePacket value(String value) {
        this.value = value;
        return this;
    }

    public DataResponsePacket flag(boolean flag) {
        this.flag = flag;
        return this;
    }

    public DataResponsePacket number(long number) {
        this.number = number;
        return this;
    }

    public DataResponsePacket entries(Map<String, String> entries) {
        this.entries = entries;
        return this;
    }

    public boolean ok() {
        return ok;
    }

    public String error() {
        return error == null ? "" : error;
    }

    public String value() {
        return value;
    }

    public boolean flag() {
        return flag;
    }

    public long number() {
        return number;
    }

    public Map<String, String> entries() {
        return entries == null ? Map.of() : entries;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeBoolean(ok)
                .writeString(error())
                .writeNullable(value, DataBuf::writeString)
                .writeBoolean(flag)
                .writeLong(number);
        buf.writeVarInt(entries().size());
        for (Map.Entry<String, String> entry : entries().entrySet()) {
            buf.writeString(entry.getKey()).writeString(entry.getValue());
        }
    }

    @Override
    public void read(DataBuf buf) {
        this.ok = buf.readBoolean();
        this.error = buf.readString();
        this.value = buf.readNullable(DataBuf::readString);
        this.flag = buf.readBoolean();
        this.number = buf.readLong();
        int size = buf.readVarInt();
        this.entries = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            entries.put(buf.readString(), buf.readString());
        }
    }
}
