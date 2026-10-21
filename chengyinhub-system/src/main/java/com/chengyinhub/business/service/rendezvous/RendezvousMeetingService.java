package com.chengyinhub.business.service.rendezvous;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;

/** W16.3 isolated domain increment. No Spring bean, route, location call or reward.
 * A challenge is a correlation secret, NOT evidence that either person was present.
 */
public final class RendezvousMeetingService {
    public enum Mode { DISABLED, ISOLATED_CONTRACT_EVALUATION }
    public enum State { CHALLENGED, REVIEW_REQUIRED, READY, IN_PROGRESS, COMPLETED,
        CANCELLED, EXPIRED, SAFETY_STOPPED, RECOVERY_REQUIRED }
    /** Read-only handoff fact for line J. Not the participant meeting state. */
    public enum HandoffState { NOT_STARTED, UNKNOWN, CREATED_JOIN_UNCONFIRMED, COMPLETE }
    /** Implementations must commit before returning, serialize with consent/block writes,
     * enforce unique matchId AND challengeId, rollback exceptions, and never retry callbacks.
     * An uncertain commit must throw; caller must read, never automatically reissue work.
     */
    public interface Store { <T> T transaction(Function<Tx, T> work); }
    public interface Tx extends Transaction {
        Meeting meeting(String matchId);
        void save(Meeting meeting);
        RendezvousHandoffRequest handoffRequest(String matchId);
        void save(RendezvousHandoffRequest request);
        /** True only for the caller that flips dispatched from false to true. */
        boolean markDispatched(String requestId, long now);
        RendezvousMeetingEvidence.Fact evidenceFact(String matchId);
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
        Meeting withTeam(Long team, State next) { return new Meeting(matchId,challengeId,activityId,issuedAt,expiresAt,
                firstConfirmedAt,secondConfirmedAt,team,next); }
        boolean open() { return state == State.CHALLENGED || state == State.REVIEW_REQUIRED || state == State.READY; }
    }
    public static final class View {
        public final String challengeId;
        public final State state;
        public final long expiresAt, activityId;
        public final boolean confirmedByMe, willLeadTeam;
        public final Long teamId;
        /** NOT_STARTED, UNKNOWN, TEAM_CREATED, or COMPLETED. Not a substitute for a published team id. */
        public final String handoffPhase;
        public final String blocker;
        View(Meeting m, boolean first, RendezvousHandoffRequest request) {
            state=m.state; expiresAt=m.expiresAt; activityId=m.activityId; willLeadTeam=first;
            challengeId=m.open() ? m.challengeId : null;
            confirmedByMe=(first ? m.firstConfirmedAt : m.secondConfirmedAt) != null;
            teamId=m.state == State.COMPLETED ? m.teamId : null;
            handoffPhase=request == null ? "NOT_STARTED" : request.phase.name();
            blocker=request == null ? null : request.blocker;
        }
    }
    private final Mode mode;
    private final Store store;
    private final Safety safety;
    private final RendezvousHandoffSafety handoffSafety;
    private final RendezvousHandoffTeamPort teams;
    private final Clock clock;
    private final long ttl;
    private final SecureRandom random = new SecureRandom();
    /** No constructor silently enables this service. Hard cap five minutes, server supplied.
     * {@code handoffSafety} and {@code teams} fail closed when they refuse or cannot prove a result. */
    public RendezvousMeetingService(Mode mode, Store store, Safety safety, RendezvousHandoffSafety handoffSafety,
                                    RendezvousHandoffTeamPort teams, Clock clock, long ttlMillis) {
        this.mode=Objects.requireNonNull(mode); this.store=Objects.requireNonNull(store);
        this.safety=Objects.requireNonNull(safety); this.handoffSafety=Objects.requireNonNull(handoffSafety);
        this.teams=Objects.requireNonNull(teams); this.clock=Objects.requireNonNull(clock);
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
            long now=clock.millis(), activity=decision(tx,m).activityId;
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
    /** Lost-response read of the handoff ledger. Does not call lookup, create, or join. */
    public View result(long actor, String ticketId) { return read(actor, ticketId); }
    /**
     * Read-only query. Signature: {@code HandoffState handoffStateFor(String matchId)}.
     * Returns NOT_STARTED, UNKNOWN, CREATED_JOIN_UNCONFIRMED, or COMPLETE.
     * Does not call the team port and does not allocate a request.
     */
    public HandoffState handoffStateFor(String matchId) {
        if (matchId == null || matchId.trim().isEmpty()) return HandoffState.NOT_STARTED;
        return store.transaction(tx -> {
            RendezvousHandoffRequest req = tx.handoffRequest(matchId);
            if (req != null) {
                if (req.phase == RendezvousHandoffRequest.Phase.COMPLETED) return HandoffState.COMPLETE;
                if (req.phase == RendezvousHandoffRequest.Phase.TEAM_CREATED) return HandoffState.CREATED_JOIN_UNCONFIRMED;
                return HandoffState.UNKNOWN;
            }
            Meeting meeting = tx.meeting(matchId);
            if (meeting == null) return HandoffState.NOT_STARTED;
            if (meeting.state == State.COMPLETED) return HandoffState.COMPLETE;
            if (meeting.state == State.IN_PROGRESS || meeting.state == State.RECOVERY_REQUIRED) return HandoffState.UNKNOWN;
            return HandoffState.NOT_STARTED;
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
            RendezvousHandoffRequest req=tx.handoffRequest(m.id);
            if (r.open()) r=r.state(State.CANCELLED);
            else if (r.state == State.IN_PROGRESS || r.state == State.RECOVERY_REQUIRED) r=r.withTeam(r.teamId, State.RECOVERY_REQUIRED);
            if (req != null && r.state != State.COMPLETED) tx.save(req.blocker("CANCELLED_NOT_ROLLED_BACK", clock.millis()));
            tx.save(r); return view(tx,t,r);
        });
    }
    /** Query the original request, then create or join at most once for that same id.
     * A timeout does not allocate another id. An unproved side effect stays UNKNOWN. */
    public View handoff(long actor, String ticketId) {
        enabled();
        final RendezvousHandoffRequest[] claimed = new RendezvousHandoffRequest[1];
        View early = store.transaction(tx -> claim(tx, actor, ticketId, claimed));
        if (early != null) return early;
        return recover(actor, ticketId, claimed[0]);
    }
    private View claim(Tx tx, long actor, String ticketId, RendezvousHandoffRequest[] claimed) {
        Ticket t=owned(tx,actor,ticketId); Match m=match(tx,t); Meeting r=refresh(tx,m,required(tx,m));
        if (r.state == State.COMPLETED) return view(tx,t,r);
        RendezvousHandoffRequest req=tx.handoffRequest(m.id);
        boolean advancing=r.state == State.READY || r.state == State.IN_PROGRESS || r.state == State.RECOVERY_REQUIRED;
        if (advancing) {
            String denial=denial(tx,m,r,t);
            if (denial != null) return hold(tx,t,r,req,denial);
        }
        if (r.state == State.CANCELLED || r.state == State.EXPIRED || r.state == State.SAFETY_STOPPED
                || r.state == State.CHALLENGED || r.state == State.REVIEW_REQUIRED) return view(tx,t,r);
        if (r.state == State.READY) {
            r=assess(tx,m,r);
            if (r.state != State.READY) { tx.save(r); return view(tx,t,r); }
            if (req == null && !teams.available()) return view(tx,t,r);
            if (req == null) {
                Ticket first=tx.ticket(m.firstTicket), second=tx.ticket(m.secondTicket);
                long now=clock.millis();
                req=new RendezvousHandoffRequest(UUID.randomUUID().toString(), m.id, first.actor, second.actor,
                        r.activityId, RendezvousHandoffRequest.Phase.UNKNOWN, null, null, false, now, now);
                tx.save(req);
            }
            tx.save(r.state(State.IN_PROGRESS));
        }
        if (req == null) {
            tx.save(r.withTeam(r.teamId, State.RECOVERY_REQUIRED));
            return view(tx,t,r.withTeam(r.teamId, State.RECOVERY_REQUIRED));
        }
        claimed[0]=req;
        return null;
    }
    private View recover(long actor, String ticketId, RendezvousHandoffRequest req) {
        RendezvousHandoffTeamPort.Lookup lookup;
        try { lookup=teams.lookup(req.requestId); }
        catch (RuntimeException failed) { return stick(actor, ticketId, "TEAM_QUERY_TIMEOUT"); }
        if (lookup.status == RendezvousHandoffTeamPort.Status.UNKNOWN) {
            return stick(actor, ticketId, lookup.blocker == null ? "TEAM_QUERY_UNKNOWN" : lookup.blocker);
        }
        try {
            if (req.phase == RendezvousHandoffRequest.Phase.UNKNOWN) {
                if (lookup.status == RendezvousHandoffTeamPort.Status.ABSENT) {
                    if (req.dispatched) return stick(actor, ticketId, "CALL_RESULT_UNKNOWN");
                    if (!teams.available()) return stick(actor, ticketId, "TEAM_PORT_DISABLED");
                    final String requestId=req.requestId;
                    boolean won=store.transaction(tx -> tx.markDispatched(requestId, clock.millis()));
                    if (!won) return read(actor, ticketId);
                    RendezvousHandoffTeamPort.Created created=teams.create(req.requestId, req.firstActor, req.secondActor, req.activityId);
                    if (created == null || created.teamId <= 0) return stick(actor, ticketId, "CREATE_RESULT_UNKNOWN");
                    req=req.teamCreated(created.teamId, clock.millis());
                    if (!persist(req)) return read(actor, ticketId);
                } else {
                    if (lookup.teamId == null || lookup.teamId <= 0) return stick(actor, ticketId, "TEAM_QUERY_UNKNOWN");
                    req=req.teamCreated(lookup.teamId, clock.millis());
                    if (!persist(req)) return read(actor, ticketId);
                }
            } else if (lookup.status == RendezvousHandoffTeamPort.Status.ABSENT
                    || (lookup.teamId != null && req.teamId != null && !lookup.teamId.equals(req.teamId))) {
                return stick(actor, ticketId, lookup.status == RendezvousHandoffTeamPort.Status.ABSENT
                        ? "TEAM_QUERY_CONFLICT" : "TEAM_IDENTITY_CONFLICT");
            }
            if (req.phase == RendezvousHandoffRequest.Phase.COMPLETED) return publish(actor, ticketId);
            if (lookup.status != RendezvousHandoffTeamPort.Status.JOINED) {
                if (!teams.available()) return stick(actor, ticketId, "TEAM_PORT_DISABLED");
                teams.join(req.requestId);
            }
            return publish(actor, ticketId);
        } catch (RendezvousHandoffTeamPort.Unconfirmed uncertain) {
            boolean creating=req.phase == RendezvousHandoffRequest.Phase.UNKNOWN;
            if (creating && uncertain.teamId != null && uncertain.teamId > 0) persist(req.teamCreated(uncertain.teamId, clock.millis()));
            return stick(actor, ticketId, creating ? "CREATE_RESULT_UNKNOWN" : "JOIN_RESULT_UNKNOWN");
        } catch (RuntimeException uncertain) {
            return stick(actor, ticketId, req.phase == RendezvousHandoffRequest.Phase.UNKNOWN
                    ? "CREATE_RESULT_UNKNOWN" : "JOIN_RESULT_UNKNOWN");
        }
    }
    /** False when a stop blocker already says the side effect must not be continued. The team id is kept. */
    private boolean persist(RendezvousHandoffRequest req) {
        return store.transaction(tx -> {
            RendezvousHandoffRequest current=tx.handoffRequest(req.matchId);
            if (current == null || !current.requestId.equals(req.requestId)) throw new IllegalStateException("W16_HANDOFF_IDENTITY_CONFLICT");
            boolean stopped=current.blocker != null && current.blocker.endsWith("NOT_ROLLED_BACK");
            RendezvousHandoffRequest next=stopped
                    ? new RendezvousHandoffRequest(req.requestId, req.matchId, req.firstActor, req.secondActor, req.activityId,
                        req.phase, req.teamId, current.blocker, true, req.createdAt, req.updatedAt)
                    : req;
            tx.save(next);
            Meeting meeting=tx.meeting(req.matchId);
            if (meeting.state != State.COMPLETED) tx.save(meeting.withTeam(req.teamId, State.RECOVERY_REQUIRED));
            return !stopped;
        });
    }
    private View publish(long actor, String ticketId) {
        return store.transaction(tx -> {
            Ticket t=owned(tx,actor,ticketId); Match m=match(tx,t); Meeting r=required(tx,m);
            RendezvousHandoffRequest req=tx.handoffRequest(m.id);
            if (r.state == State.COMPLETED) return view(tx,t,r);
            String denial=req == null ? "TEAM_QUERY_UNKNOWN" : denial(tx,m,r,t);
            RendezvousMeetingEvidence.Decision decision=decision(tx,m);
            boolean ready=denial == null && req.teamId != null && req.teamId > 0
                    && decision.activityId == r.activityId && decision.ready();
            if (!ready) {
                if (req != null) tx.save(req.blocker(denial == null ? "EVIDENCE_NOT_READY_NOT_ROLLED_BACK" : denial, clock.millis()));
                Long team=req == null || req.teamId == null ? r.teamId : req.teamId;
                tx.save(r.withTeam(team, State.RECOVERY_REQUIRED));
                return view(tx,t,r.withTeam(team, State.RECOVERY_REQUIRED));
            }
            tx.save(req.completed(clock.millis()));
            tx.save(r.withTeam(req.teamId, State.COMPLETED));
            return view(tx,t,r.withTeam(req.teamId, State.COMPLETED));
        });
    }
    private View hold(Tx tx, Ticket t, Meeting r, RendezvousHandoffRequest req, String denial) {
        if (req != null) {
            tx.save(req.blocker(denial, clock.millis()));
            if (r.state != State.RECOVERY_REQUIRED && r.state != State.COMPLETED) {
                r=r.withTeam(req.teamId == null ? r.teamId : req.teamId, State.RECOVERY_REQUIRED);
                tx.save(r);
            }
            return view(tx,t,r);
        }
        if (r.open()) {
            State stop=denial.startsWith("CANCELLED") ? State.CANCELLED
                    : denial.startsWith("EXPIRED") ? State.EXPIRED : State.SAFETY_STOPPED;
            r=r.state(stop); tx.save(r);
        }
        return view(tx,t,r);
    }
    private View stick(long actor, String ticketId, String blocker) {
        return store.transaction(tx -> {
            Ticket t=owned(tx,actor,ticketId); Match m=match(tx,t); Meeting r=required(tx,m);
            if (r.state == State.COMPLETED) return view(tx,t,r);
            RendezvousHandoffRequest req=tx.handoffRequest(m.id);
            if (req != null) {
                String code=req.blocker != null && req.blocker.endsWith("NOT_ROLLED_BACK") ? req.blocker : blocker;
                tx.save(req.blocker(code, clock.millis()));
            }
            Long team=req == null || req.teamId == null ? r.teamId : req.teamId;
            tx.save(r.withTeam(team, State.RECOVERY_REQUIRED));
            return view(tx,t,r.withTeam(team, State.RECOVERY_REQUIRED));
        });
    }
    private String denial(Tx tx, Match m, Meeting r, Ticket caller) {
        if (m.state == MatchState.CANCELLED) return "CANCELLED_NOT_ROLLED_BACK";
        if (clock.millis() >= r.expiresAt || m.state == MatchState.EXPIRED) return "EXPIRED_NOT_ROLLED_BACK";
        if (!safe(tx,m)) return "SAFETY_REVOKED_NOT_ROLLED_BACK";
        if (!verifyOngoing(tx,m,caller)) return "SAFETY_REVOKED_NOT_ROLLED_BACK";
        RendezvousHandoffRequest req=tx.handoffRequest(m.id);
        if (req != null && req.blocker != null && req.blocker.endsWith("NOT_ROLLED_BACK")) return req.blocker;
        return null;
    }
    private boolean verifyOngoing(Tx tx, Match m, Ticket caller) {
        Ticket first=tx.ticket(m.firstTicket), second=tx.ticket(m.secondTicket);
        if (first == null || second == null) return false;
        long peer=caller.actor == first.actor ? second.actor : first.actor;
        try { return handoffSafety.verifyCurrent(tx, m.id, caller.id, caller.actor, peer, clock.millis()); }
        catch (RuntimeException denied) { return false; }
    }
    private Meeting assess(Tx tx, Match m, Meeting r) {
        RendezvousMeetingEvidence.Decision decision=decision(tx,m);
        State next=decision.activityId == r.activityId && decision.ready() ? State.READY : State.REVIEW_REQUIRED;
        // Evidence work may cross the deadline or observe a new safety restriction.
        // Recheck before publishing READY or committing the side-effect claim.
        return refresh(tx,m,r.state(next));
    }
    private RendezvousMeetingEvidence.Decision decision(Tx tx, Match m) {
        return RendezvousMeetingEvidence.decide(m, tx.ticket(m.firstTicket), tx.ticket(m.secondTicket), tx.evidenceFact(m.id));
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
    private View view(Tx tx,Ticket t,Meeting r) {
        return new View(r, match(tx,t).firstTicket.equals(t.id), tx.handoffRequest(r.matchId));
    }
    private void enabled() { if (mode != Mode.ISOLATED_CONTRACT_EVALUATION) throw new Rejection("W16_MEETING_DISABLED"); }
}
