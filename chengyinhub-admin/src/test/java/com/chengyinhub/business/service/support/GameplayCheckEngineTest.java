package com.chengyinhub.business.service.support;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class GameplayCheckEngineTest {
 @Test void actionsReuseLuckAndAttemptRulesWithoutWritingState() throws Exception {
  JourneyRules rules=new JourneyRules();rules.setCheckEnabled(true);rules.getLuck().setEnabled(true);
  TopicRouteEngine.State state=new TopicRouteEngine.State();state.getVariables().put("sys.luck",1);
  ObjectNode check=(ObjectNode)new ObjectMapper().readTree("{\"enabled\":true,\"tier\":\"easy\"}");
  assertEquals("ROLL_CHECK",GameplayCheckEngine.actions(check,rules,state,"advanced:1:1").path("allowedActions").path(0).asText());
  GameplayCheckEngine.rollAttempt(check,rules,state,"advanced:1:1",false,()->12);
  String before=new ObjectMapper().writeValueAsString(state);
  assertTrue(GameplayCheckEngine.actions(check,rules,state,"advanced:1:1").path("allowedActions").toString().contains("REROLL_CHECK"));
  assertEquals(before,new ObjectMapper().writeValueAsString(state));
  state.getVariables().put("sys.luck",0);
  assertEquals("幸运值不足",GameplayCheckEngine.actions(check,rules,state,"advanced:1:1").path("disabledReason").asText());
  state.getVariables().put("sys.luck",1);GameplayCheckEngine.rollAttempt(check,rules,state,"advanced:1:1",true,()->13);
  assertEquals("重掷次数已用完",GameplayCheckEngine.actions(check,rules,state,"advanced:1:1").path("disabledReason").asText());
  state.getThemeRuntime().with("outcomeAttempts").putObject("advanced:1:1");
  assertTrue(GameplayCheckEngine.actions(check,rules,state,"advanced:1:1").path("allowedActions").isEmpty());
 }
 @Test void replayKeepsDiceAndRerollChargesLuckExactlyOnce() throws Exception {
  JourneyRules rules=new JourneyRules();rules.setCheckEnabled(true);rules.getLuck().setEnabled(true);
  TopicRouteEngine.State state=new TopicRouteEngine.State();state.getVariables().put("sys.luck",1);
  ObjectNode check=(ObjectNode)new ObjectMapper().readTree("{\"enabled\":true,\"checkId\":\"roll\",\"tier\":\"easy\"}");
  AtomicInteger draws=new AtomicInteger();java.util.function.IntSupplier rng=()->draws.incrementAndGet()==1?1:20;
  PlayCheckReceipt first=GameplayCheckEngine.rollAttempt(check,rules,state,"advanced:1:1",false,rng);
  assertFalse(first.getSuccess());assertEquals(1,state.getVariables().get("sys.luck"));
  assertEquals(first.getDice(),GameplayCheckEngine.rollAttempt(check,rules,state,"advanced:1:1",false,rng).getDice());
  PlayCheckReceipt second=GameplayCheckEngine.rollAttempt(check,rules,state,"advanced:1:1",true,rng);
  assertTrue(second.getSuccess());assertEquals(0,state.getVariables().get("sys.luck"));
  assertEquals(second.getDice(),GameplayCheckEngine.rollAttempt(check,rules,state,"advanced:1:1",true,rng).getDice());assertEquals(2,draws.get());
  assertThrows(IllegalStateException.class,()->GameplayCheckEngine.rollAttempt(check,rules,state,"advanced:1:2",true,rng));
 }
}
