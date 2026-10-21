package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.service.IPlayTeamService;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousMeetingService.*;

/** Standalone targeted contract test. Only synthetic actors/store/evidence/team port. */
public final class RendezvousMeetingServiceTest {
    static final class Time extends Clock {
        long now=1000;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    static final class Memory implements RendezvousMeetingService.Store, Tx {
        Map<String,Ticket> tickets=new HashMap<>(); Map<String,Match> matches=new HashMap<>();
        Map<String,Meeting> meetings=new HashMap<>();
        Map<String,RendezvousHandoffRequest> handoffs=new HashMap<>();
        java.util.function.Function<String,RendezvousMeetingEvidence.Fact> evidence=id->null;
        Runnable beforeEvidence=()->{};
        boolean uncertainClaimCommit;
        public synchronized <T> T transaction(Function<Tx,T> work) {
            Map<String,Ticket> a=new HashMap<>(tickets); Map<String,Match>b=new HashMap<>(matches);
            Map<String,Meeting> c=new HashMap<>(meetings);
            Map<String,RendezvousHandoffRequest> h=new HashMap<>(handoffs);
            final T result;
            try{result=work.apply(this);}catch(RuntimeException e){tickets=a;matches=b;meetings=c;handoffs=h;throw e;}
            // Simulate a committed claim whose acknowledgement was lost, not a rollback.
            if(uncertainClaimCommit&&meetings.values().stream().anyMatch(m->m.state==State.IN_PROGRESS)){
                uncertainClaimCommit=false;throw new IllegalStateException("synthetic unknown claim commit");
            }
            return result;
        }
        public Ticket activeTicket(long actor){return tickets.values().stream().filter(t->t.actor==actor&&t.active()).findFirst().orElse(null);}
        public Ticket ticket(String id){return tickets.get(id);}
        public Match match(String id){return matches.get(id);}
        public void save(Ticket t){tickets.put(t.id,t);}
        public void save(Match m){matches.put(m.id,m);}
        public Meeting meeting(String id){return meetings.get(id);}
        public void save(Meeting m){meetings.put(m.matchId,m);}
        public RendezvousHandoffRequest handoffRequest(String matchId){return handoffs.get(matchId);}
        public void save(RendezvousHandoffRequest request){
            RendezvousHandoffRequest existing=handoffs.get(request.matchId);
            if(existing!=null&&!existing.requestId.equals(request.requestId))throw new IllegalStateException("W16_HANDOFF_IDENTITY_CONFLICT");
            handoffs.put(request.matchId,request);
        }
        public boolean markDispatched(String requestId,long now){
            for(Map.Entry<String,RendezvousHandoffRequest> entry:new ArrayList<>(handoffs.entrySet())){
                RendezvousHandoffRequest row=entry.getValue();
                if(!row.requestId.equals(requestId))continue;
                if(row.dispatched||row.phase!=RendezvousHandoffRequest.Phase.UNKNOWN)return false;
                if(row.blocker!=null&&(row.blocker.endsWith("NOT_ROLLED_BACK")||"CANCELLED_PREVENTED".equals(row.blocker)))return false;
                handoffs.put(entry.getKey(),row.dispatched(now));
                return true;
            }
            return false;
        }
        public RendezvousMeetingEvidence.Fact evidenceFact(String matchId){beforeEvidence.run();return evidence.apply(matchId);}
    }
    static final class Fixture implements Safety {
        final Memory db=new Memory(); final Time time=new Time(); final RendezvousConsentService consent;
        final RendezvousMeetingService service; final String first,second,match; final TeamDouble team=new TeamDouble();
        boolean blocked, clear=true, merchantMissing, reviewPending, available=true, fail, evidenceUnknown, ongoing=true;
        String source=RendezvousMeetingEvidence.AUTHORITY_FACT, ruleVersion="evidence-v1";
        long activityId=99;
        Runnable during=()->{}, duringEvidence=()->{};
        Fixture(){
            consent=new RendezvousConsentService(new RendezvousConsentService.Store(){
                public <T>T transaction(Function<Transaction,T> work){return db.transaction(tx->work.apply(tx));}
            },this,time,RendezvousConsentServiceTest.permitsPairing);
            first=consent.join(1,"pool","v1",100000).ticketId;
            second=consent.join(2,"pool","v1",100000).ticketId;
            match=consent.propose(first,second,"public-place");
            consent.accept(1,first,1); consent.accept(2,second,2);
            db.beforeEvidence=()->duringEvidence.run();
            db.evidence=id->fact();
            team.fixture=this;
            service=new RendezvousMeetingService(Mode.ISOLATED_CONTRACT_EVALUATION,db,this,
                    (m,t,a,p,n)->ongoing,team,time,30000);
        }
        public boolean eligible(long a,String p,String v,long n){return true;}
        public boolean blocked(long a,long b){return blocked;}
        public boolean compatible(long a,long b,String p,long n){return true;}
        public boolean publicMeetingAllowed(String p,String place,long n){return true;}
        RendezvousMeetingEvidence.Fact fact(){
            if(evidenceUnknown)return null;
            RendezvousMeetingEvidence.Fact fact=new RendezvousMeetingEvidence.Fact();
            fact.activityId=activityId; fact.placeId="public-place"; fact.firstActor=1; fact.secondActor=2;
            fact.riskClear=clear?1:0; fact.timeDistancePlausible=clear?1:0;
            fact.merchantRequired=merchantMissing?1:0; fact.merchantVerified=0;
            fact.reviewRequired=reviewPending?1:0; fact.reviewApproved=reviewPending?0:1;
            fact.source=source; fact.ruleVersion=ruleVersion; return fact;
        }
        int attempts(){return team.creates;}
        String issue(){return service.issue(1,first).challengeId;}
        void ready(){String id=issue(); service.confirm(1,first,id); service.confirm(2,second,id);}
    }
    static final class TeamDouble implements RendezvousHandoffTeamPort {
        Fixture fixture; final Map<String,Long> created=new HashMap<>(); final Set<String> joined=new HashSet<>();
        int creates,joins,lookups; boolean obscure,lookupThrows,failJoin;
        Runnable beforeLookup=()->{}, beforeCreate=()->{}, beforeJoin=()->{};
        public boolean available(){return fixture.available;}
        public Lookup lookup(String requestId){
            lookups++;
            beforeLookup.run();
            if(lookupThrows)throw new IllegalStateException("synthetic query timeout");
            if(obscure)return Lookup.unknown("SYNTHETIC_QUERY_UNKNOWN");
            if(joined.contains(requestId))return Lookup.joined(created.get(requestId));
            if(created.containsKey(requestId))return Lookup.created(created.get(requestId));
            return Lookup.absent();
        }
        public Created create(String requestId,long leader,long member,long activity){
            beforeCreate.run();
            if(created.containsKey(requestId))return new Created(created.get(requestId));
            creates++;
            check(leader==1&&member==2&&activity==fixture.activityId,"bound actors/goal");
            State claimed=fixture.db.meeting(fixture.match).state;
            check(claimed==State.IN_PROGRESS||claimed==State.RECOVERY_REQUIRED,"claim before side effects");
            fixture.during.run();
            created.put(requestId,77L);
            if(fixture.fail)throw new RendezvousHandoffTeamPort.Unconfirmed(77L);
            return new Created(77L);
        }
        public void join(String requestId){
            beforeJoin.run();
            joins++;
            Long team=created.get(requestId);
            if(team==null||failJoin)throw new RendezvousHandoffTeamPort.Unconfirmed(team);
            joined.add(requestId);
        }
    }
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    static void rejected(String code,Runnable work){try{work.run();throw new AssertionError("expected "+code);}catch(Rejection e){check(code.equals(e.getCode()),e.getCode());}}
    static void dualAndReplay(){
        Fixture f=new Fixture();String id=f.issue();check(id.length()==43,"256-bit challenge");
        check(id.equals(f.service.issue(2,f.second).challengeId),"issue idempotent");
        rejected("AUTH_REQUIRED",()->f.service.confirm(0,f.first,id));
        rejected("NOT_FOUND",()->f.service.confirm(2,f.first,id));
        rejected("CHALLENGE_MISMATCH",()->f.service.confirm(1,f.first,"wrong"));
        f.service.confirm(1,f.first,id);long receipt=f.db.meeting(f.match).firstConfirmedAt;
        f.time.now++;f.service.confirm(1,f.first,id);
        check(f.db.meeting(f.match).firstConfirmedAt==receipt,"receipt immutable");
        check(f.service.handoffStateFor(f.match)==HandoffState.NOT_STARTED,"no request yet");
        check(f.service.handoff(1,f.first).state==State.CHALLENGED&&f.attempts()==0,"one actor insufficient");
        f.service.confirm(2,f.second,id);check(f.service.handoff(1,f.first).state==State.COMPLETED,"both clear");
        check(f.service.handoffStateFor(f.match)==HandoffState.COMPLETE,"published handoff");
        int lookups=f.team.lookups;
        f.service.result(2,f.second); f.service.handoffStateFor(f.match);
        check(f.team.lookups==lookups&&f.attempts()==1,"result and state query do not create");
        f.service.handoff(2,f.second);check(f.attempts()==1,"completed replay no duplicate team");
        Fixture pending=new Fixture();Match m=pending.db.match(pending.match);
        pending.db.save(new Match(m.id,m.firstTicket,m.secondTicket,m.publicPlace,m.expiresAt,m.version,
            m.firstAcceptedAt,null,MatchState.PROPOSED));
        rejected("MEETING_UNAVAILABLE",()->pending.issue());
        check(pending.db.meeting(pending.match)==null,"mutual consent required before issuing a challenge");
    }
    static void expiryCancellationSafety(){
        Fixture f=new Fixture();String id=f.issue();f.time.now=31000;
        check(f.service.confirm(1,f.first,id).state==State.EXPIRED,"exact expiry");
        check(f.service.issue(1,f.first).challengeId==null,"expired cannot reissue/replay");
        f=new Fixture();f.ready();f.service.cancel(1,f.first);check(f.service.handoff(2,f.second).state==State.CANCELLED&&f.attempts()==0,"either cancels");
        f=new Fixture();f.ready();f.consent.cancel(2,f.second);check(f.service.handoff(1,f.first).state==State.CANCELLED&&f.attempts()==0,"consent withdrawal stops");
        f=new Fixture();f.ready();f.blocked=true;check(f.service.handoff(1,f.first).state==State.SAFETY_STOPPED&&f.attempts()==0,"late block");
    }
    static void evidenceAndDisabled(){
        Fixture f=new Fixture();f.clear=false;f.ready();check(f.service.handoff(1,f.first).state==State.REVIEW_REQUIRED&&f.attempts()==0,"code alone insufficient");
        f.clear=true;f.merchantMissing=true;check(f.service.reassess(1,f.first).state==State.REVIEW_REQUIRED,"merchant required");
        f.merchantMissing=false;f.reviewPending=true;check(f.service.reassess(1,f.first).state==State.REVIEW_REQUIRED,"manual review pending");
        f.reviewPending=false;check(f.service.reassess(1,f.first).state==State.READY,"independent authority review");
        f.available=false;check(f.service.handoff(1,f.first).state==State.READY&&f.attempts()==0,"unavailable authority stops");
        RendezvousMeetingService off=new RendezvousMeetingService(Mode.DISABLED,f.db,f,(m,t,a,p,n)->false,new RendezvousHandoffLegacyTeamPort(),f.time,1000);
        final Fixture fixture=f;rejected("W16_MEETING_DISABLED",()->off.issue(1,fixture.first));
        f=new Fixture();f.ready();f.evidenceUnknown=true;check(f.service.handoff(1,f.first).state==State.REVIEW_REQUIRED&&f.attempts()==0,"withdrawn evidence fails closed");
        f=new Fixture();f.ready();f.activityId=100;check(f.service.handoff(1,f.first).state==State.REVIEW_REQUIRED&&f.attempts()==0,"changed activity cannot reuse confirmation");
    }
    static void ambiguousAndRace(){
        Fixture f=new Fixture();f.ready();f.fail=true;
        check(f.service.handoff(1,f.first).state==State.RECOVERY_REQUIRED,"unknown result");
        check(f.db.meeting(f.match).teamId==77&&f.service.read(1,f.first).teamId==null,"retain recovery ID privately");
        f.service.handoff(2,f.second);check(f.attempts()==1,"known team is not created again");
        f=new Fixture();f.ready();Fixture race=f;f.during=()->race.service.cancel(2,race.second);
        check(f.service.handoff(1,f.first).state==State.RECOVERY_REQUIRED,"cancel in flight never reports success");
        f=new Fixture();f.ready();f.db.save(f.db.meeting(f.match).state(State.IN_PROGRESS));
        check(f.service.handoff(2,f.second).state==State.RECOVERY_REQUIRED&&f.attempts()==0,"legacy claim without request id is not rerun");
        check(f.service.handoffStateFor(f.match)==HandoffState.UNKNOWN,"unclassified claim stays unknown");
        f=new Fixture();f.ready();f.db.uncertainClaimCommit=true;
        try{f.service.handoff(1,f.first);throw new AssertionError("unknown commit must throw");}
        catch(IllegalStateException e){check("synthetic unknown claim commit".equals(e.getMessage()),"unknown claim error");}
        String requestId=f.db.handoffRequest(f.match).requestId;
        check(!f.db.handoffRequest(f.match).dispatched&&f.attempts()==0,"crash before the call has no team effect");
        check(f.service.handoff(2,f.second).state==State.COMPLETED&&f.attempts()==1,"same request converges");
        check(requestId.equals(f.db.handoffRequest(f.match).requestId),"timeout does not allocate a new request");
        f=new Fixture();f.ready();Fixture concurrent=f;f.during=()->{
            check(concurrent.service.handoff(2,concurrent.second).state==State.RECOVERY_REQUIRED,"second caller does not create");
        };
        check(f.service.handoff(1,f.first).state==State.COMPLETED&&f.attempts()==1,"in-flight replay never creates again");
    }
    static void evidenceBoundary(){
        Fixture f=new Fixture();f.ready();Fixture expiry=f;f.duringEvidence=()->expiry.time.now=31000;
        check(f.service.handoff(1,f.first).state==State.EXPIRED&&f.attempts()==0,"expiry during evidence stops before claim");
        f=new Fixture();f.ready();Fixture block=f;f.duringEvidence=()->block.blocked=true;
        check(f.service.handoff(1,f.first).state==State.SAFETY_STOPPED&&f.attempts()==0,"block during evidence stops before claim");
        f=new Fixture();String id=f.issue();f.service.confirm(1,f.first,id);Fixture confirm=f;
        f.duringEvidence=()->confirm.time.now=31000;
        check(f.service.confirm(2,f.second,id).state==State.EXPIRED,"late evidence cannot publish READY");
    }
    /** Calls the real adapter against a synthetic implementation of the unchanged API. */
    static final class Teams {
        int creates,joins; boolean createThrows,joinThrows,missingInvite,wrongJoin;
        final IPlayTeamService service=(IPlayTeamService)Proxy.newProxyInstance(
            IPlayTeamService.class.getClassLoader(),new Class<?>[]{IPlayTeamService.class},(proxy,method,args)->{
                if ("create".equals(method.getName())) {
                    creates++;check(args.length==6&&Long.valueOf(1).equals(args[0])&&Integer.valueOf(2).equals(args[1])
                        &&Long.valueOf(99).equals(args[2])&&args[3]==null&&Integer.valueOf(2).equals(args[4])
                        &&Integer.valueOf(1).equals(args[5]),"real create signature/private two-member activity binding");
                    if(createThrows)throw new IllegalStateException("synthetic create unknown");
                    PlayTeam t=new PlayTeam();t.setId(77L);if(!missingInvite)t.setInviteCode("synthetic-invite");return t;
                }
                if ("join".equals(method.getName())) {
                    joins++;check("synthetic-invite".equals(args[0])&&Long.valueOf(2).equals(args[1]),"join binding");
                    if(joinThrows)throw new IllegalStateException("synthetic join unknown");
                    PlayTeam t=new PlayTeam();t.setId(wrongJoin?78L:77L);return t;
                }
                throw new AssertionError("unexpected team call "+method.getName());
            });
        ExistingPlayTeamMeetingHandoff adapter(){return new ExistingPlayTeamMeetingHandoff(
            ExistingPlayTeamMeetingHandoff.Mode.ISOLATED_TEST_DOUBLE_ONLY,service);}
    }
    static void realAdapterContract(){
        Teams t=new Teams();ExistingPlayTeamMeetingHandoff off=new ExistingPlayTeamMeetingHandoff(t.service);
        check(!off.available(),"adapter defaults disabled");
        try{off.createAndJoin(1,2,99);throw new AssertionError("disabled adapter executed");}
        catch(IllegalStateException e){check("W16_LEGACY_HANDOFF_DISABLED".equals(e.getMessage()),"disabled error");}
        check(t.creates==0&&t.joins==0,"disabled has no team effects");
        try{t.adapter().createAndJoin(1,1,99);throw new AssertionError("same actor accepted");}
        catch(IllegalArgumentException expected){check(t.creates==0,"invalid actor has no team effects");}
        check(t.adapter().createAndJoin(1,2,99)==77&&t.creates==1&&t.joins==1,"real adapter successful synthetic call");
        for(int kind=0;kind<4;kind++){
            t=new Teams();t.createThrows=kind==0;t.joinThrows=kind==1;t.missingInvite=kind==2;t.wrongJoin=kind==3;
            try{t.adapter().createAndJoin(1,2,99);throw new AssertionError("unknown result accepted");}
            catch(RecoveryRequired e){check(kind==0?e.knownTeamId==null:Long.valueOf(77).equals(e.knownTeamId),"private recovery ID");}
            check(t.creates==1&&t.joins==((kind==0||kind==2)?0:1),"adapter never retries/compensates");
        }
    }
    @Test void dualAndReplayGroup() { dualAndReplay(); }
    @Test void expiryCancellationSafetyGroup() { expiryCancellationSafety(); }
    @Test void evidenceAndDisabledGroup() { evidenceAndDisabled(); }
    @Test void ambiguousAndRaceGroup() { ambiguousAndRace(); }
    @Test void evidenceBoundaryGroup() { evidenceBoundary(); }
    @Test void realAdapterContractGroup() { realAdapterContract(); }
    public static void main(String[]args){dualAndReplay();expiryCancellationSafety();evidenceAndDisabled();ambiguousAndRace();evidenceBoundary();realAdapterContract();System.out.println("PASS W16.3 focused synthetic contracts (6 groups, real adapter API included)");}
}
