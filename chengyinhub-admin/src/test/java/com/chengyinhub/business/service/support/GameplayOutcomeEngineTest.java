package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameplayOutcomeEngineTest {
    private final ObjectMapper json = new ObjectMapper();
    private final GameplayOutcomeEngine engine = new GameplayOutcomeEngine();
    private final JourneyRules rules = new JourneyRules();
    private final ObjectNode snapshot = json.createObjectNode();
    private final TopicRouteEngine.State payer = new TopicRouteEngine.State();
    private final Map<Long, TopicRouteEngine.State> recipients = new LinkedHashMap<>();

    private ObjectNode setup(int hp, int cost) {
        rules.setCheckEnabled(true); rules.getHp().setMax(12);
        payer.getVariables().put("sys.hp", hp); recipients.put(7L, payer);
        ObjectNode theme = snapshot.putObject("themeConfig").put("capacity", 10);
        theme.putArray("items"); theme.putObject("gameplayRuntime").put("runtimeScope", "PLAYER").putObject("survival").put("zeroHpPolicy", "BLOCK_HP_COST");
        ObjectNode outcomes = snapshot.putObject("nodeConfigs").putObject("101").putObject("outcomeRules").put("version", 1);
        for (String outcome : Arrays.asList("pass", "fail")) outcomes.putObject(outcome).put("reply", outcome).putArray("costs").addObject().put("statKey", "sys.hp").put("amount", cost);
        return outcomes;
    }
    private ObjectNode decision(String outcome) { return json.createObjectNode().put("nodeId", 101).put("outcome", outcome); }

    @Test void feedbackRequiresSettledOriginalAfterAndDoesNotResettleOrChangeRouteOutcome() {
        setup(5,2).with("pass").put("nextStepId","feedback");ObjectNode branch=((ObjectNode)snapshot.path("nodeConfigs").path("101")).putObject("branch");com.fasterxml.jackson.databind.node.ArrayNode steps=branch.putArray("steps");ObjectNode first=steps.addObject().put("id","feedback");ObjectNode option=first.putArray("options").addObject().put("id","a").put("nextStepId","done");option.putArray("effects").addObject().put("var","sys.hp").put("op","INC").put("value",1);steps.addObject().put("id","done").put("terminal",true).put("outcomeCode","DIFFERENT");
        engine.remember(snapshot,payer,"advanced:1:1",decision("PASS"));assertNull(engine.feedback(snapshot,payer,"advanced:1:1"));assertThrows(IllegalStateException.class,()->engine.chooseFeedback(snapshot,rules,payer,"advanced:1:1","a"));
        ObjectNode receipt=engine.settleRemembered(snapshot.path("themeConfig"),rules,payer,recipients,"advanced:1:1",1000);assertEquals(3,payer.getVariables().get("sys.hp"));assertEquals("feedback",engine.feedback(snapshot,payer,"advanced:1:1").path("currentStepId").asText());
        assertTrue(engine.chooseFeedback(snapshot,rules,payer,"advanced:1:1","a").path("ended").asBoolean());assertEquals(4,payer.getVariables().get("sys.hp"));assertThrows(IllegalStateException.class,()->engine.chooseFeedback(snapshot,rules,payer,"advanced:1:1","a"));assertEquals(receipt,payer.getThemeRuntime().path("outcomeAttempts").path("advanced:1:1").path("receipt"));
    }
    @Test void startPreflightChecksOnlyPossiblePassAfterAndNeverMutatesTheWallet() {
        setup(1,9);
        ObjectNode block=(ObjectNode)snapshot.path("nodeConfigs").path("101");
        ObjectNode qa=block.putObject("qa").put("enabled",true).put("mode","PICK");
        ObjectNode correct=qa.putArray("options").addObject().put("id","yes").put("correct",true);
        correct.putObject("after").putArray("costs").addObject().put("statKey","sys.hp").put("amount",1);
        qa.withArray("options").addObject().put("id","no").put("correct",false).putObject("after").putArray("costs").addObject().put("statKey","sys.hp").put("amount",20);
        assertDoesNotThrow(()->engine.assertPassAffordable(block,rules,payer,snapshot.path("themeConfig")));
        assertEquals(1,payer.getVariables().get("sys.hp"));assertFalse(payer.getThemeRuntime().has("outcomeAttempts"));
        payer.getVariables().put("sys.hp",0);
        assertThrows(IllegalStateException.class,()->engine.assertPassAffordable(block,rules,payer,snapshot.path("themeConfig")));
    }
    @Test void failedCostClampsAndReplayCannotChargeAgainOrChangeTheOutcome() {
        setup(1, 3);
        ObjectNode receipt = engine.settle(snapshot, rules, payer, recipients, "attempt:1", decision("FAIL"), 1000);
        assertTrue(receipt.path("settled").asBoolean()); assertEquals(0, payer.getVariables().get("sys.hp"));
        assertEquals(3, receipt.path("costs").path(0).path("requested").asInt());
        assertEquals(1, receipt.path("costs").path(0).path("charged").asInt());
        assertEquals(receipt, engine.settle(snapshot, rules, payer, recipients, "attempt:1", decision("FAIL"), 2000));
        assertThrows(IllegalStateException.class, () -> engine.settle(snapshot, rules, payer, recipients, "attempt:1", decision("PASS"), 2000));
    }

    @Test void passShortfallFreezesResultWithoutCostOrEffectAndCanSettleAfterRestoringBalance() {
        setup(1, 3).with("pass").putArray("effects").addObject().put("var", "sys.hp").put("op", "INC").put("value", 2);
        ObjectNode blocked = engine.settle(snapshot, rules, payer, recipients, "attempt:2", decision("PASS"), 1000);
        assertFalse(blocked.path("settled").asBoolean()); assertEquals("BLOCKED_COST", blocked.path("status").asText());
        assertEquals(1, payer.getVariables().get("sys.hp"));
        assertEquals(blocked,payer.getThemeRuntime().path("lastOutcome"));
        payer.getVariables().put("sys.hp", 4);
        assertTrue(engine.settle(snapshot, rules, payer, recipients, "attempt:2", decision("PASS"), 2000).path("settled").asBoolean());
        assertEquals(3, payer.getVariables().get("sys.hp"));
    }

    @Test void effectIsOncePerRecipientAndOnlyActorPays() {
        setup(5, 2).with("fail").putArray("effects").addObject().put("var", "sys.hp").put("op", "INC").put("value", 1);
        TopicRouteEngine.State other = new TopicRouteEngine.State(); other.getVariables().put("sys.hp", 5); recipients.put(8L, other);
        engine.settle(snapshot, rules, payer, recipients, "attempt:3", decision("FAIL"), 1000);
        assertEquals(4, payer.getVariables().get("sys.hp")); assertEquals(6, other.getVariables().get("sys.hp"));
        engine.settle(snapshot, rules, payer, recipients, "attempt:3", decision("FAIL"), 2000);
        assertEquals(4, payer.getVariables().get("sys.hp")); assertEquals(6, other.getVariables().get("sys.hp"));
    }
    @Test void checkFailureUsesStoredDiceLegacyEffectsAndTagWithoutAutomaticDamage() {
        setup(5,2);ObjectNode check=((ObjectNode)snapshot.path("nodeConfigs").path("101")).putObject("check").put("enabled",true);
        check.putArray("failEffects").addObject().put("var","sys.hp").put("op","INC").put("value",1);check.put("failCostTag","lost");
        payer.getThemeRuntime().with("checkAttempts").with("advanced:1:1").putObject("roll").put("success",false).put("nat","FUMBLE");
        ObjectNode result=decision("FAIL");result.putObject("selection").put("kit","check").put("nat","FUMBLE");
        engine.settle(snapshot,rules,payer,recipients,"advanced:1:1",result,1000);
        assertEquals(4,payer.getVariables().get("sys.hp"));assertTrue(payer.getTags().contains("lost"));
        engine.settle(snapshot,rules,payer,recipients,"advanced:1:1",result,2000);assertEquals(4,payer.getVariables().get("sys.hp"));
        ObjectNode forged=decision("PASS");forged.putObject("selection").put("kit","check");
        assertThrows(IllegalStateException.class,()->engine.remember(snapshot,payer,"advanced:1:2",forged));
    }

    @Test void hiddenCostReadbackDoesNotExposeShortfallOrActualBalance() throws Exception {
        rules.setAttributes((com.fasterxml.jackson.databind.node.ArrayNode)json.readTree("[{\"key\":\"counter.secret\",\"label\":\"secret\",\"kind\":\"COUNTER\",\"min\":0,\"max\":10,\"initial\":0,\"visible\":false}]"));
        ObjectNode receipt=(ObjectNode)json.readTree("{\"costs\":[{\"statKey\":\"counter.secret\",\"requested\":3,\"charged\":1}],\"shortfall\":[{\"statKey\":\"counter.secret\",\"current\":1,\"needed\":3}]}");JourneyInitialState.redactHidden(rules,receipt);assertFalse(receipt.toString().contains("charged"));assertFalse(receipt.toString().contains("current"));
    }

}
