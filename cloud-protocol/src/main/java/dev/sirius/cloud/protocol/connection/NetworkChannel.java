package dev.sirius.cloud.protocol.connection;

import dev.sirius.cloud.protocol.packet.ConnectionType;
import dev.sirius.cloud.protocol.packet.Packet;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One connection, plus the request/response layer on top of it.
 *
 * <p>A query tags its packet with a fresh {@code queryId} and parks a future in
 * {@link #pendingQueries}. The peer echoes the id on its reply, which completes
 * the future. Timeouts are enforced so a peer that never answers leaks nothing.
 */
public final class NetworkChannel {

    public static final AttributeKey<NetworkChannel> KEY = AttributeKey.valueOf("siriuscloud:channel");

    private static final long QUERY_TIMEOUT_SECONDS = 15;

    private final Channel channel;
    private final Map<UUID, CompletableFuture<Packet>> pendingQueries = new ConcurrentHashMap<>();

    /** Populated by the handshake. Null until then. */
    private volatile ConnectionType type;
    private volatile String name;
    private volatile UUID serviceId;
    private volatile boolean authenticated;

    public NetworkChannel(Channel channel) {
        this.channel = channel;
    }

    public Channel nettyChannel() {
        return channel;
    }

    public boolean isOpen() {
        return channel.isActive();
    }

    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    public ConnectionType type() {
        return type;
    }

    public void type(ConnectionType type) {
        this.type = type;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public UUID serviceId() {
        return serviceId;
    }

    public void serviceId(UUID serviceId) {
        this.serviceId = serviceId;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public void authenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    /** Fire-and-forget. */
    public void send(Packet packet) {
        if (channel.isActive()) {
            channel.writeAndFlush(packet);
        }
    }

    /** Sends a reply carrying the request's query id. */
    public void respond(Packet request, Packet response) {
        response.queryId(request.queryId());
        send(response);
    }

    /**
     * Sends a request and completes when the peer replies.
     *
     * @return a future that fails with {@link TimeoutException} if no reply
     *         arrives, or immediately if the channel is already closed
     */
    public CompletableFuture<Packet> query(Packet packet) {
        CompletableFuture<Packet> future = new CompletableFuture<>();

        if (!channel.isActive()) {
            future.completeExceptionally(new IllegalStateException("Channel is not connected"));
            return future;
        }

        UUID queryId = UUID.randomUUID();
        packet.queryId(queryId);
        pendingQueries.put(queryId, future);

        channel.eventLoop().schedule(() -> {
            CompletableFuture<Packet> pending = pendingQueries.remove(queryId);
            if (pending != null) {
                pending.completeExceptionally(
                        new TimeoutException(packet.getClass().getSimpleName() + " timed out after "
                                + QUERY_TIMEOUT_SECONDS + "s"));
            }
        }, QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        channel.writeAndFlush(packet).addListener(result -> {
            if (!result.isSuccess()) {
                CompletableFuture<Packet> pending = pendingQueries.remove(queryId);
                if (pending != null) {
                    pending.completeExceptionally(result.cause());
                }
            }
        });

        return future;
    }

    /**
     * Routes an inbound packet to a waiting query, if it is a reply.
     *
     * <p>Returns false for inbound <em>requests</em> — they also carry a query
     * id, but one this side never issued, so they fall through to the handler.
     */
    boolean completePendingQuery(Packet packet) {
        UUID queryId = packet.queryId();
        if (queryId == null) {
            return false;
        }
        CompletableFuture<Packet> pending = pendingQueries.remove(queryId);
        if (pending == null) {
            return false;
        }
        pending.complete(packet);
        return true;
    }

    /** Fails every outstanding query. Called when the connection drops. */
    void failPendingQueries(Throwable cause) {
        pendingQueries.values().forEach(future -> future.completeExceptionally(cause));
        pendingQueries.clear();
    }

    public void close() {
        channel.close();
    }

    @Override
    public String toString() {
        return (name != null ? name : "unidentified") + "@" + channel.remoteAddress();
    }
}
