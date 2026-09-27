package dev.sirius.cloud.node.cluster;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sirius.cloud.api.logging.CloudLogger;
import dev.sirius.cloud.api.platform.Platform;
import dev.sirius.cloud.driver.config.JsonConfig;
import dev.sirius.cloud.node.config.ClusterSettings;
import dev.sirius.cloud.node.config.NodeConfig;
import dev.sirius.cloud.protocol.connection.NetworkChannel;
import dev.sirius.cloud.protocol.connection.NetworkClient;
import dev.sirius.cloud.protocol.connection.NetworkServer;
import dev.sirius.cloud.protocol.connection.PacketHandler;
import dev.sirius.cloud.protocol.packet.ConnectionType;
import dev.sirius.cloud.protocol.packet.Packet;
import dev.sirius.cloud.protocol.packet.PacketRegistry;
import dev.sirius.cloud.protocol.packet.impl.ClusterPacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakePacket;
import dev.sirius.cloud.protocol.packet.impl.HandshakeResponsePacket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * This node's place in the cluster: its connections to the other nodes, the
 * election, and replication.
 *
 * <p>Two threads, so a large file transfer never delays a heartbeat: one for
 * the election, which must answer within a fraction of its timeout, and one
 * for replication, which reads and writes files. Messages from the network
 * are handed to whichever of the two they belong to.
 *
 * <p>Every node listens on its cluster port and dials every member it knows.
 * Requests go out on the connection this node dialled; replies go back on the
 * connection the request arrived on. That lets a node that has only just
 * joined, and does not know the others' addresses yet, still answer the
 * leader that found it.
 */
public final class ClusterMember {

    /** What the node does when leadership comes and goes. */
    public interface Listener {

        /** Called on its own thread: boot the control plane. */
        void promoted(long term);

        /** Leadership is gone. The control plane must stop acting at once. */
        void demoted(String reason);
    }

    /** A point-in-time view for {@code cluster status}. */
    public record Status(String self, Election.Role role, long term, String leader, long revision, boolean joined,
                         boolean forced, List<MemberStatus> members) {
    }

    public record MemberStatus(String name, String cluster, String clients, boolean self, boolean connected,
                               long lastContactMillisAgo, long revision) {
    }

    private static final CloudLogger LOGGER = CloudLogger.of("Cluster");

    private final NodeConfig config;
    private final Path configFile;
    private final Path stateFile;
    private final int dataVersion;
    private final Listener listener;

    private final ScheduledExecutorService electionLane = lane("sirius-cluster-election");
    private final ScheduledExecutorService replicationLane = lane("sirius-cluster-replication");

    private final NetworkServer server = new NetworkServer(PacketRegistry.standard());
    private final Map<String, NetworkClient> clients = new ConcurrentHashMap<>();
    private final Map<String, NetworkChannel> outbound = new ConcurrentHashMap<>();
    private final Map<String, NetworkChannel> inbound = new ConcurrentHashMap<>();
    /** When each peer last said anything to us. */
    private final Map<String, Long> lastContact = new ConcurrentHashMap<>();
    /** Each peer's replicated revision, as it last reported it. */
    private final Map<String, Long> peerRevisions = new ConcurrentHashMap<>();

    private final Election election;
    private final Replicator replicator;

    private volatile List<String> memberNames;
    private volatile Election.Role role = Election.Role.FOLLOWER;
    private volatile String leader;
    private volatile long term;
    private volatile boolean joined;
    private volatile String votedFor;
    private volatile long revision;
    private volatile boolean closed;

    public ClusterMember(NodeConfig config, Path nodeDirectory, int dataVersion, Listener listener)
            throws IOException {
        this.config = config;
        this.configFile = nodeDirectory.resolve("config.json");
        this.stateFile = nodeDirectory.resolve("local").resolve("cluster").resolve("state.json");
        this.dataVersion = dataVersion;
        this.listener = listener;

        loadState();
        refreshMembers();
        if (config.cluster().member(config.nodeName()).isEmpty()) {
            throw new IOException("This node, '" + config.nodeName() + "', is not in its own cluster member list."
                    + " Run 'cluster init' or rejoin with 'setup node'.");
        }

        this.election = new Election(config.nodeName(), () -> memberNames, Election.Timing.DEFAULT,
                new SecureRandom(), new ElectionActions(), term, votedFor, System.currentTimeMillis());
        this.replicator = new Replicator(new FileIndex(nodeDirectory), this::send, this::sharedOut,
                this::sharedIn, revision, value -> {
                    revision = value;
                    saveState();
                });
    }

