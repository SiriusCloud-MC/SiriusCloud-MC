package dev.sirius.cloud.protocol.packet.impl;

import dev.sirius.cloud.api.buffer.DataBuf;
import dev.sirius.cloud.protocol.packet.Packet;

/**
 * Query: one operation against the node's key-value store or database.
 *
 * <p>One packet for every operation rather than a pair per call. They all
 * carry the same handful of fields and all answer with a
 * {@link DataResponsePacket}, and twenty near-identical packet classes would be
 * twenty places for the encoding to drift.
 */
public final class DataRequestPacket extends Packet {

    public enum Operation {
        STORE_GET, STORE_SET, STORE_SET_IF_ABSENT, STORE_DELETE, STORE_INCREMENT, STORE_SCAN,
        DB_GET, DB_PUT, DB_DELETE, DB_EXISTS, DB_ALL, DB_COUNT;

        public boolean mutates() {
            return switch (this) {
                case STORE_SET, STORE_SET_IF_ABSENT, STORE_DELETE, STORE_INCREMENT,
                     DB_PUT, DB_DELETE -> true;
                default -> false;
            };
        }
    }

    private Operation operation;
    private String collection;
    private String key;
    private String value;
    private long ttlMillis;
    private long delta;

    public DataRequestPacket() {
    }

    public DataRequestPacket(Operation operation, String collection, String key, String value,
                             long ttlMillis, long delta) {
        this.operation = operation;
        this.collection = collection;
        this.key = key;
        this.value = value;
        this.ttlMillis = ttlMillis;
        this.delta = delta;
    }

    public Operation operation() {
        return operation;
    }

    public String collection() {
        return collection;
    }

    public String key() {
        return key == null ? "" : key;
    }

    public String value() {
        return value;
    }

    public long ttlMillis() {
        return ttlMillis;
    }

    public long delta() {
        return delta;
    }

    @Override
    public void write(DataBuf buf) {
        buf.writeEnum(operation)
                .writeNullable(collection, DataBuf::writeString)
                .writeString(key())
                .writeNullable(value, DataBuf::writeString)
                .writeLong(ttlMillis)
                .writeLong(delta);
    }

    @Override
    public void read(DataBuf buf) {
        this.operation = buf.readEnum(Operation.class);
        this.collection = buf.readNullable(DataBuf::readString);
        this.key = buf.readString();
        this.value = buf.readNullable(DataBuf::readString);
        this.ttlMillis = buf.readLong();
        this.delta = buf.readLong();
    }
}
