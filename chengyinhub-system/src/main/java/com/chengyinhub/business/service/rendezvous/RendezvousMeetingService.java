package com.chengyinhub.business.service.rendezvous;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Function;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/** W16.3 isolated domain increment. No Spring bean, route, location call or reward.
 * A challenge is a correlation secret, NOT evidence that either person was present.
 */
public final class RendezvousMeetingService {
    public enum Mode { DISABLED, ISOLATED_CONTRACT_EVALUATION }
    public enum State { CHALLENGED, REVIEW_REQUIRED, READY, IN_PROGRESS, COMPLETED,
        CANCELLED, EXPIRED, SAFETY_STOPPED, RECOVERY_REQUIRED }
    /** Implementations must commit before returning, serialize with consent/block writes,
     * enforce unique matchId AND challengeId, rollback exceptions, and never retry callbacks.
     * An uncertain commit must throw; caller must read, never automatically reissue work.
     */
    public interface Store { <T> T transaction(Function<Tx, T> work); }
    public interface Tx extends Transaction {
        Meeting meeting(String matchId);
        void save(Meeting meeting);
    }
    /** Server-only authority. Unknown evidence must not become CLEAR. Implementations bind
     * evidence to this challenge, both actors, approved place, goal and observation window.
     * Manual review must come from an independently authorized reviewer, never a client flag.
     */
    public interface Policy {
        long activity(Match match);
        Evidence evaluate(Meeting meeting, Ticket first, Ticket second, long now);
    }
    public static final class Evidence {
        public final boolean riskClear, timeDistancePlausible, merchantRequired, merchantVerified,
                reviewRequired, reviewApproved;
        public Evidence(boolean riskClear, boolean timeDistancePlausible, boolean merchantRequired,
                        boolean merchantVerified, boolean reviewRequired, boolean reviewApproved) {
            this.riskClear = riskClear; this.timeDistancePlausible = timeDistancePlausible;
            this.merchantRequired = merchantRequired; this.merchantVerified = merchantVerified;
            this.reviewRequired = reviewRequired; this.reviewApproved = reviewApproved;
        }
        boolean clear() { return riskClear && timeDistancePlausible && (!merchantRequired || merchantVerified)
                && (!reviewRequired || reviewApproved); }
    }
    public interface Handoff {
        /** Readiness only. No side effects. False until durable recovery and policies exist. */
        boolean available();
        /** One attempt only after the durable IN_PROGRESS claim. Never retry an exception. */
        long createAndJoin(long first, long second, long activity);
    }
    /** Recovery-only diagnostic; never exposed in participant projection. */
    public static final class RecoveryRequired extends IllegalStateException {
        public final Long knownTeamId;
        public RecoveryRequired(Long knownTeamId) { super("W16_HANDOFF_RECOVERY_REQUIRED"); this.knownTeamId=knownTeamId; }
    }
    /** Internal durable record. Never serialize to clients (contains both independent receipts). */
    public static final class Meeting {
        public final String matchId, challengeId;
        public final long activityId, issuedAt, expiresAt;
        public final Long firstConfirmedAt, secondConfirmedAt, teamId;
        public final State state;
        Meeting(String matchId, String challengeId, long activityId, long issuedAt, long expiresAt,
                Long firstConfirmedAt, Long secondConfirmedAt, Long teamId, State state) {
            this.matchId=matchId; this.challengeId=challengeId; this.activityId=activityId;
            this.issuedAt=issuedAt; this.expiresAt=expiresAt; this.firstConfirmedAt=firstConfirmedAt;
            this.secondConfirmedAt=secondConfirmedAt; this.teamId=teamId; this.state=state;
        }
        Meeting state(State next) { return new Meeting(matchId,challengeId,activityId,issuedAt,expiresAt,
                firstConfirmedAt,secondConfirmedAt,teamId,next); }
        boolean open() { return state == State.CHALLENGED || state == State.REVIEW_REQUIRED || state == State.READY; }
    }
    public static final class View {
        public final String challengeId;
        public final State state;
        public final long expiresAt, activityId;
        public final boolean confirmedByMe, willLeadTeam;
        public final Long teamId;
        View(Meeting m, boolean first) {
            state=m.state; expiresAt=m.expiresAt; activityId=m.activityId; willLeadTeam=first;
            challengeId=m.open() ? m.challengeId : null;
            confirmedByMe=(first ? m.firstConfirmedAt : m.secondConfirmedAt) != null;
            teamId=m.state == State.COMPLETED ? m.teamId : null;
        }
    }
    private final Mode mode;
    private final Store store;
    private final Safety safety;
    private final Policy policy;
    private final Handoff handoff;
    private final Clock clock;
    private final long ttl;
    private final SecureRandom random = new SecureRandom();
    /** No constructor silently enables this service. Hard cap five minutes, server supplied. */
    public RendezvousMeetingService(Mode mode, Store store, Safety safety, Policy policy,
                                    Handoff handoff, Clock clock, long ttlMillis) {
        this.mode=Objects.requireNonNull(mode); this.store=Objects.requireNonNull(store);
        this.safety=Objects.requireNonNull(safety); this.policy=Objects.requireNonNull(policy);
        this.handoff=Objects.requireNonNull(handoff); this.clock=Objects.requireNonNull(clock);
        if (ttlMillis <= 0 || ttlMillis > 300000) throw new IllegalArgumentException("INVALID_TTL");
        ttl=ttlMillis;
    }
    /** Actor comes only from authenticated context. No peer ID, activity or evidence in request. */
    public View issue(long actor, String ticketId) {
        enabled();
        return store.transaction(tx -> {
            Ticket t=owned(tx,actor,ticketId); Match m=match(tx,t);
            Meeting existing=tx.meeting(m.id);
            if (existing != null) return view(tx,t,refresh(tx,m,existing));
            if (!safe(tx,m) || m.state != MatchState.MUTUAL_ACCEPTED) throw new Rejection("MEETING_UNAVAILABLE");
            long now=clock.millis(), activity=policy.activity(m);
            if (activity <= 0) throw new Rejection("GOAL_UNAVAILABLE");
            byte[] bytes=new byte[32]; random.nextBytes(bytes);
            Meeting meeting=new Meeting(m.id,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                    activity,now,Math.min(m.expiresAt,Math.addExact(now,ttl)),null,null,null,State.CHALLENGED);
            tx.save(meeting); return view(tx,t,meeting);
        });
    }
    public View read(long actor, String ticketId) {
        enabled(); return store.transaction(tx -> {
            Ticket t=owned(tx,actor,ticketId); Match m=match(tx,t);
            return view(tx,t,refresh(tx,m,required(tx,m)));
        });
    }
    public View confirm(long actor, String ticketId, String challengeId) {
        enabled(); return store.transaction(tx -> {
            Ticket t=owned(tx,actor,ticketId); Match m=match(tx,t); Meeting r=refresh(tx,m,required(tx,m));
            if (!r.challengeId.equals(challengeId)) throw new Rejection("CHALLENGE_MISMATCH");
            if (!r.open()) return view(tx,t,r);
            boolean first=m.firstTicket.equals(t.id); long now=clock.millis();
            // Replays cannot create a second receipt or overwrite its original timestamp.
            Long a=r.firstConfirmedAt, b=r.secondConfirmedAt;
            if (first && a == null) a=now;
            if (!first && b == null) b=now;
            r=new Meeting(r.matchId,r.challengeId,r.activityId,r.issuedAt,r.expiresAt,a,b,null,State.CHALLENGED);
            if (a != null && b != null) r=assess(tx,m,r);
            tx.save(r); return view(tx,t,r);
        });
    }
    /** Poll an independently authorized review/evidence authority; caller cannot approve it. */
    public View reassess(long actor, String ticketId) {
        enabled(); return store.transaction(tx -> {
            Ticket t=owned(tx,actor,ticketId); Match m=match(tx,t); Meeting r=refresh(tx,m,required(tx,m));
            if (r.open() && r.firstConfirmedAt != null && r.secondConfirmedAt != null) {
                r=assess(tx,m,r); tx.save(r);
            }
            return view(tx,t,r);
        });
    }
    public View cancel(long actor, String ticketId) {
        enabled(); return store.transaction(tx -> {
            Ticket t=owned(tx,actor,ticketId); Match m=match(tx,t); Meeting r=refresh(tx,m,required(tx,m));
            if (r.open()) r=r.state(State.CANCELLED);
            else if (r.state == State.IN_PROGRESS) r=r.state(State.RECOVERY_REQUIRED);
            tx.save(r); return view(tx,t,r);
        });
    }
    /** No transactional exactly-once claim is made for legacy team/IM side effects.
     * A committed claim is NEVER retried, including after crashes or unknown results.
     */
    public View handoff(long actor, String ticketId) {
        enabled();
        final long[] actors=new long[2];
        Meeting claimed=store.transaction(tx -> {
            Ticket t=owned(tx,actor,ticketId); Match m=match(tx,t); Meeting r=refresh(tx,m,required(tx,m));
            if (r.state != State.READY || !handoff.available()) return null;
            r=assess(tx,m,r);
            if (r.state != State.READY) { tx.save(r); return null; }
            actors[0]=tx.ticket(m.firstTicket).actor; actors[1]=tx.ticket(m.secondTicket).actor;
            r=r.state(State.IN_PROGRESS); tx.save(r); return r;
        });
        if (claimed == null) return read(actor,ticketId);
        Long team=null; boolean completed=false;
        try { long id=handoff.createAndJoin(actors[0],actors[1],claimed.activityId); if (id > 0) { team=id; completed=true; } }
        catch (RecoveryRequired uncertain) { team=uncertain.knownTeamId; }
        catch (RuntimeException uncertain) { /* No retry: team or IM may already exist. */ }
        final Long result=team; final boolean succeeded=completed;
        return store.transaction(tx -> {
            Ticket t=owned(tx,actor,ticketId); Match m=match(tx,t); Meeting r=required(tx,m);
            boolean accepted=succeeded && result != null && r.state == State.IN_PROGRESS && safe(tx,m)
                    && clock.millis() < r.expiresAt && assess(tx,m,r).state == State.READY;
            r=new Meeting(r.matchId,r.challengeId,r.activityId,r.issuedAt,r.expiresAt,
                    r.firstConfirmedAt,r.secondConfirmedAt,result,accepted ? State.COMPLETED : State.RECOVERY_REQUIRED);
            tx.save(r); return view(tx,t,r);
        });
    }
    private Meeting assess(Tx tx, Match m, Meeting r) {
        State next=State.REVIEW_REQUIRED;
        if (policy.activity(m) == r.activityId) {
            Evidence evidence=policy.evaluate(r,tx.ticket(m.firstTicket),tx.ticket(m.secondTicket),clock.millis());
            if (evidence != null && evidence.clear()) next=State.READY;
        }
        // Evidence work may cross the deadline or observe a new safety restriction.
        // Recheck before publishing READY or committing the side-effect claim.
        return refresh(tx,m,r.state(next));
    }
    private Meeting refresh(Tx tx, Match m, Meeting r) {
        if (!r.open() && r.state != State.IN_PROGRESS) return r;
        State stop=null;
        if (m.state == MatchState.CANCELLED) stop=State.CANCELLED;
        else if (clock.millis() >= r.expiresAt || m.state == MatchState.EXPIRED) stop=State.EXPIRED;
        else if (!safe(tx,m)) stop=State.SAFETY_STOPPED;
        if (stop != null) { r=r.state(r.state == State.IN_PROGRESS ? State.RECOVERY_REQUIRED : stop); tx.save(r); }
        return r;
    }
    private boolean safe(Tx tx, Match m) {
        Ticket a=tx.ticket(m.firstTicket),b=tx.ticket(m.secondTicket); long now=clock.millis();
        return m.state == MatchState.MUTUAL_ACCEPTED && m.expiresAt > now && a != null && b != null
                && a.actor != b.actor && a.state == TicketState.ACCEPTED && b.state == TicketState.ACCEPTED
                && m.id.equals(a.matchId) && m.id.equals(b.matchId) && a.expiresAt > now && b.expiresAt > now
                && safety.eligible(a.actor,a.pool,a.eligibilityVersion,now)
                && safety.eligible(b.actor,b.pool,b.eligibilityVersion,now) && !safety.blocked(a.actor,b.actor)
                && safety.compatible(a.actor,b.actor,a.pool,now) && safety.publicMeetingAllowed(a.pool,m.publicPlace,now);
    }
    private Ticket owned(Tx tx,long actor,String ticketId) {
        if (actor <= 0) throw new Rejection("AUTH_REQUIRED");
        Ticket t=tx.ticket(ticketId); if (t == null || t.actor != actor) throw new Rejection("NOT_FOUND"); return t;
    }
    private Match match(Tx tx,Ticket t) {
        Match m=t.matchId == null ? null : tx.match(t.matchId);
        if (m == null || (!m.firstTicket.equals(t.id) && !m.secondTicket.equals(t.id))) throw new Rejection("NO_PROPOSAL");
        return m;
    }
    private Meeting required(Tx tx,Match m) { Meeting r=tx.meeting(m.id); if (r == null) throw new Rejection("NO_CHALLENGE"); return r; }
    private View view(Tx tx,Ticket t,Meeting r) { return new View(r,match(tx,t).firstTicket.equals(t.id)); }
    private void enabled() { if (mode != Mode.ISOLATED_CONTRACT_EVALUATION) throw new Rejection("W16_MEETING_DISABLED"); }
}
