package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.mapper.ImBlockMapper;
import java.util.Objects;

/** Versioned product rules. A missing row, a non-OPEN decision, or a null cap/TTL/place is closed.
 * This class does not invent age, region, window, frequency, or meeting-safety values.
 * Not a Spring bean. */
public final class RendezvousVersionedPolicy implements RendezvousConsentService.Safety, RendezvousSafetyAdapter.ReviewedPolicy {
    public static final class Row {
        public String decision;
        public Integer maxContacts;
        public Long ticketTtlMillis;
        public String publicPlace;
        public Row() {}
        public Row(String decision, Integer maxContacts, Long ticketTtlMillis, String publicPlace) {
            this.decision = decision; this.maxContacts = maxContacts;
            this.ticketTtlMillis = ticketTtlMillis; this.publicPlace = publicPlace;
        }
        public boolean open() { return "OPEN".equals(decision); }
        public void setDecision(String decision) { this.decision = decision; }
        public void setMaxContacts(Integer maxContacts) { this.maxContacts = maxContacts; }
        public void setTicketTtlMillis(Long ticketTtlMillis) { this.ticketTtlMillis = ticketTtlMillis; }
        public void setPublicPlace(String publicPlace) { this.publicPlace = publicPlace; }
    }
    public interface Catalog {
        Row find(String dimension, String versionId);
        int contacts(long actor);
        /** No fact source is connected and admits nobody. A test catalog opts in by overriding this. */
        default RendezvousEligibilityFacts facts() { return RendezvousEligibilityFacts.closed(); }
    }
    private final Catalog catalog;
    private final ImBlockMapper blocks;
    public RendezvousVersionedPolicy(Catalog catalog, ImBlockMapper blocks) {
        this.catalog = Objects.requireNonNull(catalog);
        this.blocks = Objects.requireNonNull(blocks);
    }
    public boolean eligible(long actor, String pool, String version, long now) {
        RendezvousEligibilityFacts facts = catalog.facts();
        if (!facts.connected() || !facts.admits(actor, version, now)) return false;
        return open("eligibility-source", version) && open("age", version) && open("region", version)
                && open("time-window", version) && frequency(pool) != null;
    }
    public boolean factsConnected() { return catalog.facts().connected(); }
    /** A disconnected source, a missing server version, or a different client version refuses. */
    public void requireClientVersion(String clientVersion) {
        RendezvousEligibilityFacts facts = catalog.facts();
        String current = facts.currentVersion();
        if (!facts.connected() || current == null || clientVersion == null || !current.equals(clientVersion))
            throw new RendezvousConsentService.Rejection("NOT_ELIGIBLE");
    }
    /** Server current version only. A disconnected source or a missing row does not adopt the client value. */
    public String ttlVersion(String clientVersion) {
        RendezvousEligibilityFacts facts = catalog.facts();
        if (!facts.connected()) return null;
        return facts.currentVersion();
    }
    public boolean blocked(long first, long second) { return blocks.existsBetween(first, second) > 0; }
    /** Ongoing rule only. Admission counts live in {@link RendezvousContactLedger}, not here. */
    public boolean compatible(long first, long second, String pool, long now) {
        return frequency(pool) != null;
    }
    public boolean publicMeetingAllowed(String pool, String place, long now) {
        String approved = approvedPlace(pool);
        return approved != null && approved.equals(place);
    }
    /** Null unless this eligibility version has an explicit positive TTL. Never a request value. */
    public Long ticketTtl(String eligibilityVersion) {
        Row row = catalog.find("ticket-ttl", eligibilityVersion);
        if (row == null || !row.open() || row.ticketTtlMillis == null || row.ticketTtlMillis <= 0) return null;
        return row.ticketTtlMillis;
    }
    /** Null unless meeting-safety for this pool names one approved public place. */
    public String approvedPlace(String pool) {
        Row row = catalog.find("meeting-safety", pool);
        if (row == null || !row.open() || row.publicPlace == null || row.publicPlace.trim().isEmpty()) return null;
        return row.publicPlace;
    }
    private boolean open(String dimension, String version) {
        Row row = catalog.find(dimension, version);
        return row != null && row.open();
    }
    private Row frequency(String pool) {
        Row row = catalog.find("frequency", pool);
        if (row == null || !row.open() || row.maxContacts == null || row.maxContacts <= 0) return null;
        return row;
    }
}
