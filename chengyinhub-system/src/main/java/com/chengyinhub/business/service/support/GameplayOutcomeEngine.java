package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 只接受已验身份/实例的服务端判据；接收方由既有任务/设施范围枚举。 */
public final class GameplayOutcomeEngine {
    private final ThemeRuntimeEngine inventory = new ThemeRuntimeEngine();

    public void assertPassAffordable(JsonNode block,JourneyRules rules,TopicRouteEngine.State payer,JsonNode theme) {
        JsonNode root=block.path("outcomeRules").path("pass");
        GameplayOutcomeRules.normalize(block.path("outcomeRules"),null);
        List<JsonNode> candidates=new ArrayList<>();
        JsonNode choices=null;String identity="id",answer=null;boolean onlyCorrect=false;
        if(block.path("qa").path("enabled").asBoolean() && "PICK".equals(block.path("qa").path("mode").asText()) && !block.path("qa").path("multi").asBoolean()){choices=block.path("qa").path("options");onlyCorrect=true;}
        else if(block.path("pricePair").path("enabled").asBoolean()){choices=block.path("pricePair").path("items");onlyCorrect=true;}
        else if(block.path("blindTaste").path("enabled").asBoolean()){choices=block.path("blindTaste").path("options");identity="key";answer=block.path("blindTaste").path("answerKey").asText();}
        else if(block.path("predict").path("enabled").asBoolean())choices=block.path("predict").path("options");
        else if(block.path("random").path("enabled").asBoolean())choices=block.path("random").path("items");
        else if(block.path("coinFlip").path("enabled").asBoolean()){candidates.add(block.path("coinFlip").path("heads").has("after")?block.path("coinFlip").path("heads").path("after"):root);candidates.add(block.path("coinFlip").path("tails").has("after")?block.path("coinFlip").path("tails").path("after"):root);}
        else if(block.path("diceRoll").path("enabled").asBoolean() && !"d20".equals(block.path("diceRoll").path("mode").asText()))choices=block.path("diceRoll").path("faces");
        if(choices!=null)for(JsonNode row:choices)if((!onlyCorrect || row.path("correct").asBoolean()) && (answer==null || answer.equals(row.path(identity).asText())))candidates.add(row.has("after")?row.path("after"):root);
        if(candidates.isEmpty())candidates.add(root);
        for(JsonNode after:candidates)for(JsonNode fee:after.path("costs")) {
            String key=fee.path("statKey").asText();int minimum=key.startsWith("counter.")?JourneyInitialState.numericAttribute(rules,key).path("min").asInt():0;
            if((long)JourneyInitialState.numericValue(payer,key)-minimum<inventory.reduceStatCost(theme,payer,key,fee.path("amount").asInt()))throw new IllegalStateException("PASS cost unavailable: "+key);
        }
    }

    public ObjectNode remember(JsonNode frozen, TopicRouteEngine.State payer, String attemptId, JsonNode decision) {
        if (attemptId == null || !attemptId.matches("[A-Za-z0-9:_-]{1,128}") || !decision.path("nodeId").isIntegralNumber())
            throw new IllegalArgumentException("可信尝试身份不合法");
        ObjectNode attempts = payer.getThemeRuntime().with("outcomeAttempts");
        JsonNode previous = attempts.path(attemptId);
        if (previous.isObject()) {
            if (!previous.path("decision").equals(decision)) throw new IllegalStateException("本次尝试已经判定，不能改变结果");
            return (ObjectNode) previous;
        }
        if (attempts.size() >= 4096) throw new IllegalStateException("本局尝试记录容量已满");
        JsonNode block = frozen.path("nodeConfigs").path(decision.path("nodeId").asText());
        ObjectNode after = GameplayOutcomeRules.select(block, decision).deepCopy();
        String failTag=null;
        if(block.path("check").path("enabled").asBoolean()) {
            JsonNode dice=payer.getThemeRuntime().path("checkAttempts").path(attemptId);dice=dice.has("reroll")?dice.path("reroll"):dice.path("roll");
            if(!"TIMEOUT".equals(decision.path("reason").asText()) && (!dice.has("success") || dice.path("success").asBoolean()!= "PASS".equals(decision.path("outcome").asText())))throw new IllegalStateException("检定结果缺少服务端冻结骰点");
            boolean success="PASS".equals(decision.path("outcome").asText());JsonNode check=block.path("check");
            for(String field:Arrays.asList(success?"successEffects":"failEffects",success && "CRIT".equals(dice.path("nat").asText())?"critEffects":!success && "FUMBLE".equals(dice.path("nat").asText())?"fumbleEffects":""))for(JsonNode effect:check.path(field))after.withArray("effects").add(effect.deepCopy());
            if(!success && check.hasNonNull("failCostTag"))failTag=check.path("failCostTag").asText();
        }
        ObjectNode attempt = attempts.putObject(attemptId);
        attempt.set("decision", decision.deepCopy()); attempt.set("after", after);if(failTag!=null && !failTag.isEmpty())attempt.put("legacyFailCostTag",failTag);
        return attempt;
    }

