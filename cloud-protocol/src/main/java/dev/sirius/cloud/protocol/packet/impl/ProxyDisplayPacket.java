package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.network.ProxyDisplay;
import dev.sirius.cloud.protocol.packet.Packet;

/**
 * Node to proxy: what to show in the server list and tab list, and how many
 * players the whole cloud holds right now.
 *
 * <p>The online count travels with it because a proxy only knows its own
 * players, and a network with two proxies showing half its players each looks
 * half as busy as it is.
 */
public final class ProxyDisplayPacket extends Packet {

    private ProxyDisplay display;
    private int networkOnline;

    public ProxyDisplayPacket() {
    }

    public ProxyDisplayPacket(ProxyDisplay display, int networkOnline) {
        this.display = display;
        this.networkOnline = networkOnline;
    }

    /** Null means "use the proxy's own". */
    public ProxyDisplay display() {
        return display;
    }

    public int networkOnline() {
        return networkOnline;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeNullable(display, DataBuf::writeObject).writeInt(networkOnline);
    }

    @Override
    public void read(DataBuf buf) {
        this.display = buf.readNullable(in -> in.readObject(ProxyDisplay.class));
        this.networkOnline = buf.readInt();
    }
}
