package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.service.ServiceInfo;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;

/**
 * Wrapper to node: every service this machine currently has running.
 *
 * <p>Sent right after the handshake, and it <em>replaces</em> whatever the node
 * believed about this wrapper rather than adding to it — the same contract as
 * {@link PlayerSnapshotPacket}, for the same reason.
 *
 * <p>Losing the control connection deliberately does not stop any service: a
 * network blip must not disconnect players. But that leaves processes the node
 * cannot see, and without this packet it never learns about them again. It
 * would then believe the group is empty, provision replacements, and hand the
 * released ports to services that promptly fail to bind.
 *
 * <p>Sent even when empty, because "this machine is running nothing" is
 * information too: it is what tells the node the services it was holding really
 * did die.
 */
public final class ServiceSnapshotPacket extends Packet {

    /**
     * One running service, with the credential its process authenticates with.
     *
     * <p>The token travels beside {@link ServiceInfo} rather than inside it
     * because {@code ServiceInfo} goes everywhere — to proxies, to plugins, out
     * through the HTTP API. A field there would publish every service's
     * credential to anything that can list services. This packet only ever
     * crosses the authenticated wrapper connection.
     */
    public record Entry(ServiceInfo service, String token) {
    }

    private String wrapperName;
    private List<Entry> services;

    public ServiceSnapshotPacket() {
    }

    public ServiceSnapshotPacket(String wrapperName, List<Entry> services) {
        this.wrapperName = wrapperName;
        this.services = services;
    }

    public String wrapperName() {
        return wrapperName;
    }

    public List<Entry> services() {
        return services == null ? List.of() : services;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeString(wrapperName)
                .writeCollection(services(), (b, entry) ->
                        b.writeObject(entry.service()).writeString(
                                entry.token() == null ? "" : entry.token()));
    }

    @Override
    public void read(DataBuf buf) {
        this.wrapperName = buf.readString();
        this.services = buf.readList(b ->
                new Entry(b.readObject(ServiceInfo.class), b.readString()));
    }
}
