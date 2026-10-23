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
 /** check.skill 绑定属性:只认 counter.* 具名键(与状态键正则同形),其余视为展示文本。 */
 private static final java.util.regex.Pattern SKILL_KEY=java.util.regex.Pattern.compile("^counter\\.[a-z][a-z0-9_]{0,47}$");
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
                String var = text(mod, "var");
                // F2 T03:mods[].var 从服务端局状态取修正值;缺省 / 未初始化按 0,绝不读客户端值。
                item.setVar(var);
                item.setValue(var != null ? stateInt(state, var) : mod.path("value").asInt(0));
                item.setApplied(conditionPasses(mod.get("when"), state));
                mods.add(item);
                if (item.isApplied()) modSum += item.getValue();
            }
        }
        // modCap 只封顶情境修正;角色属性(check.skill 绑定的 counter.*)单独计入,不受它约束。
        int mod = Math.min(modSum, rules.getModCap());
        Map<String, Object> skill = skill(check.path("skill").asText(null), rules, state);
        int skillValue = skill == null ? 0 : ((Integer) skill.get("value")).intValue();
        if (skill != null && skill.containsKey("hidden")) skill.remove("value");
        int total = kept + skillValue + mod;
        // F2 T04:natAuto=false 时自然骰不再必成 / 必败,也不产生 CRIT / FUMBLE(大成功/大失败效果随之不触发)。
        String nat = !rules.isNatAuto() ? null : kept == 20 ? PlayCheckReceipt.NAT_CRIT
                : kept == 1 ? PlayCheckReceipt.NAT_FUMBLE : null;
        boolean success = PlayCheckReceipt.NAT_CRIT.equals(nat)
                || (!PlayCheckReceipt.NAT_FUMBLE.equals(nat) && total >= tier.getDc());

        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setCheckId(check.path("checkId").asText(""));
        receipt.setTier(tierName);
        receipt.setDc(tier.getDc());
        receipt.setMods(mods);
        receipt.setAdvantage(advantage);
        receipt.setDisadvantage(disadvantage);
        receipt.setDice(dice);
        receipt.setKept(kept);
        receipt.setTotal(total);
        receipt.setSuccess(success);
        receipt.setNat(nat);
        receipt.setRerolled(rerolled);
        receipt.setSettled(false);
        Map<String, Object> situational = new LinkedHashMap<String, Object>();
        situational.put("raw", modSum);
        situational.put("cap", rules.getModCap());
        situational.put("applied", mod);
        Map<String, Object> breakdown = new LinkedHashMap<String, Object>();
        breakdown.put("die", kept);
        breakdown.put("skill", skill);
        breakdown.put("situational", situational);
        breakdown.put("total", total);
        breakdown.put("dc", tier.getDc());
        breakdown.put("natAuto", rules.isNatAuto());
        receipt.setBreakdown(breakdown);
        return receipt;
 }
 /**
  * check.skill 是已启用的 counter 属性键时按局状态取值;老配置里的自由文本技能名(如「观察」)
  * 不是键名 → 返回 null,检定口径与以前完全一致。隐藏属性(journeyRules visible=false)在明细里只标 hidden,不给键名、名称与数值。
  */
 private static Map<String,Object> skill(String skill,JourneyRules rules,TopicRouteEngine.State state){
  if(skill==null || !SKILL_KEY.matcher(skill.trim()).matches())return null;
  String key=skill.trim();String label=null;boolean visible=true;
  for(JsonNode row:rules.getAttributes())if(key.equals(row.path("key").asText())){label=row.path("label").asText(null);visible=row.path("visible").asBoolean(true);}
  Map<String,Object> view=new LinkedHashMap<String,Object>();
  if(visible){view.put("statKey",key);view.put("label",label==null?key.substring(key.indexOf('.')+1):label);}else view.put("hidden",true);
  view.put("value",stateInt(state,key));
  return view;
 }
 private static int stateInt(TopicRouteEngine.State state,String key){Number value=state.getVariables().get(key);return value==null || !Double.isFinite(value.doubleValue())?0:value.intValue();}
 private static int die(IntSupplier random){int value=random.getAsInt();if(value<1 || value>20)throw new IllegalStateException("骰点值非法");return value;}
 private static JourneyRules.Tier tierOf(JourneyRules rules,String tier){return "easy".equals(tier)?rules.getTiers().getEasy():"medium".equals(tier)?rules.getTiers().getMedium():"hard".equals(tier)?rules.getTiers().getHard():null;}
 private static String text(JsonNode node,String field){return node.hasNonNull(field)?node.path(field).asText():null;}
 private static boolean anyConditionPasses(JsonNode conditions,TopicRouteEngine.State state){if(conditions==null || !conditions.isArray() || conditions.isEmpty())return false;for(JsonNode c:conditions)if(conditionPasses(c,state))return true;return false;}
 private static boolean conditionPasses(JsonNode c,TopicRouteEngine.State state){if(c==null || c.isNull())return true;if(c.isArray())return anyConditionPasses(c,state);ArrayNode one=JSON.createArrayNode().add(c);return new TopicRouteEngine().conditionsPass(one,state);}
}
