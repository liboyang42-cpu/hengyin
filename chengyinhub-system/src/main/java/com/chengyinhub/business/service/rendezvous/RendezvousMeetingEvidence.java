package com.chengyinhub.business.service.rendezvous;

import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Match;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Ticket;
import static com.chengyinhub.business.service.rendezvous.RendezvousMeetingService.Evidence;

/**
 * Approved target and presence facts. A challenge code, a scan, a double click,
 * coordinates, or the two confirmations are not a fact. No row, a blank rule,
 * or any source other than {@link #AUTHORITY_FACT} refuses the target.
 */
public final class RendezvousMeetingEvidence {
    public static final String AUTHORITY_FACT = "AUTHORITY_FACT";

    private RendezvousMeetingEvidence() {}

    /** MyBatis row. Public fields are the mapping, not a client projection. */
    public static final class Fact {
        public long activityId;
        public String placeId;
        public long firstActor;
        public long secondActor;
        public int riskClear;
        public int timeDistancePlausible;
        public int merchantRequired;
        public int merchantVerified;
        public int reviewRequired;
        public int reviewApproved;
        public String source;
        public String ruleVersion;
    }

    public static final class Decision {
        public final long activityId;
        public final Evidence evidence;

        private Decision(long activityId, Evidence evidence) {
            this.activityId = activityId;
            this.evidence = evidence;
        }

        public static Decision closed() { return new Decision(0, null); }

        public boolean ready() { return activityId > 0 && evidence != null && evidence.clear(); }
    }

    public static Decision decide(Match match, Ticket first, Ticket second, Fact fact) {
        if (match == null || first == null || second == null || fact == null) return Decision.closed();
        if (fact.activityId <= 0 || fact.placeId == null || fact.ruleVersion == null || fact.ruleVersion.isEmpty()) {
            return Decision.closed();
        }
        if (!AUTHORITY_FACT.equals(fact.source)) return Decision.closed();
        if (!fact.placeId.equals(match.publicPlace)) return Decision.closed();
        if (fact.firstActor != first.actor || fact.secondActor != second.actor || fact.firstActor == fact.secondActor) {
            return Decision.closed();
        }
        return new Decision(fact.activityId, new Evidence(fact.riskClear == 1, fact.timeDistancePlausible == 1,
                fact.merchantRequired == 1, fact.merchantVerified == 1, fact.reviewRequired == 1, fact.reviewApproved == 1));
    }
}