    public ObjectNode feedback(JsonNode frozen,TopicRouteEngine.State state,String attemptId) {
        JsonNode attempt=state.getThemeRuntime().path("outcomeAttempts").path(attemptId);
        if(!attempt.path("receipt").path("settled").asBoolean() || !attempt.path("receipt").has("nextStepId"))return null;
        ObjectNode branch=attempt.path("feedback").isObject()?((ObjectNode)attempt.path("feedback")).deepCopy():JsonNodeFactory.instance.objectNode().put("currentStepId",attempt.path("receipt").path("nextStepId").asText());
        JsonNode current=feedbackStep(frozen,attempt,branch.path("currentStepId").asText());
        branch.put("ended",current.path("terminal").asBoolean());return branch;
    }
    public ObjectNode chooseFeedback(JsonNode frozen,JourneyRules rules,TopicRouteEngine.State state,String attemptId,String optionId) {
        ObjectNode branch=feedback(frozen,state,attemptId);if(branch==null)throw new IllegalStateException("结果尚未结算，不能进入下一段");
        if(branch.path("ended").asBoolean())throw new IllegalStateException("反馈分段已到终点");
        ObjectNode attempt=state.getThemeRuntime().with("outcomeAttempts").with(attemptId);String current=branch.path("currentStepId").asText();JsonNode option=null;
        for(JsonNode row:feedbackStep(frozen,attempt,current).path("options"))if(row.path("id").asText().equals(optionId))option=row;
        if(option==null)throw new IllegalArgumentException("当前反馈段没有这个选项");
        if(branch.path("actions").asInt()>=4096)throw new IllegalStateException("反馈操作容量已满");
        String source=current+":"+optionId;ObjectNode sources=branch.with("sources");
        if(!sources.has(source)){TemplateEffects.apply(option.path("effects"),rules,state);sources.put(source,true);}
        branch.put("currentStepId",option.path("nextStepId").asText()).put("actions",branch.path("actions").asInt()+1);attempt.set("feedback",branch);
        return feedback(frozen,state,attemptId);
    }
    private JsonNode feedbackStep(JsonNode frozen,JsonNode attempt,String stepId) {
        for(JsonNode step:frozen.path("nodeConfigs").path(attempt.path("decision").path("nodeId").asText()).path("branch").path("steps"))if(step.path("id").asText().equals(stepId))return step;
        throw new IllegalStateException("结果反馈段缺少冻结步骤");
    }

    public ObjectNode settle(JsonNode frozen, JourneyRules rules, TopicRouteEngine.State payer,
                             Map<Long, TopicRouteEngine.State> recipients, String attemptId, JsonNode decision, long now) {
        remember(frozen, payer, attemptId, decision);
        return settleRemembered(frozen.path("themeConfig"), rules, payer, recipients, attemptId, now);
    }

