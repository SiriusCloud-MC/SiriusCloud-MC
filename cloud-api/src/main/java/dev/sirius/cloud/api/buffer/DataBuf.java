package dev.sirius.cloud.api.buffer;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Wire buffer abstraction.
 *
 * <p>This interface exists so that {@code cloud-api} never exposes Netty.
 * Paper ships its own Netty on the server classpath, and a plugin that leaks a
 * second copy of {@code io.netty} into that classpath produces version
 * conflicts that are miserable to debug. The Netty-backed implementation lives
 * in {@code cloud-protocol} and is relocated when shaded into plugins.
 */
public interface DataBuf {

    DataBuf writeBoolean(boolean value);

    boolean readBoolean();

    DataBuf writeByte(int value);

    byte readByte();

    DataBuf writeVarInt(int value);

    int readVarInt();

    DataBuf writeInt(int value);

    int readInt();

    DataBuf writeLong(long value);

    long readLong();

    DataBuf writeString(String value);

    String readString();

    DataBuf writeUniqueId(UUID value);

    UUID readUniqueId();

    <E extends Enum<E>> DataBuf writeEnum(E value);

    <E extends Enum<E>> E readEnum(Class<E> type);

    /** Serialises an arbitrary object. The default implementation uses JSON. */
    DataBuf writeObject(Object value);

    <T> T readObject(Class<T> type);

    <T> DataBuf writeNullable(T value, BiConsumer<DataBuf, T> writer);

    <T> T readNullable(Function<DataBuf, T> reader);

    <T> DataBuf writeCollection(Collection<T> values, BiConsumer<DataBuf, T> writer);

    <T> List<T> readList(Function<DataBuf, T> reader);

    int readableBytes();
}
