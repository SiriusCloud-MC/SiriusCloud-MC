package dev.sirius.cloud.protocol.buffer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.sirius.cloud.api.buffer.DataBuf;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Netty-backed {@link DataBuf}.
 *
 * <p>Strings are length-prefixed UTF-8 and complex values are JSON, which keeps
 * the wire format inspectable and lets model classes gain fields without a
 * coordinated codec change on both sides.
 */
public final class NettyDataBuf implements DataBuf {

    /** Hard ceiling on a single string, to stop a malformed frame allocating gigabytes. */
    private static final int MAX_STRING_LENGTH = 8 * 1024 * 1024;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ByteBuf buf;

    public NettyDataBuf(ByteBuf buf) {
        this.buf = buf;
    }

    public ByteBuf byteBuf() {
        return buf;
    }

    public static Gson gson() {
        return GSON;
    }

    @Override
    public DataBuf writeBoolean(boolean value) {
        buf.writeBoolean(value);
        return this;
    }

    @Override
    public boolean readBoolean() {
        return buf.readBoolean();
    }

    @Override
    public DataBuf writeByte(int value) {
        buf.writeByte(value);
        return this;
    }

    @Override
    public byte readByte() {
        return buf.readByte();
    }

    @Override
    public DataBuf writeVarInt(int value) {
        int remaining = value;
        while (true) {
            if ((remaining & ~0x7F) == 0) {
                buf.writeByte(remaining);
                return this;
            }
            buf.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
    }

    @Override
    public int readVarInt() {
        int result = 0;
        int position = 0;
        byte current;
        do {
            current = buf.readByte();
            result |= (current & 0x7F) << position;
            position += 7;
            if (position > 35) {
                throw new IllegalStateException("VarInt is too long");
            }
        } while ((current & 0x80) != 0);
        return result;
    }

    @Override
    public DataBuf writeInt(int value) {
        buf.writeInt(value);
        return this;
    }

    @Override
    public int readInt() {
        return buf.readInt();
    }

    @Override
    public DataBuf writeLong(long value) {
        buf.writeLong(value);
        return this;
    }

    @Override
    public long readLong() {
        return buf.readLong();
    }

    @Override
    public DataBuf writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        buf.writeBytes(bytes);
        return this;
    }

    @Override
    public String readString() {
        int length = readVarInt();
        if (length < 0 || length > MAX_STRING_LENGTH) {
            throw new IllegalStateException("Refusing to read string of length " + length);
        }
        if (length > buf.readableBytes()) {
            throw new IllegalStateException("String length " + length + " exceeds remaining " + buf.readableBytes());
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public DataBuf writeUniqueId(UUID value) {
        buf.writeLong(value.getMostSignificantBits());
        buf.writeLong(value.getLeastSignificantBits());
        return this;
    }

    @Override
    public UUID readUniqueId() {
        return new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    public <E extends Enum<E>> DataBuf writeEnum(E value) {
        return writeString(value.name());
    }

    @Override
    public <E extends Enum<E>> E readEnum(Class<E> type) {
        return Enum.valueOf(type, readString());
    }

    @Override
    public DataBuf writeObject(Object value) {
        return writeString(GSON.toJson(value));
    }

    @Override
    public <T> T readObject(Class<T> type) {
        return GSON.fromJson(readString(), type);
    }

    @Override
    public <T> DataBuf writeNullable(T value, BiConsumer<DataBuf, T> writer) {
        writeBoolean(value != null);
        if (value != null) {
            writer.accept(this, value);
        }
        return this;
    }

    @Override
    public <T> T readNullable(Function<DataBuf, T> reader) {
        return readBoolean() ? reader.apply(this) : null;
    }

    @Override
    public <T> DataBuf writeCollection(Collection<T> values, BiConsumer<DataBuf, T> writer) {
        writeVarInt(values.size());
        for (T value : values) {
            writer.accept(this, value);
        }
        return this;
    }

    @Override
    public <T> List<T> readList(Function<DataBuf, T> reader) {
        int size = readVarInt();
        List<T> values = new ArrayList<>(Math.min(size, 1024));
        for (int i = 0; i < size; i++) {
            values.add(reader.apply(this));
        }
        return values;
    }

    @Override
    public int readableBytes() {
        return buf.readableBytes();
    }
}
