package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.service.IPlayTeamService;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.function.Function;
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
        boolean uncertainClaimCommit;
        public synchronized <T> T transaction(Function<Tx,T> work) {
            Map<String,Ticket> a=new HashMap<>(tickets); Map<String,Match>b=new HashMap<>(matches);
            Map<String,Meeting> c=new HashMap<>(meetings);
            final T result;
            try{result=work.apply(this);}catch(RuntimeException e){tickets=a;matches=b;meetings=c;throw e;}
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
    }
    static final class Fixture implements Safety, Policy, Handoff {
        final Memory db=new Memory(); final Time time=new Time(); final RendezvousConsentService consent;
        final RendezvousMeetingService service; final String first,second,match;
        boolean blocked, clear=true, merchantMissing, reviewPending, available=true, fail, evidenceUnknown;
        long activityId=99;
        int attempts; Runnable during=()->{}, duringEvidence=()->{};
        Fixture(){
            consent=new RendezvousConsentService(new RendezvousConsentService.Store(){
                public <T>T transaction(Function<Transaction,T> work){return db.transaction(tx->work.apply(tx));}
            },this,time);
            first=consent.join(1,"pool","v1",100000).ticketId;
            second=consent.join(2,"pool","v1",100000).ticketId;
            match=consent.propose(first,second,"public-place");
            consent.accept(1,first,1); consent.accept(2,second,2);
            service=new RendezvousMeetingService(Mode.ISOLATED_CONTRACT_EVALUATION,db,this,this,this,time,30000);
        }
        public boolean eligible(long a,String p,String v,long n){return true;}
        public boolean blocked(long a,long b){return blocked;}
        public boolean compatible(long a,long b,String p,long n){return true;}
        public boolean publicMeetingAllowed(String p,String place,long n){return true;}
        public long activity(Match m){return activityId;}
        public Evidence evaluate(Meeting m,Ticket a,Ticket b,long n){duringEvidence.run();return evidenceUnknown ? null : new Evidence(clear,clear,merchantMissing,!merchantMissing,reviewPending,!reviewPending);}
        public boolean available(){return available;}
        public long createAndJoin(long a,long b,long activity){
            check(a==1&&b==2&&activity==99,"bound actors/goal"); attempts++;
            check(db.meeting(match).state==State.IN_PROGRESS,"claim before side effects");
            during.run(); if(fail)throw new RecoveryRequired(77L); return 77;
        }
        String issue(){return service.issue(1,first).challengeId;}
        void ready(){String id=issue(); service.confirm(1,first,id); service.confirm(2,second,id);}
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
        check(f.service.handoff(1,f.first).state==State.CHALLENGED&&f.attempts==0,"one actor insufficient");
        f.service.confirm(2,f.second,id);check(f.service.handoff(1,f.first).state==State.COMPLETED,"both clear");
        f.service.handoff(2,f.second);check(f.attempts==1,"completed replay no duplicate team");
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
        f=new Fixture();f.ready();f.service.cancel(1,f.first);check(f.service.handoff(2,f.second).state==State.CANCELLED&&f.attempts==0,"either cancels");
        f=new Fixture();f.ready();f.consent.cancel(2,f.second);check(f.service.handoff(1,f.first).state==State.CANCELLED&&f.attempts==0,"consent withdrawal stops");
        f=new Fixture();f.ready();f.blocked=true;check(f.service.handoff(1,f.first).state==State.SAFETY_STOPPED&&f.attempts==0,"late block");
    }
    static void evidenceAndDisabled(){
        Fixture f=new Fixture();f.clear=false;f.ready();check(f.service.handoff(1,f.first).state==State.REVIEW_REQUIRED&&f.attempts==0,"code alone insufficient");
        f.clear=true;f.merchantMissing=true;check(f.service.reassess(1,f.first).state==State.REVIEW_REQUIRED,"merchant required");
        f.merchantMissing=false;f.reviewPending=true;check(f.service.reassess(1,f.first).state==State.REVIEW_REQUIRED,"manual review pending");
        f.reviewPending=false;check(f.service.reassess(1,f.first).state==State.READY,"independent authority review");
        f.available=false;check(f.service.handoff(1,f.first).state==State.READY&&f.attempts==0,"unavailable authority stops");
        RendezvousMeetingService off=new RendezvousMeetingService(Mode.DISABLED,f.db,f,f,f,f.time,1000);
        final Fixture fixture=f;rejected("W16_MEETING_DISABLED",()->off.issue(1,fixture.first));
        f=new Fixture();f.evidenceUnknown=true;f.ready();check(f.service.handoff(1,f.first).state==State.REVIEW_REQUIRED&&f.attempts==0,"null evidence fails closed");
        f=new Fixture();f.ready();f.activityId=100;check(f.service.handoff(1,f.first).state==State.REVIEW_REQUIRED&&f.attempts==0,"changed activity cannot reuse confirmation");
    }
    static void ambiguousAndRace(){
        Fixture f=new Fixture();f.ready();f.fail=true;
        check(f.service.handoff(1,f.first).state==State.RECOVERY_REQUIRED,"unknown result");
        check(f.db.meeting(f.match).teamId==77&&f.service.read(1,f.first).teamId==null,"retain recovery ID privately");
        f.service.handoff(2,f.second);check(f.attempts==1,"no retry uncertain create/join");
        f=new Fixture();f.ready();Fixture race=f;f.during=()->race.service.cancel(2,race.second);
        check(f.service.handoff(1,f.first).state==State.RECOVERY_REQUIRED,"cancel in flight never reports success");
        f=new Fixture();f.ready();f.db.save(f.db.meeting(f.match).state(State.IN_PROGRESS));
        check(f.service.handoff(2,f.second).state==State.IN_PROGRESS&&f.attempts==0,"crash claim never rerun");
        f=new Fixture();f.ready();f.db.uncertainClaimCommit=true;
        try{f.service.handoff(1,f.first);throw new AssertionError("unknown commit must throw");}
        catch(IllegalStateException e){check("synthetic unknown claim commit".equals(e.getMessage()),"unknown claim error");}
        check(f.service.handoff(2,f.second).state==State.IN_PROGRESS&&f.attempts==0,"unknown claim acknowledgement cannot reissue effects");
        f=new Fixture();f.ready();Fixture concurrent=f;f.during=()->{
            check(concurrent.service.handoff(2,concurrent.second).state==State.IN_PROGRESS,"second caller observes claim");
        };
        check(f.service.handoff(1,f.first).state==State.COMPLETED&&f.attempts==1,"in-flight replay never creates again");
    }
    static void evidenceBoundary(){
        Fixture f=new Fixture();f.ready();Fixture expiry=f;f.duringEvidence=()->expiry.time.now=31000;
        check(f.service.handoff(1,f.first).state==State.EXPIRED&&f.attempts==0,"expiry during evidence stops before claim");
        f=new Fixture();f.ready();Fixture block=f;f.duringEvidence=()->block.blocked=true;
        check(f.service.handoff(1,f.first).state==State.SAFETY_STOPPED&&f.attempts==0,"block during evidence stops before claim");
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
    public static void main(String[]args){dualAndReplay();expiryCancellationSafety();evidenceAndDisabled();ambiguousAndRace();evidenceBoundary();realAdapterContract();System.out.println("PASS W16.3 focused synthetic contracts (6 groups, real adapter API included)");}
}
