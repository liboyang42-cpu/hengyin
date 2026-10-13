package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameplayZeroHpR5Test {
    private final ObjectMapper json = new ObjectMapper();
    private final GameplaySurvivalEngine engine = new GameplaySurvivalEngine();
    private final JourneyRules rules = new JourneyRules();
    private final TopicRouteEngine.State state = new TopicRouteEngine.State();
    private final ObjectNode snapshot = json.createObjectNode();

    private ObjectNode policy(String key) {
        rules.setCheckEnabled(true); rules.getHp().setMax(12);
        state.getVariables().put("sys.hp", 0);
        ObjectNode theme = snapshot.putObject("themeConfig").put("schemaVersion", 1).put("capacity", 10);
        theme.putArray("items");
        return theme.putObject("gameplayRuntime").put("runtimeScope", "PLAYER").putObject("survival")
                .put("zeroHpPolicy", key).put("actionLimitPolicy", "BLOCK_OVERFLOW").put("zeroMessage", "Rest first");
    }

    @Test void themeFailureFreezesOneFailureCard() {
        policy("THEME_FAIL");
        engine.syncChallenge(snapshot, rules, state, 1000);
        assertFalse(state.getThemeRuntime().path("result").path("success").asBoolean(true));
        assertEquals("HP_ZERO", state.getThemeRuntime().path("result").path("reason").asText());
        assertEquals("ENDED", state.getStatus());
        engine.syncChallenge(snapshot, rules, state, 2000);
        assertEquals(1000, state.getThemeRuntime().path("result").path("endedAt").asLong());
    }

    @Test void zeroViewIncludesSafeMemberGatingAndFrozenFallbackWithoutWeRunDetails() {
        policy("STEPS").putObject("zeroSteps").put("version",1).put("steps",100).put("restoreTo",4).put("limit",2).put("thenPolicy","THEME_FAIL");
        engine.syncZeroHp(snapshot,rules,state,1000);
        state.getThemeRuntime().with("zeroHp").put("recoveryMemberId",7).put("baselineSteps",1234).put("openid","private").putObject("highWaterByDay").put("2026-10-10",1234);
        ObjectNode view=GameplaySurvivalEngine.zeroView(snapshot.path("themeConfig"),state,1000);
        assertTrue(view.path("allowedActions").isArray());assertEquals(0,view.path("allowedActions").size());
        assertTrue(view.path("disabledReason").isTextual());assertTrue(view.has("recoveryMemberId"));assertTrue(view.path("recoveryMemberName").isTextual());
        assertEquals("THEME_FAIL",view.path("thenPolicy").asText());
        assertFalse(view.has("baselineSteps"));assertFalse(view.has("highWaterByDay"));assertFalse(view.has("openid"));
    }

    @Test void currentMemberButtonsHonorTheBaselineAndRuntimeAvailability() {
        policy("STEPS").putObject("zeroSteps").put("version",1).put("steps",100).put("restoreTo",4).put("limit",2).put("thenPolicy","THEME_FAIL");engine.syncZeroHp(snapshot,rules,state,1000);
        assertEquals(json.createArrayNode().add("ZERO_HP_STEPS"),GameplaySurvivalEngine.zeroView(snapshot.path("themeConfig"),state,1000,7L,7L,"队长","").path("allowedActions"));
        ObjectNode other=GameplaySurvivalEngine.zeroView(snapshot.path("themeConfig"),state,1000,8L,7L,"队长","");assertEquals(0,other.path("allowedActions").size());assertEquals("需由 队长 同步微信运动",other.path("disabledReason").asText());
        ObjectNode paused=GameplaySurvivalEngine.zeroView(snapshot.path("themeConfig"),state,1000,7L,7L,"队长","本局暂停");assertEquals(0,paused.path("allowedActions").size());assertEquals("本局暂停",paused.path("disabledReason").asText());
        state.getThemeRuntime().with("zeroHp").put("used",2);ObjectNode exhausted=GameplaySurvivalEngine.zeroView(snapshot.path("themeConfig"),state,1000,7L,7L,"队长","");assertEquals(0,exhausted.path("allowedActions").size());assertEquals("恢复次数已用完",exhausted.path("disabledReason").asText());
    }

    @Test void waitSyncHasNoRecoveryMemberAndHealthyEpisodeHasNoButtons() {
        policy("WAIT").putObject("zeroWait").put("version",1).put("minutes",1).put("restoreTo",4).put("limit",2).put("thenPolicy","BLOCK_HP_COST");engine.syncZeroHp(snapshot,rules,state,1000);
        ObjectNode wait=GameplaySurvivalEngine.zeroView(snapshot.path("themeConfig"),state,1000,7L,null,"","");assertEquals(json.createArrayNode().add("ZERO_HP_SYNC"),wait.path("allowedActions"));assertTrue(wait.path("recoveryMemberId").isNull());assertEquals("BLOCK_HP_COST",wait.path("thenPolicy").asText());
        engine.syncZeroHp(snapshot,rules,state,61000);ObjectNode healthy=GameplaySurvivalEngine.zeroView(snapshot.path("themeConfig"),state,61000,7L,null,"","");assertEquals(0,healthy.path("allowedActions").size());assertEquals("当前无需恢复",healthy.path("disabledReason").asText());
    }

    @Test void blockHpMessageIsVisibleAtZeroWithoutCreatingRecoveryOrChangingLegacyState() {
        ObjectNode survival=policy("BLOCK_HP_COST");engine.syncChallenge(snapshot,rules,state,1000);
        assertFalse(state.getThemeRuntime().has("zeroHp"));
        ThemeRuntimeEngine runtime=new ThemeRuntimeEngine();ObjectNode view=runtime.view(snapshot.path("themeConfig"),state,rules);
        assertEquals("Rest first",view.path("zeroHp").path("message").asText());assertEquals("BLOCK_HP_COST",view.path("zeroHp").path("policy").asText());assertFalse(state.getThemeRuntime().has("zeroHp"));
        state.getVariables().put("sys.hp",3);assertFalse(runtime.view(snapshot.path("themeConfig"),state,rules).has("zeroHp"));
        state.getVariables().put("sys.hp",0);rules.getHp().setEnabled(false);assertFalse(runtime.view(snapshot.path("themeConfig"),state,rules).has("zeroHp"));rules.getHp().setEnabled(true);
        survival.remove("zeroMessage");state.getVariables().put("sys.hp",0);assertFalse(runtime.view(snapshot.path("themeConfig"),state,rules).has("zeroHp"));
    }

    @Test void waitUsesPersistentDeadlineAndRestoresOnceThenAppliesExhaustionPolicy() {
        ObjectNode p = policy("WAIT");
        p.putObject("zeroWait").put("version", 1).put("minutes", 1).put("restoreTo", 5).put("limit", 1).put("thenPolicy", "THEME_FAIL");
        engine.syncChallenge(snapshot, rules, state, 1000);
        String episode = state.getThemeRuntime().path("zeroHp").path("episodeId").asText();
        assertFalse(episode.isEmpty());
        engine.syncChallenge(snapshot, rules, state, 60999);
        assertEquals(0, state.getVariables().get("sys.hp"));
        assertEquals(episode, state.getThemeRuntime().path("zeroHp").path("episodeId").asText());
        engine.syncChallenge(snapshot, rules, state, 61000);
        assertEquals(5, state.getVariables().get("sys.hp"));
        assertEquals(1, state.getThemeRuntime().path("zeroHp").path("used").asInt());
        engine.syncChallenge(snapshot, rules, state, 62000);
        assertEquals(1, state.getThemeRuntime().path("zeroHp").path("used").asInt());
        state.getVariables().put("sys.hp", 0);
        engine.syncChallenge(snapshot, rules, state, 63000);
        assertEquals("HP_ZERO", state.getThemeRuntime().path("result").path("reason").asText());
    }

    @Test void externalRecoveryClosesWaitWithoutConsumingOrPayingAgain() {
        policy("WAIT").putObject("zeroWait").put("version", 1).put("minutes", 1).put("restoreTo", 5).put("limit", 2).put("thenPolicy", "BLOCK_HP_COST");
        engine.syncChallenge(snapshot, rules, state, 1000);
        state.getVariables().put("sys.hp", 3);
        engine.syncChallenge(snapshot, rules, state, 61000);
        assertEquals(3, state.getVariables().get("sys.hp"));
        assertTrue(state.getThemeRuntime().path("zeroHp").path("resolved").asBoolean());
        assertEquals(0, state.getThemeRuntime().path("zeroHp").path("used").asInt());
    }

    @Test void v2ChallengeCountsWholeCompletionsAndNeverExtendsEpisodeOnRetry() {
        policy("CHALLENGE").putObject("zeroChallenge").put("version", 2).put("challengeNodeId", 101)
                .put("gameplayKey", "ballShake").put("requiredCompletions", 2).put("withinSeconds", 60)
                .put("restoreTo", 5).put("limit", 2).put("thenPolicy", "THEME_FAIL");
        snapshot.putObject("nodeConfigs").putObject("101").putObject("ballShake").put("enabled", true).put("goal", 3);
        engine.syncChallenge(snapshot, rules, state, 1000);
        assertEquals(2, state.getThemeRuntime().path("challenge").path("requiredCompletions").asInt());
        assertEquals(61000, state.getThemeRuntime().path("challenge").path("deadlineAt").asLong());
        engine.syncChallenge(snapshot, rules, state, 2000);
        assertEquals(61000, state.getThemeRuntime().path("challenge").path("deadlineAt").asLong());
        engine.syncChallenge(snapshot, rules, state, 61001);
        assertEquals("HP_ZERO", state.getThemeRuntime().path("result").path("reason").asText());
    }

    @Test void newConfigIsStrictAndLegacyChallengeRemainsReadable() {
        ObjectNode p = policy("WAIT");
        ObjectNode wait = p.putObject("zeroWait").put("version", 1).put("minutes", 30).put("restoreTo", 5).put("limit", 2).put("thenPolicy", "BLOCK_HP_COST");
        assertNotNull(ThemeRuntimeConfig.parseDraft(snapshot.path("themeConfig").toString()));
        wait.put("minutes", 0);
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(snapshot.path("themeConfig").toString()));
        wait.put("minutes", 30).put("thenPolicy", "WAIT");
        assertThrows(IllegalArgumentException.class, () -> ThemeRuntimeConfig.parseDraft(snapshot.path("themeConfig").toString()));
        p = policy("CHALLENGE");
        p.putObject("zeroChallenge").put("challengeNodeId", 101).put("restoreTo", 4).put("limit", 2);
        assertFalse(ThemeRuntimeConfig.parseDraft(snapshot.path("themeConfig").toString()).path("gameplayRuntime").path("survival").path("zeroChallenge").has("version"));
    }

    @Test void completedChildCannotBeRechargedWithAnotherRequestAndRecoveryIsOnce() {
        policy("CHALLENGE").putObject("zeroChallenge").put("version", 2).put("challengeNodeId", 101)
                .put("gameplayKey", "ballShake").put("requiredCompletions", 2).put("withinSeconds", 60)
                .put("restoreTo", 5).put("limit", 2).put("thenPolicy", "THEME_FAIL");
        snapshot.putObject("nodeConfigs").putObject("101").putObject("ballShake").put("enabled", true).put("goal", 3);
        engine.syncChallenge(snapshot, rules, state, 1000);
        String first = engine.challengeView(state).path("instanceId").asText();
        ObjectNode receipt = engine.submitChallenge(snapshot, rules, state, 7L, first, 101L, "one", true, 3L, false, 2000);
        assertFalse(receipt.path("success").asBoolean()); assertTrue(receipt.path("gameCompleted").asBoolean());
        assertEquals(0, state.getVariables().get("sys.hp"));
        assertEquals(receipt, engine.submitChallenge(snapshot, rules, state, 7L, first, 101L, "one", true, 3L, false, 2100));
        assertThrows(IllegalStateException.class, () -> engine.submitChallenge(snapshot, rules, state, 7L, first, 101L, "spam", true, 3L, false, 2100));
        String second = engine.challengeView(state).path("instanceId").asText(); assertNotEquals(first, second);
        assertTrue(engine.submitChallenge(snapshot, rules, state, 7L, second, 101L, "two", true, 3L, false, 3000).path("success").asBoolean());
        assertEquals(5, state.getVariables().get("sys.hp"));
        assertEquals(1, state.getThemeRuntime().path("zeroHp").path("used").asInt());
    }
    @Test void v2ChosenCoinIsAWholeCompletionAndDoesNotNeedASensorFallback() {
        ObjectNode survival=policy("CHALLENGE");survival.putObject("zeroChallenge").put("version",2).put("challengeNodeId",101).put("gameplayKey","coinFlip").put("requiredCompletions",2).put("withinSeconds",60).put("restoreTo",5).put("limit",2).put("thenPolicy","BLOCK_HP_COST");snapshot.putObject("nodeConfigs").putObject("101").putObject("coinFlip").put("enabled",true);
        assertDoesNotThrow(()->ThemeRuntimeConfig.parseDraft(snapshot.path("themeConfig").toString()));assertDoesNotThrow(()->GameplayRuntimeConfig.assertChallengeAlternative(survival,snapshot.path("nodeConfigs")));
        engine.syncChallenge(snapshot,rules,state,1000);assertEquals("coinFlip",engine.challengeView(state).path("gameplayKey").asText());String first=engine.challengeView(state).path("instanceId").asText();assertTrue(engine.submitChallenge(snapshot,rules,state,7L,first,101L,"one",true,null,false,2000).path("gameCompleted").asBoolean());assertEquals(0,state.getVariables().get("sys.hp"));
    }

}
