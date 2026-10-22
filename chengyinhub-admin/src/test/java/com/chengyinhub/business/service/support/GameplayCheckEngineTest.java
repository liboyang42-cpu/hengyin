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
 /** F2 T03:check.skill 绑定 counter 属性,值进 total 且不受 modCap;mods[].var 读局状态,情境修正之和才封顶。 */
 @Test void skillValueIsUncappedAndOnlySituationalModsAreCapped() throws Exception {
  JourneyRules rules=JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true,\"stateEnabled\":true,\"modCap\":2,"
    +"\"attributes\":[{\"key\":\"counter.str\",\"label\":\"力量\",\"initial\":0,\"min\":0,\"max\":20},{\"key\":\"counter.luckbuff\",\"label\":\"运势\",\"initial\":0}]}");
  TopicRouteEngine.State state=new TopicRouteEngine.State();state.getVariables().put("counter.str",5);state.getVariables().put("counter.luckbuff",4);
  ObjectNode check=(ObjectNode)new ObjectMapper().readTree("{\"enabled\":true,\"checkId\":\"lift\",\"tier\":\"medium\",\"skill\":\"counter.str\","
    +"\"mods\":[{\"label\":\"顺风\",\"var\":\"counter.luckbuff\",\"when\":null},{\"label\":\"工具\",\"value\":3,\"when\":null}]}");
  PlayCheckReceipt receipt=GameplayCheckEngine.roll(check,rules,state,()->7,false);
  assertEquals(14,receipt.getTotal(),"7 + 力量5 + min(4+3, cap2)");
  assertTrue(receipt.getSuccess(),"medium dc13");
  assertEquals(4,receipt.getMods().get(0).getValue());assertEquals("counter.luckbuff",receipt.getMods().get(0).getVar());
  assertNull(receipt.getMods().get(1).getVar());
  com.fasterxml.jackson.databind.JsonNode breakdown=new ObjectMapper().valueToTree(receipt.getBreakdown());
  assertEquals(7,breakdown.path("die").asInt());
  assertEquals("counter.str",breakdown.path("skill").path("statKey").asText());
  assertEquals("力量",breakdown.path("skill").path("label").asText());
  assertEquals(5,breakdown.path("skill").path("value").asInt());
  assertEquals(7,breakdown.path("situational").path("raw").asInt());
  assertEquals(2,breakdown.path("situational").path("applied").asInt());
  assertEquals(2,breakdown.path("situational").path("cap").asInt());
  assertEquals(13,breakdown.path("dc").asInt());
  assertEquals(14,breakdown.path("total").asInt());
  state.getVariables().put("counter.str",20);
  assertEquals(29,GameplayCheckEngine.roll(check,rules,state,()->7,false).getTotal(),"属性值再高也不被 modCap 截断");
 }
 /** 老配置:skill 是自由文本、mods 是静态 value —— total 口径一字不变。 */
 @Test void legacyFreeTextSkillKeepsTheOldTotal() throws Exception {
  JourneyRules rules=new JourneyRules();rules.setCheckEnabled(true);
  TopicRouteEngine.State state=new TopicRouteEngine.State();
  ObjectNode check=(ObjectNode)new ObjectMapper().readTree("{\"enabled\":true,\"checkId\":\"look\",\"tier\":\"easy\",\"skill\":\"观察\",\"mods\":[{\"value\":9,\"when\":null}]}");
  PlayCheckReceipt receipt=GameplayCheckEngine.roll(check,rules,state,()->3,false);
  assertEquals(9,receipt.getTotal(),"3 + min(9, 默认 cap6)");
  assertTrue(receipt.getBreakdown().get("skill")==null);
  String json=new ObjectMapper().writeValueAsString(receipt.getMods().get(0));
  assertFalse(json.contains("\"var\""),"静态修正不在回执里多出 var 字段:"+json);
 }
 /** 隐藏属性:明细只标 hidden,不给键名、名称与数值。 */
 @Test void hiddenSkillIsRedactedFromTheBreakdown() throws Exception {
  JourneyRules rules=JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true,\"stateEnabled\":true,\"attributes\":[{\"key\":\"counter.fate\",\"label\":\"天命\",\"initial\":0,\"visible\":false}]}");
  TopicRouteEngine.State state=new TopicRouteEngine.State();state.getVariables().put("counter.fate",4);
  ObjectNode check=(ObjectNode)new ObjectMapper().readTree("{\"enabled\":true,\"checkId\":\"fate\",\"tier\":\"easy\",\"skill\":\"counter.fate\"}");
  PlayCheckReceipt receipt=GameplayCheckEngine.roll(check,rules,state,()->6,false);
  assertEquals(10,receipt.getTotal());
  String json=new ObjectMapper().writeValueAsString(receipt.getBreakdown());
  assertTrue(json.contains("\"hidden\":true"),json);assertFalse(json.contains("counter.fate"),json);assertFalse(json.contains("天命"),json);
 }
 /** F2 T04:natAuto 缺省 = 老主题口径(自然 20 必成 / 1 必败);显式 false 关闭,且不产生 CRIT / FUMBLE。 */
 @Test void natAutoDefaultsToLegacyAndExplicitFalseTurnsItOff() throws Exception {
  ObjectNode check=(ObjectNode)new ObjectMapper().readTree("{\"enabled\":true,\"checkId\":\"leap\",\"tier\":\"hard\",\"mods\":[{\"value\":-5,\"when\":null}]}");
  ObjectNode easy=(ObjectNode)new ObjectMapper().readTree("{\"enabled\":true,\"checkId\":\"step\",\"tier\":\"easy\",\"mods\":[{\"value\":9,\"when\":null}]}");
  TopicRouteEngine.State state=new TopicRouteEngine.State();
  JourneyRules legacy=JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true}");
  assertTrue(legacy.isNatAuto());
  PlayCheckReceipt crit=GameplayCheckEngine.roll(check,legacy,state,()->20,false);
  assertTrue(crit.getSuccess());assertEquals(PlayCheckReceipt.NAT_CRIT,crit.getNat());
  PlayCheckReceipt fumble=GameplayCheckEngine.roll(easy,legacy,state,()->1,false);
  assertFalse(fumble.getSuccess());assertEquals(PlayCheckReceipt.NAT_FUMBLE,fumble.getNat());
  JourneyRules off=JourneyRules.parse("{\"schemaVersion\":1,\"checkEnabled\":true,\"modCap\":10,\"natAuto\":false}");
  PlayCheckReceipt plain20=GameplayCheckEngine.roll(check,off,state,()->20,false);
  assertFalse(plain20.getSuccess(),"20-5=15 < hard16");assertNull(plain20.getNat());
  PlayCheckReceipt plain1=GameplayCheckEngine.roll(easy,off,state,()->1,false);
  assertTrue(plain1.getSuccess(),"自然 1 不再必败:1+9 >= easy10");
 }
}
