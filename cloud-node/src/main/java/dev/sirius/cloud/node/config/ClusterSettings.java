package dev.sirius.cloud.node.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code cluster} block of {@code node/config.json}.
 *
 * <p>Off by default: a single node needs none of this and behaves exactly as
 * it always has. Every member, this node included, is listed with the address
 * other nodes reach it on and the address wrappers and services reach it on.
 * The list and the secret are the same on every node - the leader replicates
 * them - so adding a node is done once, on the leader.
 */
public final class ClusterSettings {

    private boolean enabled = false;

    /** What nodes authenticate to each other with. Never the wrapper secret. */
    private String secret = "";

    private List<Member> members = new ArrayList<>();

    /** One node of the cluster. */
    public static final class Member {

        private String name;

        /** {@code host:port} other nodes reach this one's cluster port on. */
        private String cluster;

        /** {@code host:port} wrappers and services reach this one on: its connect address and port. */
        private String clients;

        public Member() {
        }

        public Member(String name, String cluster, String clients) {
            this.name = name;
            this.cluster = cluster;
            this.clients = clients;
        }

        public String name() {
            return name;
        }

        public String cluster() {
            return cluster;
        }

        public String clients() {
            return clients;
        }

        /** The port of {@link #cluster}. */
        public int clusterPort() {
            return Integer.parseInt(cluster.substring(cluster.lastIndexOf(':') + 1));
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String secret() {
        return secret == null ? "" : secret;
    }

    public void secret(String secret) {
        this.secret = secret;
    }

    public String generateSecret() {
        this.secret = UUID.randomUUID().toString() + UUID.randomUUID().toString().substring(0, 8);
        return secret;
    }

    public List<Member> members() {
        if (members == null) {
            members = new ArrayList<>();
        }
        return members;
    }

    public Optional<Member> member(String name) {
        return members().stream().filter(member -> member.name().equalsIgnoreCase(name)).findFirst();
    }

    public void putMember(Member member) {
        members().removeIf(existing -> existing.name().equalsIgnoreCase(member.name()));
        members().add(member);
        members().sort((left, right) -> left.name().toLowerCase(Locale.ROOT).compareTo(right.name().toLowerCase(Locale.ROOT)));
    }

    public boolean removeMember(String name) {
        return members().removeIf(existing -> existing.name().equalsIgnoreCase(name));
    }

    /** How many members make a majority: more than half, so two can never both have one. */
    public int quorum() {
        return members().size() / 2 + 1;
    }
}
