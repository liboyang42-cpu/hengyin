package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;

/**
 * Authoritative role body for one side of a match. A null result is unavailable.
 * This type never invents a goal, a role name, or clue text. Not a Spring bean.
 */
public interface RendezvousRoleContentSource {
    /**
     * Resolve the caller's side from rows already visible in {@code tx}.
     * {@code ownSideFirst} selects the binding's first or second role key.
     * Returns null when the binding is missing or not {@code BOUND}, the publication
     * is missing or not {@code ISSUED}, or that role has no body. Must not read or
     * return the other role's body.
     */
    Issued resolve(RendezvousRoleSecretService.Tx tx, String matchId, boolean ownSideFirst, long now);

    /** Published facts. {@code body} is the only role text; it is omitted from {@link #toString()}. */
    final class Issued {
        public final String goalId, publishVersion, roleKey, body;
        public Issued(String goalId, String publishVersion, String roleKey, String body) {
            this.goalId = goalId; this.publishVersion = publishVersion; this.roleKey = roleKey; this.body = body;
        }
        public String toString() {
            return "Issued{goalId=" + goalId + ", publishVersion=" + publishVersion + ", roleKey=" + roleKey + "}";
        }
    }

    /** Match-level pointer to one publication and the two role keys. No body is stored here. */
    final class Binding {
        public final String matchId, goalId, publishVersion, firstRoleKey, secondRoleKey, state;
        public Binding(String matchId, String goalId, String publishVersion, String firstRoleKey, String secondRoleKey, String state) {
            this.matchId = matchId; this.goalId = goalId; this.publishVersion = publishVersion;
            this.firstRoleKey = firstRoleKey; this.secondRoleKey = secondRoleKey; this.state = state;
        }
    }

    /**
     * Durable distribution record. {@code secretRef} correlates the caller's grant.
     * It is not role text and must not be returned in place of {@link Issued#body}.
     */
    final class Grant {
        public final String ticketId, matchId, goalId, publishVersion, roleKey, secretRef;
        public final long actor;
        public Grant(String ticketId, long actor, String matchId, String goalId, String publishVersion, String roleKey, String secretRef) {
            this.ticketId = ticketId; this.actor = actor; this.matchId = matchId; this.goalId = goalId;
            this.publishVersion = publishVersion; this.roleKey = roleKey; this.secretRef = secretRef;
        }
        public String toString() { return "Grant{ticketId=" + ticketId + ", roleKey=" + roleKey + "}"; }
    }

    /** No catalog is wired. Always unavailable. Never substitutes a generated clue. */
    final class Closed implements RendezvousRoleContentSource {
        public static final Closed INSTANCE = new Closed();
        private Closed() {}
        public Issued resolve(RendezvousRoleSecretService.Tx tx, String matchId, boolean ownSideFirst, long now) {
            return null;
        }
    }

    /**
     * Reads the caller's role from {@code tx}. Used when a store actually implements the
     * content queries. Empty tables and non-ISSUED rows return null. Not a publisher.
     */
    final class Rows implements RendezvousRoleContentSource {
        public static final Rows INSTANCE = new Rows();
        private Rows() {}
        public Issued resolve(RendezvousRoleSecretService.Tx tx, String matchId, boolean ownSideFirst, long now) {
            Objects.requireNonNull(tx);
            if (matchId == null || matchId.isEmpty()) return null;
            Binding binding = tx.binding(matchId);
            if (binding == null || !"BOUND".equals(binding.state)) return null;
            if (!matchId.equals(binding.matchId)) return null;
            String roleKey = ownSideFirst ? binding.firstRoleKey : binding.secondRoleKey;
            if (!id(binding.goalId) || !id(binding.publishVersion) || !id(roleKey)) return null;
            if (!"ISSUED".equals(tx.publicationState(binding.goalId, binding.publishVersion))) return null;
            String body = tx.roleBody(binding.goalId, binding.publishVersion, roleKey);
            if (body == null || body.trim().isEmpty() || body.length() > 1024) return null;
            return new Issued(binding.goalId, binding.publishVersion, roleKey, body);
        }
        private static boolean id(String value) { return value != null && !value.trim().isEmpty() && value.length() <= 128; }
    }
}
