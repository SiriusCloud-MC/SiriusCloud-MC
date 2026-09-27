package dev.sirius.cloud.node.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Who leads the cluster: Raft's election, without its log.
 *
 * <p>Pure state and rules, with time and messages passed in, so every case
 * below can be tested without a network. The pieces that keep two leaders
 * from ever acting at once:
 * <ul>
 *   <li><b>Terms and majorities.</b> Each election is a new term, a node votes
 *       once per term, and winning takes a majority of all members. Two
 *       majorities always share a node, so there is at most one leader per term.</li>
 *   <li><b>The lease.</b> A leader that has not heard back from a majority for
 *       {@link Timing#leaseMillis} steps down on its own. That is shorter than
 *       the shortest election timeout, so an old leader that is cut off stops
 *       before the others can have elected its replacement.</li>
 *   <li><b>Pre-vote.</b> A node first asks whether it <em>would</em> win,
 *       without starting a new term. A node that is merely cut off itself
 *       never wins that, so it cannot keep raising the term and unseat a
 *       healthy leader every time it reconnects.</li>
 *   <li><b>Stickiness.</b> A node that has heard from a leader recently
 *       refuses to help elect another, for the same reason.</li>
 *   <li><b>Up-to-date data.</b> A node only votes for a candidate whose
 *       replicated data is at least as new as its own, and whose build can
 *       read it, so the new leader never starts from older files than the
 *       cluster already had.</li>
 * </ul>
 */
public final class Election {

    public enum Role {
        FOLLOWER, CANDIDATE, LEADER
    }

    /** Timing, in milliseconds. The lease must stay below the election timeout. */
    public record Timing(long heartbeatMillis, long leaseMillis, long electionMinMillis, long electionMaxMillis) {

        public static final Timing DEFAULT = new Timing(500, 3_000, 4_000, 6_000);

        public Timing {
            if (leaseMillis >= electionMinMillis) {
                throw new IllegalArgumentException("The lease must be shorter than the election timeout");
            }
        }
    }

    /** What an election decides to do. Implemented by the node's cluster member over the network. */
    public interface Actions {

        void sendHeartbeat(String peer, long term);

        void sendVoteRequest(String peer, VoteRequest request);

        /** Term and vote must be on disk before either is acted on, or a restart could vote twice. */
        void persist(long term, String votedFor);

        void becameLeader(long term);

        void lostLeadership(String reason);
    }

    /** A request for a vote, real or {@code pre}. */
    public record VoteRequest(String candidate, long term, boolean pre, boolean transfer, long revision,
                              int dataVersion) {
    }

    private final String self;
    private final Supplier<List<String>> members;
    private final Timing timing;
    private final Random random;
    private final Actions actions;

    private long term;
    private String votedFor;

    private Role role = Role.FOLLOWER;
    private String leader;
    private long lastLeaderContact = Long.MIN_VALUE / 2;
    private long electionDeadline;
    private long lastHeartbeat = Long.MIN_VALUE / 2;
    private boolean preVoting;
    private final Set<String> votes = new HashSet<>();
    private final Map<String, Long> lastAck = new HashMap<>();
    /** Promoted by hand, without a majority. Holds until every member has answered again. */
    private boolean forced;

    public Election(String self, Supplier<List<String>> members, Timing timing, Random random, Actions actions,
                    long term, String votedFor, long now) {
        this.self = self;
        this.members = members;
        this.timing = timing;
        this.random = random;
        this.actions = actions;
        this.term = term;
        this.votedFor = votedFor;
        resetDeadline(now);
    }

    public Role role() {
        return role;
    }

    public long term() {
        return term;
    }

    /** The current leader's name, or null while there is none this node knows of. */
    public String leader() {
        return leader;
    }

    public boolean forced() {
        return forced;
    }

    /** When each peer last answered the leader's heartbeat. Leader only. */
    public Map<String, Long> lastAcks() {
        return Map.copyOf(lastAck);
    }

    private List<String> peers() {
        return members.get().stream().filter(name -> !name.equalsIgnoreCase(self)).toList();
    }

    private int quorum() {
        return members.get().size() / 2 + 1;
    }

    // ------------------------------------------------------------------ timer

    /** Called often, a few times a second. */
    public void tick(long now, long revision, int dataVersion) {
        switch (role) {
            case LEADER -> {
                if (now - lastHeartbeat >= timing.heartbeatMillis()) {
                    lastHeartbeat = now;
                    peers().forEach(peer -> actions.sendHeartbeat(peer, term));
                }
                // Current members only: a removed member's last answer is no vote.
                int reachable = 1 + (int) peers().stream()
                        .map(lastAck::get)
                        .filter(at -> at != null && now - at < timing.leaseMillis())
                        .count();
                if (forced) {
                    if (reachable == members.get().size()) {
                        // Everyone is back: the ordinary rules apply again.
                        forced = false;
                    }
                } else if (reachable < quorum()) {
                    stepDown("lost contact with the majority of the cluster");
                }
            }
            case FOLLOWER, CANDIDATE -> {
                if (now >= electionDeadline) {
                    if (quorum() <= 1) {
                        startElection(now, false, revision, dataVersion);
                    } else {
                        startPreVote(now, revision, dataVersion);
                    }
                }
            }
        }
    }

    private void startPreVote(long now, long revision, int dataVersion) {
        preVoting = true;
        votes.clear();
        votes.add(self);
        resetDeadline(now);
        VoteRequest request = new VoteRequest(self, term + 1, true, false, revision, dataVersion);
        peers().forEach(peer -> actions.sendVoteRequest(peer, request));
    }

    private void startElection(long now, boolean transfer, long revision, int dataVersion) {
        preVoting = false;
        term++;
        votedFor = self;
        actions.persist(term, votedFor);
        role = Role.CANDIDATE;
        leader = null;
        votes.clear();
        votes.add(self);
        resetDeadline(now);
        if (votes.size() >= quorum()) {
            becomeLeader(now);
            return;
        }
        VoteRequest request = new VoteRequest(self, term, false, transfer, revision, dataVersion);
        peers().forEach(peer -> actions.sendVoteRequest(peer, request));
    }

    private void becomeLeader(long now) {
        role = Role.LEADER;
        leader = self;
        lastAck.clear();
        // The voters just answered us, which counts as contact for the lease.
        votes.stream().filter(voter -> !voter.equalsIgnoreCase(self)).forEach(voter -> lastAck.put(voter, now));
        lastHeartbeat = now;
        peers().forEach(peer -> actions.sendHeartbeat(peer, term));
        actions.becameLeader(term);
    }

    private void stepDown(String reason) {
        boolean wasLeader = role == Role.LEADER;
        role = Role.FOLLOWER;
        leader = null;
        preVoting = false;
        forced = false;
        lastAck.clear();
        if (wasLeader) {
            actions.lostLeadership(reason);
        }
    }

    private void adoptTerm(long newTerm) {
        term = newTerm;
        votedFor = null;
        actions.persist(term, null);
        if (role != Role.FOLLOWER) {
            stepDown("a newer term " + newTerm + " exists");
        }
    }

    private void resetDeadline(long now) {
        long spread = timing.electionMaxMillis() - timing.electionMinMillis();
        electionDeadline = now + timing.electionMinMillis() + (spread <= 0 ? 0 : (long) (random.nextDouble() * spread));
    }

    // --------------------------------------------------------------- messages

    /** A leader's heartbeat. Returns whether it is accepted, which the reply carries. */
    public boolean onHeartbeat(String from, long heartbeatTerm, long now) {
        if (heartbeatTerm < term) {
            return false;
        }
        if (heartbeatTerm > term) {
            adoptTerm(heartbeatTerm);
        } else if (role == Role.LEADER && !from.equalsIgnoreCase(self)) {
            // Cannot happen with majorities; if it somehow does, both yield.
            stepDown("another leader claims term " + heartbeatTerm);
        } else if (role == Role.CANDIDATE) {
            stepDown("a leader was elected");
        }
        role = Role.FOLLOWER;
        preVoting = false;
        leader = from;
        lastLeaderContact = now;
        resetDeadline(now);
        return true;
    }

    public void onHeartbeatReply(String from, long replyTerm, boolean accepted, long now) {
        if (replyTerm > term) {
            adoptTerm(replyTerm);
            return;
        }
        if (role == Role.LEADER && accepted && replyTerm == term) {
            lastAck.put(from, now);
        }
    }

    /**
     * A candidate asks for this node's vote.
     *
     * @return whether the vote is granted
     */
    public boolean onVoteRequest(VoteRequest request, long now, long revision, int dataVersion) {
        if (request.dataVersion() < dataVersion || request.revision() < revision) {
            return false;
        }
        boolean heardFromLeader = role == Role.LEADER
                || (leader != null && now - lastLeaderContact < timing.electionMinMillis());
        if (heardFromLeader && !request.transfer()) {
            return false;
        }
        if (request.pre()) {
            return request.term() > term;
        }
        if (request.term() < term) {
            return false;
        }
        if (request.term() > term) {
            adoptTerm(request.term());
        }
        if (votedFor == null || votedFor.equalsIgnoreCase(request.candidate())) {
            votedFor = request.candidate();
            actions.persist(term, votedFor);
            resetDeadline(now);
            return true;
        }
        return false;
    }

    public void onVoteReply(String from, long replyTerm, boolean pre, boolean granted, long now, long revision,
                            int dataVersion) {
        if (!pre && replyTerm > term) {
            adoptTerm(replyTerm);
            return;
        }
        if (!granted) {
            return;
        }
        if (pre && preVoting && role != Role.LEADER) {
            votes.add(from);
            if (votes.size() >= quorum()) {
                startElection(now, false, revision, dataVersion);
            }
        } else if (!pre && role == Role.CANDIDATE && replyTerm == term) {
            votes.add(from);
            if (votes.size() >= quorum()) {
                becomeLeader(now);
            }
        }
    }

    /**
     * New members get one lease to answer before they count against the
     * leader's majority. Without it, adding a member steps the leader down
     * on the very next tick, before the newcomer could have replied.
     */
    public void membersAdded(List<String> added, long now) {
        if (role == Role.LEADER) {
            added.forEach(name -> lastAck.putIfAbsent(name, now));
        }
    }

    /** The leader is going away on purpose and hands over to this node: elect now, without waiting. */
    public void onTimeoutNow(String from, long fromTerm, long now, long revision, int dataVersion) {
        if (fromTerm == term && from.equalsIgnoreCase(leader) && role == Role.FOLLOWER) {
            startElection(now, true, revision, dataVersion);
        }
    }

    /**
     * Makes this node the leader without a majority, for a cluster that has
     * lost the majority it would need - two nodes, one of them gone. The
     * operator is vouching that the others are really down; if one is not,
     * it steps down as soon as it hears this node's newer term.
     */
    public void forcePromote(long now) {
        term++;
        votedFor = self;
        actions.persist(term, votedFor);
        votes.clear();
        forced = true;
        becomeLeader(now);
        forced = true;
    }

    /** Stepping down on purpose, e.g. because this node is shutting down. */
    public void resign() {
        role = Role.FOLLOWER;
        leader = null;
        forced = false;
        lastAck.clear();
    }
}
