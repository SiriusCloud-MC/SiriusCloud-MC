package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

import java.util.List;

/** Reply to a suggesting {@link NetworkCommandPacket}. */
public final class NetworkSuggestionsPacket extends Packet {

    private List<String> suggestions;

    public NetworkSuggestionsPacket() {
    }

    public NetworkSuggestionsPacket(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public List<String> suggestions() {
        return suggestions == null ? List.of() : suggestions;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeCollection(suggestions(), DataBuf::writeString);
    }

    @Override
    public void read(DataBuf buf) {
        this.suggestions = buf.readList(DataBuf::readString);
    }
}
