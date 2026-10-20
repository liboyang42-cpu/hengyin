package com.chengyinhub.business.discovery;

import static com.chengyinhub.business.discovery.EggDiscoveryEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EggDiscoveryEvaluatorTest {
    private final Instant start = Instant.parse("2026-10-01T00:00:00Z");
    private final Instant now = start.plusSeconds(600);
    private final Scope theme = Scope.theme("topic-1", "release-1", "run-1");
    private Rule rule(Scope scope, List<String> steps) {
        return new Rule(scope, "egg-1", 1, steps, start, start.plusSeconds(300), Duration.ofSeconds(60), "Asia/Shanghai");
    }
    private Event event(String id, long seq, String step, long seconds) {
        return new Event(id, 7, theme, seq, step, start.plusSeconds(seconds), true);
    }
    private Snapshot snapshot(Rule rule, List<Event> events) {
        return new Snapshot(7, rule, now, true, true, true, true, true, events);
    }
    private Receipt evaluate(Snapshot facts) {
        return new EggDiscoveryEvaluator(Mode.ISOLATED_EVALUATION, (m, s, d, n) -> facts,
                Clock.fixed(now, ZoneOffset.UTC)).discover(7, theme, "egg-1");
    }
    private Receipt evaluate(List<Event> events) { return evaluate(snapshot(rule(theme, Arrays.asList("a", "b")), events)); }
    private void blocked(String error, Runnable action) {
        assertEquals(error, assertThrows(IllegalStateException.class, action::run).getMessage());
    }
    @Test void disabledByDefault() { blocked("DISCOVERY_DISABLED", () -> new EggDiscoveryEvaluator().discover(7, theme, "egg-1")); }
    @Test void orderedAndDuplicateTransportIsStable() {
        Event a = event("receipt-a", 1, "a", 1), b = event("receipt-b", 2, "b", 10);
        Receipt one = evaluate(Arrays.asList(a,b));
        Receipt two = evaluate(Arrays.asList(b,a,b));
        assertEquals(one.publicView(), two.publicView());
        assertEquals("ISOLATED_EVALUATION", one.provenance);
        assertEquals(7, one.memberId); assertEquals(theme, one.scope);
    }
    @Test void changedEventWithSameIdIsRejected() {
        blocked("EVENT_ID_CONFLICT", () -> evaluate(Arrays.asList(event("same",1,"a",1),event("same",1,"b",2))));
    }
    @Test void sequenceCollisionRejected() {
        blocked("EVENT_SEQUENCE_CONFLICT", () -> evaluate(Arrays.asList(event("one",1,"a",1),event("two",1,"b",2))));
    }
    @Test void reverseCommittedOrderDoesNotUnlock() { assertNull(evaluate(Arrays.asList(event("b",1,"b",2),event("a",2,"a",1)))); }
    @Test void latePhysicalEventCannotCompleteSequence() { assertNull(evaluate(Arrays.asList(event("a",1,"a",10),event("b",2,"b",9)))); }
    @Test void validWindowCanRestartAfterEarlierAttempt() {
        assertNotNull(evaluate(Arrays.asList(event("a1",1,"a",1),event("a2",2,"a",50),event("b",3,"b",100))));
    }
    @Test void timeWindowUsesServerEventsAndExclusiveEnd() {
        assertNull(evaluate(Arrays.asList(event("a",1,"a",1),event("b",2,"b",62))));
        assertNull(evaluate(Arrays.asList(event("a",1,"a",290),event("b",2,"b",300))));
        assertNotNull(evaluate(Arrays.asList(event("a",1,"a",0),event("b",2,"b",60))));
    }
    @Test void appendingLateEventsDoesNotChangeCompletedReceipt() {
        Event a=event("a",1,"a",1), b=event("b",2,"b",10);
        assertEquals(evaluate(Arrays.asList(a,b)).publicView(),
                evaluate(Arrays.asList(a,b,event("late",3,"a",0))).publicView());
    }
    @Test void futureTimeRejected() { blocked("FUTURE_EVENT", () -> evaluate(Arrays.asList(event("a",1,"a",601)))); }
    @Test void unverifiedClientClaimDoesNotUnlock() {
        assertNull(evaluate(Arrays.asList(new Event("a",7,theme,1,"a",start,true),
                new Event("b",7,theme,2,"b",start.plusSeconds(1),false))));
    }
    @Test void memberAndEveryScopeComponentAreExact() {
        for(Scope scope : Arrays.asList(Scope.theme("other","release-1","run-1"),
                Scope.theme("topic-1","other","run-1"),Scope.theme("topic-1","release-1","other"),
                Scope.map("topic-1","release-1","run-1"))) {
            blocked("EVENT_SCOPE_MISMATCH", () -> evaluate(Arrays.asList(new Event("a",7,scope,1,"a",start,true))));
        }
        blocked("EVENT_SCOPE_MISMATCH", () -> evaluate(Arrays.asList(new Event("a",8,theme,1,"a",start,true))));
    }
    @Test void currentAuthorityMustMatchOwnerDefinitionAndTime() {
        Rule r=rule(theme,Arrays.asList("a","b"));
        blocked("CURRENT_AUTHORITY_REQUIRED", () -> evaluate(new Snapshot(8,r,now,true,true,true,true,true,Collections.emptyList())));
        blocked("CURRENT_AUTHORITY_REQUIRED", () -> evaluate(new Snapshot(7,r,now.minusSeconds(1),true,true,true,true,true,Collections.emptyList())));
        blocked("CURRENT_AUTHORITY_REQUIRED", () -> evaluate(snapshot(rule(Scope.theme("topic-1","release-2","run-1"),Arrays.asList("a")),Collections.emptyList())));
    }
    @Test void cancelRevocationClosureAndUnsafePlaceBlockCompletedReplay() {
        Rule r=rule(theme,Arrays.asList("a","b")); List<Event> es=Arrays.asList(event("a",1,"a",1),event("b",2,"b",2));
        assertNotNull(evaluate(snapshot(r,es)));
        for(int flag=0;flag<5;flag++) {
            final Snapshot facts=new Snapshot(7,r,now,flag!=0,flag!=1,flag!=2,flag!=3,flag!=4,es);
            blocked("DISCOVERY_UNAVAILABLE", () -> evaluate(facts));
        }
    }
    @Test void repeatedStepRequiresDistinctCommittedReceipt() {
        Rule r=rule(theme,Arrays.asList("a","a")); Event a=event("a",1,"a",1);
        assertNull(evaluate(snapshot(r,Arrays.asList(a,a))));
        assertNotNull(evaluate(snapshot(r,Arrays.asList(a,event("a2",2,"a",2)))));
    }
    @Test void clientProjectionIsStrictAllowlistAndImmutable() {
        Receipt receipt=evaluate(Arrays.asList(event("secret-source-a",1,"a",1),event("secret-source-b",2,"b",2)));
        assertEquals(new HashSet<String>(Arrays.asList("receiptId","kind","discoveredAt")),receipt.publicView().keySet());
        assertFalse(receipt.publicView().toString().contains("secret"));
        assertFalse(receipt.publicView().toString().contains("egg-1"));
        assertThrows(UnsupportedOperationException.class, () -> receipt.publicView().put("answer","bad"));
    }
    @Test void ruleAndHistoryDefensivelyCopyInputs() {
        List<String> steps=new ArrayList<String>(Arrays.asList("a","b")); Rule r=rule(theme,steps); steps.clear();
        List<Event> es=new ArrayList<Event>(Arrays.asList(event("a",1,"a",1),event("b",2,"b",2)));
        Snapshot facts=snapshot(r,es); es.clear(); assertNotNull(evaluate(facts));
    }
    @Test void sameRawIdsAcrossMapAndThemeDoNotCollide() {
        Scope map=Scope.map("topic-1","release-1","run-1");
        Rule r=rule(map,Arrays.asList("a","b"));
        List<Event> es=Arrays.asList(new Event("a",7,map,1,"a",start,true),new Event("b",7,map,2,"b",start.plusSeconds(1),true));
        Receipt mapReceipt=new EggDiscoveryEvaluator(Mode.ISOLATED_EVALUATION,(m,s,d,n)->snapshot(r,es),Clock.fixed(now,ZoneOffset.UTC)).discover(7,map,"egg-1");
        assertNotEquals(mapReceipt.receiptId,evaluate(Arrays.asList(event("a",1,"a",0),event("b",2,"b",1))).receiptId);
    }
    @Test void lateCompletionRetainsEarlierNonDominatedPrefix() {
        Rule r=rule(theme,Arrays.asList("a","b","c"));
        Receipt receipt=evaluate(snapshot(r,Arrays.asList(event("a1",1,"a",0),event("b1",2,"b",10),
                event("a2",3,"a",20),event("b2",4,"b",30),event("c",5,"c",15))));
        assertNotNull(receipt); assertEquals(start.plusSeconds(15),receipt.discoveredAt);
    }
    @Test void lateRepeatedStepsStillRequireDistinctOrderedSources() {
        Rule r=rule(theme,Arrays.asList("a","b","b"));
        Receipt receipt=evaluate(snapshot(r,Arrays.asList(event("a1",1,"a",0),event("b1",2,"b",10),
                event("a2",3,"a",20),event("b2",4,"b",30),event("b3",5,"b",15))));
        assertNotNull(receipt); // first valid completion is already a1/b1/b2, each uses a distinct receipt
        assertEquals(start.plusSeconds(30),receipt.discoveredAt);
    }
    @Test void matcherAgreesWithExhaustiveSubsequenceOracle() {
        Random random=new Random(1741L);
        for(int trial=0;trial<300;trial++) {
            List<String> steps=new ArrayList<String>();
            for(int n=1+random.nextInt(4);n>0;n--) steps.add("s"+random.nextInt(3));
            List<Event> events=new ArrayList<Event>();
            for(int n=0;n<9;n++) events.add(event("e"+n,n+1,"s"+random.nextInt(3),random.nextInt(100)));
            Instant expected=null;
            for(int end=1;end<=events.size();end++) {
                if (hasPath(events.subList(0,end),steps,0,0,null,null)) { expected=events.get(end-1).occurredAt; break; }
            }
            Collections.shuffle(events,random);
            Receipt actual=evaluate(snapshot(rule(theme,steps),events));
            assertEquals(expected,actual==null?null:actual.discoveredAt,"trial "+trial);
        }
    }
    private boolean hasPath(List<Event> es,List<String> steps,int pos,int step,Instant first,Instant last) {
        if(step==steps.size()) return true;
        for(int i=pos;i<es.size();i++) {
            Event e=es.get(i);
            if(!steps.get(step).equals(e.stepId) || (last!=null && e.occurredAt.isBefore(last))
                    || (first!=null && Duration.between(first,e.occurredAt).getSeconds()>60)) continue;
            if(hasPath(es,steps,i+1,step+1,first==null?e.occurredAt:first,e.occurredAt)) return true;
        }
        return false;
    }
    @Test void pathologicalFrontierWorkFailsClosed() {
        List<Event> es=new ArrayList<Event>();
        for(int i=0;i<1600;i++) es.add(new Event("e"+i,7,theme,i+1,i%2==0?"a":"b",start.plusMillis(i),true));
        blocked("MATCH_WORK_BOUND_EXCEEDED", () -> evaluate(snapshot(rule(theme,Arrays.asList("a","b","c")),es)));
    }
    @Test void missingDefinitionZoneAndUnboundedHistoryFailClosed() {
        blocked("PUBLICATION_ZONE_REQUIRED", () -> new Rule(theme,"egg",1,Arrays.asList("a"),start,now,Duration.ofSeconds(1),null));
        blocked("EVENT_HISTORY_BOUND_EXCEEDED", () -> evaluate(Collections.nCopies(10001,event("a",1,"a",1))));
    }
}
