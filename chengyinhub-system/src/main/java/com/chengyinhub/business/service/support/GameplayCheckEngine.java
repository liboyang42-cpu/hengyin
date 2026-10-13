package com.chengyinhub.business.service.support;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.IntSupplier;

/** 冻结的服务端骰点与幸运支付；旧检定只共用纯计算。 */
public final class GameplayCheckEngine {
 private static final ObjectMapper JSON=new ObjectMapper();
 private GameplayCheckEngine() { }
 public static PlayCheckReceipt rollAttempt(JsonNode check,JourneyRules rules,TopicRouteEngine.State state,String attemptId,boolean reroll,IntSupplier random) {
  if(!rules.isCheckEnabled() || !check.path("enabled").asBoolean() || attemptId==null || !attemptId.matches("advanced:[0-9]+:[0-9]+"))throw new IllegalStateException("本局未启用可信检定尝试");
  ObjectNode attempt=state.getThemeRuntime().with("checkAttempts").with(attemptId);String key=reroll?"reroll":"roll";
  if(attempt.has(key))return JSON.convertValue(attempt.path(key),PlayCheckReceipt.class);
  if(state.getThemeRuntime().path("outcomeAttempts").has(attemptId))throw new IllegalStateException("本次检定已提交");
  if(reroll)requireReroll(rules,state,attempt);
  if(!attempt.has("roll") && state.getThemeRuntime().path("checkAttempts").size()>4096)throw new IllegalStateException("本局检定容量已满");
  PlayCheckReceipt receipt=roll(check,rules,state,random,reroll);
  if(reroll)state.getVariables().put("sys.luck",JourneyInitialState.numericValue(state,"sys.luck")-1);
  receipt.setLuck(JourneyInitialState.numericValue(state,"sys.luck"));attempt.set(key,JSON.valueToTree(receipt));
  return receipt;
 }
 public static ObjectNode actions(JsonNode check,JourneyRules rules,TopicRouteEngine.State state,String attemptId) {
  ObjectNode view=JSON.createObjectNode().put("attemptId",attemptId).put("disabledReason","");ArrayNode allowed=view.putArray("allowedActions");
  JsonNode attempt=state.getThemeRuntime().path("checkAttempts").path(attemptId);
  if(!rules.isCheckEnabled() || !check.path("enabled").asBoolean())return view.put("disabledReason","本局未启用可信检定尝试");
  if(state.getThemeRuntime().path("outcomeAttempts").has(attemptId))return view.put("disabledReason","本次检定已提交");
  if(!attempt.has("roll")){allowed.add("ROLL_CHECK");return view;}
  allowed.add("SUBMIT_CHECK");
  if(attempt.has("reroll"))return view.put("disabledReason","重掷次数已用完");
  try{requireReroll(rules,state,attempt);allowed.add("REROLL_CHECK");}catch(IllegalStateException refused){view.put("disabledReason",refused.getMessage());}
  return view;
 }
 private static void requireReroll(JourneyRules rules,TopicRouteEngine.State state,JsonNode attempt) {
  if(!attempt.has("roll"))throw new IllegalStateException("请先掷骰");
  if(!rules.getLuck().isEnabled() || JourneyInitialState.numericValue(state,"sys.luck")<=0)throw new IllegalStateException("幸运值不足");
 }
 public static PlayCheckReceipt roll(JsonNode check,JourneyRules rules,TopicRouteEngine.State state,IntSupplier random,boolean rerolled) {
        String tierName = check.path("tier").asText("");
        JourneyRules.Tier tier = tierOf(rules, tierName);
        if (tier == null) throw new IllegalStateException("检定难度档位无效");
        boolean advantage = anyConditionPasses(check.get("advantageIf"), state);
        boolean disadvantage = anyConditionPasses(check.get("disadvantageIf"), state);
        if (advantage && disadvantage) { advantage = false; disadvantage = false; }

        List<Integer> dice = new ArrayList<Integer>();
        int kept;
        if (advantage || disadvantage) {
            int first = die(random);
            int second = die(random);
            dice.add(first); dice.add(second);
            kept = advantage ? Math.max(first, second) : Math.min(first, second);
        } else {
            kept = die(random);
            dice.add(kept);
        }

        int modSum = 0;
        List<PlayCheckReceipt.Mod> mods = new ArrayList<PlayCheckReceipt.Mod>();
        JsonNode configuredMods = check.get("mods");
        if (configuredMods != null && configuredMods.isArray()) {
            for (JsonNode mod : configuredMods) {
                PlayCheckReceipt.Mod item = new PlayCheckReceipt.Mod();
                item.setLabel(text(mod, "label"));
                item.setValue(mod.path("value").asInt(0));
                item.setApplied(conditionPasses(mod.get("when"), state));
                mods.add(item);
                if (item.isApplied()) modSum += item.getValue();
            }
        }
        int mod = Math.min(modSum, rules.getModCap());
        String nat = kept == 20 ? PlayCheckReceipt.NAT_CRIT
                : kept == 1 ? PlayCheckReceipt.NAT_FUMBLE : null;
        boolean success = PlayCheckReceipt.NAT_CRIT.equals(nat)
                || (!PlayCheckReceipt.NAT_FUMBLE.equals(nat) && kept + mod >= tier.getDc());

        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setCheckId(check.path("checkId").asText(""));
        receipt.setTier(tierName);
        receipt.setDc(tier.getDc());
        receipt.setMods(mods);
        receipt.setAdvantage(advantage);
        receipt.setDisadvantage(disadvantage);
        receipt.setDice(dice);
        receipt.setKept(kept);
        receipt.setTotal(kept + mod);
        receipt.setSuccess(success);
        receipt.setNat(nat);
        receipt.setRerolled(rerolled);
        receipt.setSettled(false);
        return receipt;
 }
 private static int die(IntSupplier random){int value=random.getAsInt();if(value<1 || value>20)throw new IllegalStateException("骰点值非法");return value;}
 private static JourneyRules.Tier tierOf(JourneyRules rules,String tier){return "easy".equals(tier)?rules.getTiers().getEasy():"medium".equals(tier)?rules.getTiers().getMedium():"hard".equals(tier)?rules.getTiers().getHard():null;}
 private static String text(JsonNode node,String field){return node.hasNonNull(field)?node.path(field).asText():null;}
 private static boolean anyConditionPasses(JsonNode conditions,TopicRouteEngine.State state){if(conditions==null || !conditions.isArray() || conditions.isEmpty())return false;for(JsonNode c:conditions)if(conditionPasses(c,state))return true;return false;}
 private static boolean conditionPasses(JsonNode c,TopicRouteEngine.State state){if(c==null || c.isNull())return true;if(c.isArray())return anyConditionPasses(c,state);ArrayNode one=JSON.createArrayNode().add(c);return new TopicRouteEngine().conditionsPass(one,state);}
}
