package dev.sirius.cloud.node.cluster;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A simulated cluster: elections on a fake clock, messages through an in-memory network that can be cut. */
class ElectionTest {

    private final Map<String, Election> nodes = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final Map<String, Integer> versions = new HashMap<>();
    private final Set<String> down = new HashSet<>();
    /** Pairs that cannot reach each other, as "a|b". */
    private final Set<String> cut = new HashSet<>();
    private final Queue<Runnable> network = new ArrayDeque<>();
    private final List<String> events = new ArrayList<>();
    private long now;

    private void cluster(String... names) {
        List<String> members = List.of(names);
        Random random = new Random(42);
        for (String name : names) {
            revisions.put(name, 0L);
            versions.put(name, 1);
            nodes.put(name, new Election(name, () -> members, Election.Timing.DEFAULT, random, actions(name),
                    0, null, now));
        }
    }

    private boolean reachable(String from, String to) {
        return !down.contains(from) && !down.contains(to) && !cut.contains(from + "|" + to)
                && !cut.contains(to + "|" + from);
    }

    private Election.Actions actions(String self) {
        return new Election.Actions() {
            @Override
            public void sendHeartbeat(String peer, long term) {
                network.add(() -> {
                    if (!reachable(self, peer)) {
                        return;
                    }
                    Election target = nodes.get(peer);
                    boolean accepted = target.onHeartbeat(self, term, now);
                    long replyTerm = target.term();
                    network.add(() -> {
                        if (reachable(peer, self)) {
                            nodes.get(self).onHeartbeatReply(peer, replyTerm, accepted, now);
                        }
                    });
                });
            }

            @Override
            public void sendVoteRequest(String peer, Election.VoteRequest request) {
                network.add(() -> {
                    if (!reachable(self, peer)) {
                        return;
                    }
                    Election target = nodes.get(peer);
                    boolean granted = target.onVoteRequest(request, now, revisions.get(peer), versions.get(peer));
                    long replyTerm = target.term();
                    network.add(() -> {
                        if (reachable(peer, self)) {
                            nodes.get(self).onVoteReply(peer, replyTerm, request.pre(), granted, now,
                                    revisions.get(self), versions.get(self));
                        }
                    });
                });
            }

            @Override
            public void persist(long term, String votedFor) {
            }

            @Override
            public void becameLeader(long term) {
                events.add(self + " leads " + term);
            }

            @Override
            public void lostLeadership(String reason) {
                events.add(self + " stepped down: " + reason);
            }
        };
    }

    /** Advances the clock, ticking every live node every 100ms and delivering messages. */
    private void run(long millis) {
        long until = now + millis;
        while (now < until) {
            now += 100;
            nodes.forEach((name, election) -> {
                if (!down.contains(name)) {
                    election.tick(now, revisions.get(name), versions.get(name));
                }
            });
            while (!network.isEmpty()) {
                network.poll().run();
            }
        }
    }

    private List<String> leaders() {
        return nodes.entrySet().stream()
                .filter(entry -> !down.contains(entry.getKey()))
                .filter(entry -> entry.getValue().role() == Election.Role.LEADER)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private String theLeader() {
        List<String> leaders = leaders();
        assertEquals(1, leaders.size(), "exactly one leader, got " + leaders + " / " + events);
        return leaders.getFirst();
    }

    @Test
    void threeNodesElectExactlyOneLeader() {
        cluster("a", "b", "c");
        run(10_000);
        String leader = theLeader();
        nodes.forEach((name, election) -> assertEquals(leader, election.leader(), name + " agrees"));
    }

    @Test
    void aSingleNodeLeadsItself() {
        cluster("solo");
        run(10_000);
        assertEquals("solo", theLeader());
    }

    @Test
    void whenTheLeaderDiesAnotherTakesOver() {
        cluster("a", "b", "c");
        run(10_000);
        String first = theLeader();
        long firstTerm = nodes.get(first).term();

        down.add(first);
        run(10_000);
        String second = theLeader();
        assertNotEquals(first, second);
        assertTrue(nodes.get(second).term() > firstTerm);
    }

    @Test
    void aCutOffLeaderStepsDownBeforeTheOthersElect() {
        cluster("a", "b", "c");
        run(10_000);
        String old = theLeader();
        for (String other : nodes.keySet()) {
            if (!other.equals(old)) {
                cut.add(old + "|" + other);
            }
        }

        // Step through time: at no instant may two nodes both think they lead.
        for (int step = 0; step < 150; step++) {
            run(100);
            assertTrue(leaders().size() <= 1, "two leaders at " + now + ": " + leaders());
        }
        assertEquals(Election.Role.FOLLOWER, nodes.get(old).role());
        assertNotEquals(old, theLeader());
        assertTrue(events.stream().anyMatch(event -> event.startsWith(old + " stepped down")));
    }

    @Test
    void aCutOffFollowerCannotUnseatAHealthyLeader() {
        cluster("a", "b", "c");
        run(10_000);
        String leader = theLeader();
        long term = nodes.get(leader).term();
        String loner = nodes.keySet().stream().filter(name -> !name.equals(leader)).findFirst().orElseThrow();
        nodes.keySet().stream().filter(name -> !name.equals(loner)).forEach(other -> cut.add(loner + "|" + other));

        run(30_000);
        assertEquals(term, nodes.get(loner).term(), "pre-vote kept it from raising the term");

        cut.clear();
        run(5_000);
        assertEquals(leader, theLeader());
        assertEquals(term, nodes.get(leader).term());
    }

    @Test
    void aCandidateWithOlderDataCannotWin() {
        cluster("a", "b", "c");
        revisions.put("a", 5L);
        revisions.put("b", 9L);
        revisions.put("c", 9L);
        run(20_000);
        assertNotEquals("a", theLeader());
    }

    @Test
    void anOlderBuildIsNeverElected() {
        cluster("a", "b", "c");
        versions.put("b", 2);
        versions.put("c", 2);
        run(20_000);
        assertNotEquals("a", theLeader());
    }

    @Test
    void aLeaderCanHandOverWithoutWaiting() {
        cluster("a", "b", "c");
        run(10_000);
        String old = theLeader();
        long term = nodes.get(old).term();
        String next = nodes.keySet().stream().filter(name -> !name.equals(old)).sorted().findFirst().orElseThrow();

        nodes.get(old).resign();
        down.add(old);
        nodes.get(next).onTimeoutNow(old, term, now, revisions.get(next), versions.get(next));
        run(300);
        assertEquals(next, theLeader(), "well inside an election timeout");
    }

    @Test
    void twoNodesNeedBothUnlessPromotedByHand() {
        cluster("a", "b");
        run(10_000);
        String leader = theLeader();
        String other = leader.equals("a") ? "b" : "a";

        down.add(leader);
        run(20_000);
        assertTrue(leaders().isEmpty(), "one of two is not a majority");

        nodes.get(other).forcePromote(now);
        run(10_000);
        assertEquals(other, theLeader(), "held without a majority once forced");

        // The old one returns: its lower term makes it follow, and the rules apply again.
        down.remove(leader);
        nodes.get(leader).resign();
        run(10_000);
        assertEquals(other, theLeader());
        assertTrue(!nodes.get(other).forced());
    }
}