    public ObjectNode settleRemembered(JsonNode theme, JourneyRules rules, TopicRouteEngine.State payer,
                                      Map<Long, TopicRouteEngine.State> recipients, String attemptId, long now) {
        return settleRemembered(theme,rules,payer,recipients,attemptId,now,true);
    }
    public ObjectNode settleRemembered(JsonNode theme, JourneyRules rules, TopicRouteEngine.State payer,
                                      Map<Long, TopicRouteEngine.State> recipients, String attemptId, long now, boolean allowNoEffect) {
        if (recipients == null || recipients.isEmpty() || !recipients.containsValue(payer)) throw new IllegalArgumentException("Payer must be an eligible recipient");
        JsonNode stored = payer.getThemeRuntime().path("outcomeAttempts").path(attemptId);
        if (!stored.isObject()) throw new IllegalStateException("Server outcome missing");
        ObjectNode attempt = (ObjectNode) stored; JsonNode decision = attempt.path("decision");
        if (attempt.path("receipt").path("settled").asBoolean()) return (ObjectNode) attempt.get("receipt").deepCopy();
        JsonNode after = attempt.path("after");
        boolean pass = "PASS".equals(decision.path("outcome").asText());
        Map<Long, TopicRouteEngine.State> copies = new TreeMap<>();
        recipients.forEach((id, state) -> copies.put(id, state.copy()));
        Long payerId = null; for (Map.Entry<Long, TopicRouteEngine.State> recipient : recipients.entrySet()) if (recipient.getValue() == payer) payerId = recipient.getKey();
        TopicRouteEngine.State paying = copies.get(payerId);
        ObjectNode receipt = JsonNodeFactory.instance.objectNode().put("attemptId", attemptId).put("outcome", decision.path("outcome").asText()).put("settled", false);
        com.fasterxml.jackson.databind.node.ArrayNode costs = receipt.putArray("costs");
        boolean noEffect = allowNoEffect && pass && TemplateEffects.pureRecovery(after.path("effects")) && !theme.path("gameplayRuntime").path("survival").path("consumeOnNoEffect").asBoolean();
        if (noEffect) for (TopicRouteEngine.State recipient : copies.values()) {
            TopicRouteEngine.State probe = recipient.copy(); TemplateEffects.apply(after.path("effects"), rules, probe);
            if (!probe.getVariables().equals(recipient.getVariables())) { noEffect = false; break; }
        }
        if (!noEffect) {
            // PASS 先完整检查再扣；FAIL 按下限截断，不能让失败因付不起而消失。
            for (JsonNode row : after.path("costs")) {
                String key = row.path("statKey").asText(); int requested = row.path("amount").asInt();
                int effective = inventory.reduceStatCost(theme, paying, key, requested);
                int minimum = key.startsWith("counter.") ? JourneyInitialState.numericAttribute(rules, key).path("min").asInt() : 0;
                if (pass && (long) JourneyInitialState.numericValue(paying, key) - minimum < effective) {
                    receipt.put("status", "BLOCKED_COST");
                    receipt.putArray("shortfall").addObject().put("statKey", key).put("needed", effective).put("current", JourneyInitialState.numericValue(paying, key));
                    attempt.set("receipt",receipt.deepCopy()); payer.getThemeRuntime().set("lastOutcome",receipt.deepCopy());
                    return receipt;
                }
            }
            for (JsonNode row : after.path("costs")) {
                String key = row.path("statKey").asText(); int requested = row.path("amount").asInt();
                int actual = -inventory.changeStat(paying, rules, key, -inventory.reduceStatCost(theme, paying, key, requested));
                costs.addObject().put("statKey", key).put("requested", requested).put("charged", actual);
            }
            for (Map.Entry<Long, TopicRouteEngine.State> target : copies.entrySet()) {
                ObjectNode sources = target.getValue().getThemeRuntime().with("outcomeSources");
                if (sources.has(attemptId)) continue;
                TemplateEffects.apply(after.path("effects"), rules, target.getValue()); sources.put(attemptId, true);
            }
        }
        if(!pass && attempt.has("legacyFailCostTag"))paying.getTags().add(attempt.path("legacyFailCostTag").asText());
        receipt.put("settled", true).put("status", "SETTLED").put("consumed", !noEffect).put("resolvedAt", now).put("reply", after.path("reply").asText());
        if (after.path("images").isArray() && after.path("images").size() > 0) receipt.set("images", after.get("images").deepCopy());
        if (after.has("nextStepId")) receipt.set("nextStepId", after.get("nextStepId").deepCopy());
        for (Map.Entry<Long, TopicRouteEngine.State> target : copies.entrySet()) {
            TopicRouteEngine.State actual = recipients.get(target.getKey()), next = target.getValue();
            actual.setVariables(next.getVariables()); actual.setTags(next.getTags()); actual.setThemeRuntime(next.getThemeRuntime());
        }
        payer.getThemeRuntime().with("outcomeAttempts").with(attemptId).set("receipt", receipt.deepCopy());
        payer.getThemeRuntime().set("lastOutcome", receipt.deepCopy());
        return receipt;
    }
}
