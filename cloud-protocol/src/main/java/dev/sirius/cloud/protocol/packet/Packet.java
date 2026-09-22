package dev.sirius.cloud.protocol.packet;

import dev.sirius.cloud.api.buffer.DataBuf;

import java.util.UUID;

/**
 * Base class for everything that crosses the wire.
 *
 * <p>A packet carrying a {@code queryId} is a request expecting exactly one
 * reply tagged with the same id. That single field is the whole request/response
 * mechanism — see {@code NetworkChannel#query}.
 */
public abstract class Packet {

    private UUID queryId;

    public UUID queryId() {
        return queryId;
    }

    public void queryId(UUID queryId) {
        this.queryId = queryId;
    }

    public abstract void write(DataBuf buf);

    public abstract void read(DataBuf buf);

    @Override
    public String toString() {
        return getClass().getSimpleName() + (queryId != null ? "[query]" : "");
    }
}
