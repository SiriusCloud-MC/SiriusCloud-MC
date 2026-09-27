package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/**
 * Wrapper to node: a group's template files changed on disk.
 *
 * <p>{@code global} with a type means the shared template for every group of
 * that type changed, so all of them are affected.
 */
public final class TemplateChangedPacket extends Packet {

    public static final String GLOBAL = "global";

    private String groupName;

    public TemplateChangedPacket() {
    }

    public TemplateChangedPacket(String groupName) {
        this.groupName = groupName;
    }

    public String groupName() {
        return groupName;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeString(groupName);
    }

    @Override
    public void read(DataBuf buf) {
        this.groupName = buf.readString();
    }
}
