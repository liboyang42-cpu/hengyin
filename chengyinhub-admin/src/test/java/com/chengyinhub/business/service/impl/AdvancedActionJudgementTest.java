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
        JsonNode config=json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"SHOT\",\"xp\":2}}");
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

    /**
     * BE-08:高级 {@code qa.TYPE} 的别名只来自模板 {@code advancedConfigJson} 根层政策。
     * 没配政策时逐字保留原比较器(trim + 大小写,不折全角、不删中间空白)。
     */
    @Test void typeQaUsesTheRootAliasPolicyOnlyWhenOneIsConfigured() throws Exception {
        JsonNode withPolicy=json.readTree("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"answerText\":\"A、B\"},"
                +"\"answerPolicy\":{\"version\":1,\"normalizationPolicy\":\"LEGACY_V1\",\"acceptedAliases\":[\"甲\"]}}");
        assertTrue(judge().evaluate(Action.SUBMIT_QA,new Input(withPolicy,json.createObjectNode(),
                Map.of("input","甲"),null,7L)).state().path("qa").path("passed").asBoolean());
        assertTrue(judge().evaluate(Action.SUBMIT_QA,new Input(withPolicy,json.createObjectNode(),
                Map.of("input"," a "),null,7L)).state().path("qa").path("passed").asBoolean(),"canonical 保留");
        assertFalse(judge().evaluate(Action.SUBMIT_QA,new Input(withPolicy,json.createObjectNode(),
                Map.of("input","甲吧"),null,7L)).state().path("qa").path("passed").asBoolean(),"近似错字不通过");

        JsonNode withoutPolicy=json.readTree("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"answerText\":\"Ａ\"}}");
        assertTrue(judge().evaluate(Action.SUBMIT_QA,new Input(withoutPolicy,json.createObjectNode(),
                Map.of("input","ａ"),null,7L)).state().path("qa").path("passed").asBoolean(),"仍忽略大小写");
        assertTrue(judge().evaluate(Action.SUBMIT_QA,new Input(withoutPolicy,json.createObjectNode(),
                Map.of("input","A"),null,7L)).state().path("qa").path("passed").asBoolean(),
                "2026-11-14 起没配政策也走共享归一器(NFKC):全角「Ａ」与「A」等价,旧写法「ａ」仍然通过");
        assertFalse(judge().evaluate(Action.SUBMIT_QA,new Input(withoutPolicy,json.createObjectNode(),
                Map.of("input","B"),null,7L)).state().path("qa").path("passed").asBoolean(),"不同答案仍然判错");
    }

    @Test void combinedAlbumMustBeAcknowledgedBeforeDrawingAndBeforeCompletion() throws Exception {
        JsonNode config=json.readTree("{\"album\":{\"enabled\":true},\"random\":{\"enabled\":true,\"drawCount\":1,\"items\":[{\"id\":\"a\",\"weight\":1,\"label\":\"label\",\"content\":\"body\"}]}}");
        ObjectNode state=json.createObjectNode();
        assertFalse(AdvancedActionJudgement.mechanicsReady(config,state));
        assertThrows(IllegalStateException.class,()->judge().evaluate(Action.DRAW,new Input(config,state,Map.of(),0,7L)));
        state.putObject("album").put("done",true);
        assertFalse(AdvancedActionJudgement.mechanicsReady(config,state));
        Plan draw=judge().evaluate(Action.DRAW,new Input(config,state,Map.of(),0,7L));
        assertTrue(AdvancedActionJudgement.mechanicsReady(config,draw.state()));
        assertEquals("body",draw.state().path("draws").path(0).path("content").asText());
    }

    // ==================== F02 按错答回复 ====================

    private static final String WRONG = ",\"wrongReplies\":[{\"match\":[\"ABD\",\"abx\"],\"reply\":\"前两个字母对了\"}]";

    @Test void qaTypeNearMissReturnsItsOwnReplyAndStillCountsTheAttempt() throws Exception {
        JsonNode config=json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"answerText\":\"ABC\",\"maxTries\":3"+WRONG+"},"
                +"\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{\"reply\":\"还差一点\"}}}");
        Plan miss=judge().evaluate(Action.SUBMIT_QA,new Input(config,json.createObjectNode(),Map.of("input","abd"),0,7L));
        JsonNode qa=miss.state().path("qa");
        assertEquals("前两个字母对了",qa.path("lastFeedback").asText());
        assertTrue(qa.path("lastNearMiss").asBoolean());
        assertEquals(1,qa.path("attempts").asInt());
        assertFalse(qa.path("finished").asBoolean());
        assertEquals(0,miss.score(),"错答回复不给奖励也不扣分");

        Plan generic=judge().evaluate(Action.SUBMIT_QA,new Input(config,miss.state(),Map.of("input","XYZ"),0,7L));
        assertEquals("还差一点",generic.state().path("qa").path("lastFeedback").asText(),"没命中回退到 fail.reply");
        assertFalse(generic.state().path("qa").path("lastNearMiss").asBoolean());
        assertEquals(2,generic.state().path("qa").path("attempts").asInt());
    }

    @Test void qaTypeNearMissIgnoresCaseAndFullWidth() throws Exception {
        JsonNode config=json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"answerText\":\"ABC\""+WRONG+"}}");
        Plan miss=judge().evaluate(Action.SUBMIT_QA,new Input(config,json.createObjectNode(),Map.of("input","ＡＢＸ"),0,7L));
        assertEquals("前两个字母对了",miss.state().path("qa").path("lastFeedback").asText());
        Plan pass=judge().evaluate(Action.SUBMIT_QA,new Input(config,miss.state(),Map.of("input","abc"),0,7L));
        assertTrue(pass.state().path("qa").path("passed").asBoolean());
        assertFalse(pass.state().path("qa").has("lastNearMiss"),"答对后清掉上一次的错答痕迹");
    }

    @Test void finalNearMissCarriesItsReplyIntoTheFailOutcome() throws Exception {
        JsonNode config=json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"answerText\":\"ABC\",\"maxTries\":1"+WRONG+"},"
                +"\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{\"reply\":\"还差一点\"}}}");
        Plan miss=judge().evaluate(Action.SUBMIT_QA,new Input(config,json.createObjectNode(),Map.of("input","ABD"),0,7L));
        ObjectNode decision=judge().serverGameplayDecision("SUBMIT_QA",config,miss.state());
        assertEquals("FAIL",decision.path("outcome").asText());
        assertEquals(0,decision.path("selection").path("wrongReplyIndex").asInt());
        JsonNode after=com.chengyinhub.business.service.support.GameplayOutcomeRules.select(config,decision);
        assertEquals("前两个字母对了",after.path("reply").asText());
    }

    @Test void typeInNearMissUsesTheSharedNormalizer() throws Exception {
        JsonNode config=json.readTree("{\"typeIn\":{\"enabled\":true,\"title\":\"t\",\"target\":\"ABC\",\"seconds\":30,\"tries\":0"+WRONG+"},"
                +"\"outcomeRules\":{\"version\":1,\"pass\":{},\"fail\":{\"reply\":\"还差一点\"}}}");
        ObjectNode state=json.createObjectNode();state.putObject("typeIn").put("startedAtMs",99_000L);
        Plan miss=judge().evaluate(Action.SUBMIT_TYPE_IN,new Input(config,state,Map.of("text","ａｂｄ","elapsedMs",500),0,7L));
        assertEquals("前两个字母对了",miss.state().path("typeIn").path("lastFeedback").asText());
        assertEquals(1,miss.state().path("typeIn").path("attempts").asInt());
        ObjectNode again=miss.state().deepCopy();((ObjectNode)again.path("typeIn")).put("startedAtMs",99_000L);
        Plan generic=judge().evaluate(Action.SUBMIT_TYPE_IN,new Input(config,again,Map.of("text","QQQ","elapsedMs",500),0,7L));
        assertEquals("还差一点",generic.state().path("typeIn").path("lastFeedback").asText());
    }

    @Test void hangmanNearMissRepliesAndKeepsFilledSlots() throws Exception {
        JsonNode config=json.readTree("{\"hangman\":{\"enabled\":true,\"answer\":\"ABC\""+WRONG+"}}");
        Plan miss=judge().evaluate(Action.SUBMIT_HANGMAN,new Input(config,json.createObjectNode(),Map.of("input","abd"),0,7L));
        JsonNode kit=miss.state().path(com.chengyinhub.business.service.support.HangmanGameplay.STATE_KEY);
        assertEquals("WRONG",kit.path("lastVerdict").asText());
        assertEquals("前两个字母对了",kit.path("lastFeedback").asText());
        Plan generic=judge().evaluate(Action.SUBMIT_HANGMAN,new Input(config,miss.state(),Map.of("input","QQQ"),0,7L));
        JsonNode genericKit=generic.state().path(com.chengyinhub.business.service.support.HangmanGameplay.STATE_KEY);
        assertEquals("",genericKit.path("lastFeedback").asText(),"没配 fail.reply 时不编一句,由客户端兜底");
        assertFalse(genericKit.path("lastNearMiss").asBoolean());
    }

    // ==================== F04 确认后锁定 ====================

    @Test void confirmedChoiceCannotBeSubmittedAgain() throws Exception {
        JsonNode config=json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"options\":[{\"id\":\"a\",\"correct\":true},{\"id\":\"b\"}]}}");
        Plan confirmed=judge().evaluate(Action.SUBMIT_QA,new Input(config,json.createObjectNode(),Map.of("optionId","a"),0,7L));
        IllegalStateException again=assertThrows(IllegalStateException.class,
                ()->judge().evaluate(Action.SUBMIT_QA,new Input(config,confirmed.state(),Map.of("optionId","b"),0,7L)));
        assertEquals("这一题已经答完了",again.getMessage());
        JsonNode open=json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"judge\":false,\"options\":[{\"id\":\"a\"},{\"id\":\"b\"}]}}");
        Plan picked=judge().evaluate(Action.SUBMIT_QA,new Input(open,json.createObjectNode(),Map.of("optionId","b"),0,7L));
        assertThrows(IllegalStateException.class,
                ()->judge().evaluate(Action.SUBMIT_QA,new Input(open,picked.state(),Map.of("optionId","a"),0,7L)));
    }

    // ==================== 拍照作证据步 ====================

    @Test void photoBesideQaNeverSettlesTheOutcomeButPhotoAloneStillDoes() throws Exception {
        String rules=",\"outcomeRules\":{\"version\":1,\"pass\":{\"reply\":\"继续\"},\"fail\":{\"reply\":\"再看看\"}}";
        JsonNode both=json.readTree("{\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"answerText\":\"ABC\"},\"photoCheck\":{\"enabled\":true,\"fallback\":\"pass\"}"+rules+"}");
        ObjectNode rejected=json.createObjectNode();rejected.putObject("photoCheck").put("verdict","REJECT");
        assertNull(judge().serverGameplayDecision("SUBMIT_PHOTO_CHECK",both,rejected),"证据步拍照被拒也不结算 FAIL");
        ObjectNode passedPhoto=json.createObjectNode();passedPhoto.putObject("photoCheck").put("verdict","PASS");
        assertNull(judge().serverGameplayDecision("SUBMIT_PHOTO_CHECK",both,passedPhoto),"证据步拍照通过也不抢先结算 PASS");
        Plan answered=judge().evaluate(Action.SUBMIT_QA,new Input(both,json.createObjectNode(),Map.of("input","abc"),0,7L));
        assertEquals("PASS",judge().serverGameplayDecision("SUBMIT_QA",both,answered.state()).path("outcome").asText(),"主玩法答对才结算");
        JsonNode photoOnly=json.readTree("{\"photoCheck\":{\"enabled\":true}"+rules+"}");
        assertEquals("FAIL",judge().serverGameplayDecision("SUBMIT_PHOTO_CHECK",photoOnly,rejected).path("outcome").asText(),"单开拍照仍由拍照结算");
    }

    private AdvancedActionJudgement judge() {return new AdvancedActionJudgement(json,()->new Date(100_000),bound->0,()->42);}
}
