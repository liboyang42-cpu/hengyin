package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.impl.AdvancedActionJudgement.*;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.AdvancedGameRandom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Boundary checks only; original runtime/SQL acceptance remains with root's unified regression. */
class AdvancedActionJudgementTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void qaProposesOnlyLocalScoreStateAndOrderedChoices() throws Exception {
        JsonNode config=json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"xp\":10,\"options\":[{\"id\":\"a\",\"correct\":true,\"fb\":\"ok\"}]}}");
        ObjectNode original=json.createObjectNode();
        Plan plan=judge().evaluate(Action.SUBMIT_QA,new Input(config,original,Map.of("optionId","a"),3,7L));
        assertTrue(original.isEmpty());assertEquals(13,plan.score());assertTrue(plan.scoreAssigned());
        assertTrue(plan.state().path("qa").path("passed").asBoolean());
        assertEquals(List.of(new ChoiceRef("qa","a")),plan.choices());assertNull(plan.photoEvidence());
    }

    @Test void rejectedSortDoesNotPublishAnAttemptOrMutateTheInputState() throws Exception {
        JsonNode config=json.readTree("{\"sort\":{\"enabled\":true,\"items\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"answerOrder\":[\"a\",\"b\"]}}");
        ObjectNode original=json.createObjectNode();original.putObject("sort").put("attempts",2);
        ObjectNode before=original.deepCopy();
        assertThrows(IllegalArgumentException.class,()->judge().evaluate(Action.SUBMIT_SORT,
                new Input(config,original,Map.of("order",List.of("a","a")),5,7L)));
        assertEquals(before,original);
    }

    @Test void photoQaReturnsAnExplicitUploadReferenceRequirementWithoutInvokingAnExternalGuard() throws Exception {
        JsonNode config=json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"PHOTO\",\"xp\":2},\"outcomeRules\":{\"version\":1}}");
        ObjectNode original=json.createObjectNode();
        Plan plan=judge().evaluate(Action.SUBMIT_QA,new Input(config,original,Map.of("imageUrl","owned-reference"),5,7L));
        assertEquals(new PhotoEvidence("owned-reference",7L),plan.photoEvidence());
        assertEquals(7,plan.score());assertTrue(plan.state().path("qa").path("finished").asBoolean());
        assertTrue(original.isEmpty(),"only the parent may apply a proposal after checking the original upload reference");
    }

    @Test void challengeUsesTheOriginalClockAndIdentitySourcesAndKeepsMemberAttemptRules() throws Exception {
        AtomicLong now=new AtomicLong(100_000);AtomicInteger clockCalls=new AtomicInteger(),ids=new AtomicInteger();
        AdvancedGameClock clock=()->{clockCalls.incrementAndGet();return new Date(now.get());};
        AdvancedActionJudgement judge=new AdvancedActionJudgement(json,clock,bound->0,()->{ids.incrementAndGet();return 42L;});
        JsonNode config=json.readTree("{\"reaction\":{\"enabled\":true,\"rounds\":1,\"goalMs\":200}}");
        Plan start=judge.evaluate(Action.START_CHALLENGE,new Input(config,json.createObjectNode(),Map.of("game","reaction"),5,7L));
        assertEquals(2,clockCalls.get());assertEquals(1,ids.get());assertFalse(start.scoreAssigned());
        assertEquals(42,start.state().path("reaction").path("attempt").path("id").asLong());
        judge.requireCurrentAttempt(start.state().path("reaction"),Map.of("attemptId",42),7L);
        assertThrows(IllegalStateException.class,()->judge.requireCurrentAttempt(start.state().path("reaction"),Map.of("attemptId",42),8L));
        AdvancedActionJudgement.saveTeamMemberChallenge(start.state(),"reaction",7L);
        assertEquals(42,start.state().path("teamMemberChallenges").path("7").path("reaction").path("attempt").path("id").asLong());
        now.set(start.state().path("reaction").path("attempt").path("expiresAtMs").asLong()+1);
        assertThrows(IllegalStateException.class,()->judge.requireCurrentAttempt(start.state().path("reaction"),Map.of("attemptId",42),7L));
    }

    @Test void repeatedCoinRetainsTheFirstServerDrawWithoutSamplingAgain() throws Exception {
        AtomicInteger draws=new AtomicInteger();AdvancedGameRandom random=bound->{draws.incrementAndGet();return 0;};
        AdvancedActionJudgement judge=new AdvancedActionJudgement(json,()->new Date(100_000),random,()->42);
        JsonNode config=json.readTree("{\"coinFlip\":{\"enabled\":true,\"heads\":{\"action\":\"heads\"},\"tails\":{\"action\":\"tails\"}}}");
        Plan first=judge.evaluate(Action.FLIP_COIN,new Input(config,json.createObjectNode(),Map.of(),null,7L));
        Plan again=judge.evaluate(Action.FLIP_COIN,new Input(config,first.state(),Map.of(),null,7L));
        assertEquals(1,draws.get());assertEquals(first.state(),again.state());
        assertFalse(first.scoreAssigned());assertNull(first.score());assertNull(again.score());
    }

    @Test void publishedStaticObjectiveEntrypointsRetainAnswerAndChoiceContracts() throws Exception {
        JsonNode config=json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"answerText\":\"A、B\"},\"sort\":{\"enabled\":true,\"items\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"answerOrder\":[\"b\",\"a\"]}}");
        JsonNode answers=json.readTree("{\"qa\":{\"input\":\" A \"},\"sort\":{\"order\":[\"b\",\"a\"]}}");
        assertTrue(AdvancedGameRuntimeServiceImpl.verifyObjectiveAnswers(config,answers));
        List<String[]> choices=AdvancedGameRuntimeServiceImpl.verifiedObjectiveChoices(config,answers);
        assertEquals(1,choices.size());assertArrayEquals(new String[]{"sort",null},choices.get(0));
        ((ObjectNode)answers).put("passed",true);
        assertThrows(IllegalArgumentException.class,()->AdvancedGameRuntimeServiceImpl.verifyObjectiveAnswers(config,answers));
    }

    private AdvancedActionJudgement judge() {return new AdvancedActionJudgement(json,()->new Date(100_000),bound->0,()->42);}
}