    private static ScheduledExecutorService lane(String name) {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------ lifecycle

    public void start() throws InterruptedException {
        ClusterSettings.Member self = config.cluster().member(config.nodeName()).orElseThrow();
        server.start(config.bindAddress(), self.clusterPort(), new InboundHandler());
        syncConnections();

        electionLane.scheduleWithFixedDelay(() -> guard(() -> {
            if (joined) {
                election.tick(System.currentTimeMillis(), replicator.revision(), dataVersion);
            }
            publish();
        }), 100, 100, TimeUnit.MILLISECONDS);

        replicationLane.scheduleWithFixedDelay(() -> guard(() -> {
            if (role == Election.Role.LEADER) {
                replicator.leaderTick(connectedPeers(), System.currentTimeMillis());
            }
        }), 1, 1, TimeUnit.SECONDS);

        if (!joined) {
            LOGGER.info("Waiting to be added to a cluster. On its leader, run: cluster add {} {} {}",
                    config.nodeName(), self.cluster(), self.clients());
        } else {
            LOGGER.info("Cluster member '{}' of {}, looking for a leader", config.nodeName(), memberNames.size());
        }
    }

    public void close() {
        closed = true;
        electionLane.shutdownNow();
        replicationLane.shutdownNow();
        clients.values().forEach(NetworkClient::close);
        server.close();
    }

    /**
     * Leaves on purpose. A leader hands over to the most up-to-date follower
     * it can reach, which then elects itself at once rather than waiting out
     * a timeout, so the network is without a leader for a moment instead of
     * several seconds.
     */
    public void handOver() {
        try {
            electionLane.submit(() -> {
                if (election.role() == Election.Role.LEADER) {
                    long now = System.currentTimeMillis();
                    Optional<String> successor = election.lastAcks().entrySet().stream()
                            .filter(entry -> now - entry.getValue() < Election.Timing.DEFAULT.leaseMillis())
                            .map(Map.Entry::getKey)
                            .max((left, right) -> Long.compare(peerRevisions.getOrDefault(left, -1L),
                                    peerRevisions.getOrDefault(right, -1L)));
                    long handingOverTerm = election.term();
                    election.resign();
                    publish();
                    successor.ifPresent(peer -> {
                        JsonObject body = new JsonObject();
                        body.addProperty("term", handingOverTerm);
                        send(peer, "timeout-now", body, new byte[0]);
                        LOGGER.info("Handing leadership to '{}'", peer);
                    });
                }
            }).get(2, TimeUnit.SECONDS);
            // Long enough for the message to leave before the process does.
            Thread.sleep(300);
        } catch (Exception exception) {
            LOGGER.debug("Hand-over did not complete: {}", exception.getMessage());
        }
    }

    /** Makes this node leader without a majority. See {@link Election#forcePromote}. */
    public void forcePromote() {
        run(electionLane, () -> {
            joined = true;
            saveState();
            election.forcePromote(System.currentTimeMillis());
            publish();
        });
    }

    /** For {@code cluster init}: this node founds a cluster rather than waiting to be added to one. */
    public static void markFounded(Path nodeDirectory) throws IOException {
        Path file = nodeDirectory.resolve("local").resolve("cluster").resolve("state.json");
        JsonObject state = Files.exists(file)
                ? JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                : new JsonObject();
        state.addProperty("joined", true);
        JsonConfig.save(file, state);
    }

    // ---------------------------------------------------------------- views

    public boolean isLeader() {
        return role == Election.Role.LEADER;
    }

    public long term() {
        return term;
    }

    public Optional<String> leader() {
        return Optional.ofNullable(leader);
    }

    /** Every member's client address, which wrappers and services fail over between. */
    public List<String> clientEndpoints() {
        return config.cluster().members().stream().map(ClusterSettings.Member::clients).toList();
    }

    /** The leader's client address, for pointing a client that reached a follower. */
    public Optional<String> leaderClientEndpoint() {
        String current = leader;
        if (current == null || current.equalsIgnoreCase(config.nodeName())) {
            return Optional.empty();
        }
        return config.cluster().member(current).map(ClusterSettings.Member::clients);
    }

    public Status status() {
        long now = System.currentTimeMillis();
        Map<String, Long> followerRevisions = role == Election.Role.LEADER
                ? replicator.followerRevisions() : Map.of();
        List<MemberStatus> members = new ArrayList<>();
        for (ClusterSettings.Member member : config.cluster().members()) {
            boolean self = member.name().equalsIgnoreCase(config.nodeName());
            Long contact = lastContact.get(member.name());
            long memberRevision = self ? revision
                    : followerRevisions.getOrDefault(member.name(), peerRevisions.getOrDefault(member.name(), -1L));
            members.add(new MemberStatus(member.name(), member.cluster(), member.clients(), self,
                    self || outbound.containsKey(member.name()) || inbound.containsKey(member.name()),
                    self ? 0 : contact == null ? -1 : now - contact, memberRevision));
        }
        return new Status(config.nodeName(), role, term, leader, revision, joined, election.forced(), members);
    }

    // ------------------------------------------------------------- elections

    private final class ElectionActions implements Election.Actions {

        @Override
        public void sendHeartbeat(String peer, long heartbeatTerm) {
            JsonObject body = new JsonObject();
            body.addProperty("term", heartbeatTerm);
            send(peer, "heartbeat", body, new byte[0]);
        }

        @Override
        public void sendVoteRequest(String peer, Election.VoteRequest request) {
            JsonObject body = new JsonObject();
            body.addProperty("candidate", request.candidate());
            body.addProperty("term", request.term());
            body.addProperty("pre", request.pre());
            body.addProperty("transfer", request.transfer());
            body.addProperty("revision", request.revision());
            body.addProperty("dataVersion", request.dataVersion());
            send(peer, "vote", body, new byte[0]);
        }

        @Override
        public void persist(long newTerm, String newVotedFor) {
            term = newTerm;
            votedFor = newVotedFor;
            saveState();
        }

        @Override
        public void becameLeader(long leaderTerm) {
            LOGGER.info("This node is now the cluster leader (term {})", leaderTerm);
            run(replicationLane, replicator::becameLeader);
            Thread.ofPlatform().name("sirius-promotion").start(() -> listener.promoted(leaderTerm));
        }

        @Override
        public void lostLeadership(String reason) {
            LOGGER.warn("No longer the cluster leader: {}", reason);
            // Not on the election lane: stepping down shuts that lane down.
            Thread.ofPlatform().name("sirius-step-down").start(() -> listener.demoted(reason));
        }
    }

    /** Copies the election's state where other threads can read it. Election lane only. */
    private void publish() {
        role = election.role();
        leader = election.leader();
        term = election.term();
    }

    // ------------------------------------------------------------- messages

    private void dispatch(String from, ClusterPacket packet, NetworkChannel replyVia) {
        if (closed) {
            return;
        }
        lastContact.put(from, System.currentTimeMillis());
        JsonObject body = JsonParser.parseString(packet.body()).getAsJsonObject();
        switch (packet.kind()) {
            case "heartbeat", "heartbeat-reply", "vote", "vote-reply", "timeout-now" ->
                    run(electionLane, () -> onElectionMessage(from, packet.kind(), body, replyVia));
            case "manifest", "file", "gone", "fetch-done", "delta", "delta-done" ->
                    run(replicationLane, () -> {
                        if (role != Election.Role.LEADER && from.equalsIgnoreCase(leader)) {
                            onFollowerMessage(from, packet.kind(), body, packet.data());
                        }
                    });
            case "fetch", "synced" -> run(replicationLane, () -> {
                if (role == Election.Role.LEADER) {
                    if (packet.kind().equals("fetch")) {
                        replicator.onFetch(from, body);
                    } else {
                        replicator.onSynced(from, body);
                        peerRevisions.put(from, body.get("revision").getAsLong());
                    }
                }
            });
            default -> LOGGER.debug("Unknown cluster message '{}' from {}", packet.kind(), from);
        }
    }

    private void onElectionMessage(String from, String kind, JsonObject body, NetworkChannel replyVia) {
        long now = System.currentTimeMillis();
        switch (kind) {
            case "heartbeat" -> {
                boolean accepted = election.onHeartbeat(from, body.get("term").getAsLong(), now);
                JsonObject reply = new JsonObject();
                reply.addProperty("term", election.term());
                reply.addProperty("accepted", accepted);
                reply.addProperty("revision", replicator.revision());
                replyVia.send(new ClusterPacket("heartbeat-reply", reply.toString()));
            }
            case "heartbeat-reply" -> {
                peerRevisions.put(from, body.get("revision").getAsLong());
                election.onHeartbeatReply(from, body.get("term").getAsLong(), body.get("accepted").getAsBoolean(),
                        now);
            }
            case "vote" -> {
                Election.VoteRequest request = new Election.VoteRequest(
                        body.get("candidate").getAsString(), body.get("term").getAsLong(),
                        body.get("pre").getAsBoolean(), body.get("transfer").getAsBoolean(),
                        body.get("revision").getAsLong(), body.get("dataVersion").getAsInt());
                boolean granted = election.onVoteRequest(request, now, replicator.revision(), dataVersion);
                JsonObject reply = new JsonObject();
                reply.addProperty("term", election.term());
                reply.addProperty("pre", request.pre());
                reply.addProperty("granted", granted);
                replyVia.send(new ClusterPacket("vote-reply", reply.toString()));
            }
            case "vote-reply" -> election.onVoteReply(from, body.get("term").getAsLong(),
                    body.get("pre").getAsBoolean(), body.get("granted").getAsBoolean(), now,
                    replicator.revision(), dataVersion);
            case "timeout-now" -> {
                if (joined) {
                    election.onTimeoutNow(from, body.get("term").getAsLong(), now, replicator.revision(),
                            dataVersion);
                }
            }
            default -> {
            }
        }
        publish();
    }

    private void onFollowerMessage(String from, String kind, JsonObject body, byte[] data) throws IOException {
        switch (kind) {
            case "manifest" -> replicator.onManifest(from, body);
            case "file" -> replicator.onFile(body, data);
            case "gone" -> replicator.onGone(body);
            case "fetch-done" -> replicator.onFetchDone(from, body);
            case "delta" -> replicator.onDelta(body);
            case "delta-done" -> replicator.onDeltaDone(from, body);
            default -> {
            }
        }
    }

    private void send(String peer, String kind, JsonObject body, byte[] data) {
        NetworkChannel channel = outbound.get(peer);
        if (channel == null || !channel.isOpen()) {
            channel = inbound.get(peer);
        }
        if (channel != null && channel.isOpen()) {
            channel.send(new ClusterPacket(kind, body.toString(), data));
        }
    }

    private List<String> connectedPeers() {
        return memberNames.stream()
                .filter(name -> !name.equalsIgnoreCase(config.nodeName()))
                .filter(name -> outbound.containsKey(name) || inbound.containsKey(name))
                .toList();
    }

    // ------------------------------------------------------ shared settings

    private byte[] sharedOut() {
        return JsonConfig.gson().toJson(config.sharedSettings(JsonConfig.gson())).getBytes(StandardCharsets.UTF_8);
    }

    /** The leader's shared settings: adopt them, and with them the member list. Replication lane. */
    private void sharedIn(byte[] data) {
        try {
            JsonObject shared = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            synchronized (config) {
                config.adoptShared(shared, JsonConfig.gson());
                JsonConfig.save(configFile, config);
            }
            refreshMembers();
            if (config.cluster().member(config.nodeName()).isPresent() && !joined) {
                joined = true;
                saveState();
                LOGGER.info("Joined the cluster: {} member(s)", memberNames.size());
            }
            syncConnections();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not apply the leader's settings: {}", exception.getMessage());
        }
    }

    /** After the member list changed here, on the leader. */
    public void membersChanged() {
        List<String> before = memberNames;
        refreshMembers();
        List<String> added = memberNames.stream().filter(name -> !before.contains(name)).toList();
        syncConnections();
        run(electionLane, () -> election.membersAdded(added, System.currentTimeMillis()));
    }

    /**
     * Whether something is listening on a member's cluster address. Checked
     * before adding it: a member that cannot be reached counts against the
     * majority from the moment it is added.
     */
    public static boolean reachable(String address, int timeoutMillis) {
        int colon = address.lastIndexOf(':');
        if (colon <= 0) {
            return false;
        }
        String host = address.substring(0, colon).replace("[", "").replace("]", "");
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, Integer.parseInt(address.substring(colon + 1))),
                    timeoutMillis);
            return true;
        } catch (IOException | NumberFormatException exception) {
            return false;
        }
    }

    /** Hands work to a lane, unless it has already been shut down with the rest of this member. */
    private void run(ScheduledExecutorService lane, ThrowingRunnable task) {
        if (closed) {
            return;
        }
        try {
            lane.execute(() -> guard(task));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            // Closing; nothing left to do this for.
        }
    }

    private void refreshMembers() {
        memberNames = config.cluster().members().stream().map(ClusterSettings.Member::name).toList();
    }

    // ------------------------------------------------------------ transport

    /** Dials every member not dialled yet, and hangs up on ones no longer members. */
    private synchronized void syncConnections() {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (ClusterSettings.Member member : config.cluster().members()) {
            if (!member.name().equalsIgnoreCase(config.nodeName())) {
                wanted.put(member.name(), member.cluster());
            }
        }
        clients.entrySet().removeIf(entry -> {
            if (!wanted.containsKey(entry.getKey())) {
                entry.getValue().close();
                outbound.remove(entry.getKey());
                return true;
            }
            return false;
        });
        wanted.forEach((name, address) -> clients.computeIfAbsent(name, key -> {
            NetworkClient client = new NetworkClient(PacketRegistry.standard());
            client.quiet(true);
            client.connect(List.of(address), new OutboundHandler(name));
            return client;
        }));
    }

    private boolean secretMatches(String credential) {
        return credential != null && MessageDigest.isEqual(
                credential.getBytes(StandardCharsets.UTF_8),
                config.cluster().secret().getBytes(StandardCharsets.UTF_8));
    }

    /** Connections other nodes dialled to us. */
    private final class InboundHandler implements PacketHandler {

        @Override
        public void onPacket(NetworkChannel channel, Packet packet) {
            if (packet instanceof HandshakePacket handshake) {
                if (handshake.type() != ConnectionType.NODE || !secretMatches(handshake.credential())
                        || config.cluster().secret().isBlank()) {
                    LOGGER.warn("Refused a cluster connection from {}: wrong cluster secret", channel.remoteAddress());
                    channel.send(new HandshakeResponsePacket(false, "invalid cluster secret"));
                    channel.close();
                    return;
                }
                channel.authenticated(true);
                channel.type(ConnectionType.NODE);
                channel.name(handshake.name());
                inbound.put(handshake.name(), channel);
                channel.send(new HandshakeResponsePacket(true, "welcome"));
                return;
            }
            if (channel.authenticated() && packet instanceof ClusterPacket cluster) {
                dispatch(channel.name(), cluster, channel);
            }
        }

        @Override
        public void onDisconnect(NetworkChannel channel) {
            if (channel.name() != null) {
                inbound.remove(channel.name(), channel);
                peerGone(channel.name());
            }
        }
    }

    /** Connections this node dialled to one peer. */
    private final class OutboundHandler implements PacketHandler {

        private final String peer;
        private volatile boolean warned;

        OutboundHandler(String peer) {
            this.peer = peer;
        }

        @Override
        public void onConnect(NetworkChannel channel) {
            channel.send(new HandshakePacket(ConnectionType.NODE, config.nodeName(), config.cluster().secret(),
                    null, Platform.describe()));
        }

        @Override
        public void onPacket(NetworkChannel channel, Packet packet) {
            if (packet instanceof HandshakeResponsePacket response) {
                if (response.accepted()) {
                    channel.authenticated(true);
                    channel.name(peer);
                    outbound.put(peer, channel);
                    warned = false;
                    LOGGER.debug("Connected to cluster member '{}'", peer);
                } else {
                    if (!warned) {
                        LOGGER.warn("Cluster member '{}' refused this node: {}", peer, response.message());
                        warned = true;
                    }
                    channel.close();
                }
                return;
            }
            if (channel.authenticated() && packet instanceof ClusterPacket cluster) {
                dispatch(peer, cluster, channel);
            }
        }

        @Override
        public void onDisconnect(NetworkChannel channel) {
            if (outbound.remove(peer, channel)) {
                peerGone(peer);
            }
        }
    }

    private void peerGone(String peer) {
        if (!outbound.containsKey(peer) && !inbound.containsKey(peer)) {
            run(replicationLane, () -> replicator.peerDisconnected(peer));
        }
    }

    // ---------------------------------------------------------------- state

    private void loadState() throws IOException {
        if (Files.notExists(stateFile)) {
            return;
        }
        JsonObject state = JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
        term = state.has("term") ? state.get("term").getAsLong() : 0;
        votedFor = state.has("votedFor") && !state.get("votedFor").isJsonNull()
                ? state.get("votedFor").getAsString() : null;
        revision = state.has("revision") ? state.get("revision").getAsLong() : 0;
        joined = state.has("joined") && state.get("joined").getAsBoolean();
    }

    /** Term and vote are written before they are acted on, so a restart can never vote twice in a term. */
    private synchronized void saveState() {
        JsonObject state = new JsonObject();
        state.addProperty("term", term);
        state.addProperty("votedFor", votedFor);
        state.addProperty("revision", revision);
        state.addProperty("joined", joined);
        try {
            JsonConfig.save(stateFile, state);
        } catch (IOException exception) {
            LOGGER.error("Could not save the cluster state: {}", exception.getMessage());
        }
    }

    private static void guard(ThrowingRunnable task) {
        try {
            task.run();
        } catch (Exception exception) {
            LOGGER.error("Cluster task failed", exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
