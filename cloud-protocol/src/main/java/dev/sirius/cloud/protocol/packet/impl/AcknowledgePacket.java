package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/** Generic reply for queries whose result is "it worked" or "it didn't". */
public final class AcknowledgePacket extends Packet {

    private boolean success;
    private String message;

    public AcknowledgePacket() {
    }

    public AcknowledgePacket(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static AcknowledgePacket ok() {
        return new AcknowledgePacket(true, "");
    }

    public static AcknowledgePacket fail(String message) {
        return new AcknowledgePacket(false, message);
    }

    public boolean success() {
        return success;
    }

    public String message() {
        return message;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeBoolean(success).writeString(message == null ? "" : message);
    }

    @Override
    public void read(DataBuf buf) {
        this.success = buf.readBoolean();
        this.message = buf.readString();
    }
}
