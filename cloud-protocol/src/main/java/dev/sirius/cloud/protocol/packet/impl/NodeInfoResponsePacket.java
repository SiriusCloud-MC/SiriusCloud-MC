package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.api.node.NodeInfo;
import dev.sirius.cloud.api.node.WrapperInfo;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;

/**
 * Reply to {@link NodeInfoRequestPacket}: the node, its peers, and its wrappers.
 *
 * <p>All three travel together because every caller that wants one wants the
 * others — a panel drawing an overview would otherwise make three round trips
 * for one screen.
 */
public final class NodeInfoResponsePacket extends Packet {

    private NodeInfo node;
    private List<NodeInfo> nodes;
    private List<WrapperInfo> wrappers;

    public NodeInfoResponsePacket() {
    }

    public NodeInfoResponsePacket(NodeInfo node, List<NodeInfo> nodes, List<WrapperInfo> wrappers) {
        this.node = node;
        this.nodes = nodes;
        this.wrappers = wrappers;
    }

    public NodeInfo node() {
        return node;
    }

    public List<NodeInfo> nodes() {
        return nodes == null ? List.of() : nodes;
    }

    public List<WrapperInfo> wrappers() {
        return wrappers == null ? List.of() : wrappers;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeObject(node)
                .writeCollection(nodes(), DataBuf::writeObject)
                .writeCollection(wrappers(), DataBuf::writeObject);
    }

    @Override
    public void read(DataBuf buf) {
        this.node = buf.readObject(NodeInfo.class);
        this.nodes = buf.readList(b -> b.readObject(NodeInfo.class));
        this.wrappers = buf.readList(b -> b.readObject(WrapperInfo.class));
    }
}
