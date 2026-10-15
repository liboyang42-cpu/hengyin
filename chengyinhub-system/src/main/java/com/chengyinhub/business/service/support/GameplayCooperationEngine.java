package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.TopicRouteEngine.State;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 多队协作只改原State；调用方须在同一场次事务内提交所有变化与回执。 */
public final class GameplayCooperationEngine {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
            "TRADE_OPEN", "TRADE_CONFIRM", "TRADE_CANCEL", "FACILITY_USE", "PLAN_PROPOSE", "PLAN_INTENT",
            "PLAN_WITHDRAW", "PLAN_CHANGE", "PLAN_CONFIRM", "PLAN_EXECUTE", "VOTE_OPEN", "VOTE_CAST",
            "VOTE_NEXT", "ACCEPT_SET", "CONSENSUS_CAST", "PLAN_REJECT_ALL"));
    private final ThemeRuntimeEngine inventory = new ThemeRuntimeEngine();

    public boolean supports(String action) { return ACTIONS.contains(action); }

    public String fallbackDisabledReason(JsonNode theme, State world, Map<Long, State> teams, Map<Long, Long> leaders) {
        JsonNode c = world.getThemeRuntime().path("cooperation");
        if (c.has("result")) return "SESSION_ENDED";
        boolean enabled = false;
        for (JsonNode source : theme.path("gameplayRuntime").path("templateTools").path("nodes")) if (source.path("decidesEnding").asBoolean()) enabled = true;
        int fallbacks = 0; boolean terminal = false;
        for (JsonNode ending : endingCatalog(theme)) if (ending.path("fallback").asBoolean()) fallbacks++;
        for (JsonNode plan : theme.path("gameplayRuntime").path("plans")) if (plan.path("terminal").asBoolean(true)) terminal = true;
        if (!"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(theme)) || !enabled || !terminal || fallbacks != 1) return "FALLBACK_NOT_CONFIGURED";
        Map<Long, State> effective = new LinkedHashMap<>();
        teams.forEach((id, state) -> { if (isEffectiveTarget(theme, state)) effective.put(id, state); });
        if (effective.isEmpty()) return "NO_EFFECTIVE_TEAMS";
        if ("SELECTED".equals(c.path("stage").asText()) || "CONSENSUS".equals(c.path("stage").asText())) return "PLAN_AVAILABLE";
        ObjectNode scope = rejectionScope(theme, c);
        boolean rejected = true;
        for (Long id : effective.keySet()) if (!currentSignature(c.path("rejections"), leaders, id)
                || !scope.equals(c.path("rejections").path(String.valueOf(id)).path("scope"))) rejected = false;
        if (rejected) return null;
        String field = "MAJORITY_ACCEPTANCE".equals(theme.path("gameplayRuntime").path("votePolicy").asText()) ? "acceptances" : "votes";
        if (!"TIED".equals(c.path("stage").asText())) return "BALLOT_NOT_FINISHED";
        for (Long id : effective.keySet()) if (!currentSignature(c.path(field), leaders, id)) return "BALLOT_NOT_FINISHED";
        try { requireFrozenBalances(c, effective); } catch (IllegalStateException stale) { return "BALLOT_CHANGED"; }
        int threshold = "UNANIMOUS".equals(theme.path("gameplayRuntime").path("votePolicy").asText()) || effective.size() <= 2 ? effective.size() : effective.size() / 2 + 1;
        for (JsonNode candidate : c.path("candidates")) {
            String key = candidate.asText(); int count = 0;
            for (Long id : effective.keySet()) {
                JsonNode vote = c.path(field).path(String.valueOf(id));
                if ("acceptances".equals(field) ? contains(vote.path("choices"), key) : key.equals(vote.path("choice").asText())) count++;
            }
            if (count >= threshold && funded(theme, c.path("proposals").path(key), world, effective, leaders)) return "PLAN_AVAILABLE";
        }
        return null;
    }

    private ObjectNode rejectionScope(JsonNode theme, JsonNode c) {
        ObjectNode scope = JSON.createObjectNode().put("round", c.path("round").asInt());
        scope.set("plans", theme.path("gameplayRuntime").path("plans").deepCopy());
        scope.set("proposals", c.path("proposals").deepCopy());
        return scope;
    }

    public ObjectNode closeFallback(JsonNode theme, State world, Map<Long, State> teams, Map<Long, Long> leaders,
                                    Long operatorMemberId, String reason, String eventId) {
        return closeFallback(theme, world, teams, leaders, operatorMemberId, reason, eventId, null, 0L);
    }
    /** rules 非空时，默认收场为剧情/最终章结局的，各有效队伍同时写入同一结局（与方案执行一致）。 */
    public ObjectNode closeFallback(JsonNode theme, State world, Map<Long, State> teams, Map<Long, Long> leaders,
                                    Long operatorMemberId, String reason, String eventId, JourneyRules rules, long now) {
        if (operatorMemberId == null || operatorMemberId <= 0 || reason == null || reason.trim().isEmpty() || reason.length() > 500
                || eventId == null || eventId.isEmpty() || eventId.length() > 120) throw new IllegalArgumentException("兜底收尾需要原因和真实操作人");
        ObjectNode request = JSON.createObjectNode().put("memberId", operatorMemberId).put("reason", reason);
        JsonNode old = world.getThemeRuntime().path("cooperation").path("receipts").path(eventId);
        if (old.isObject()) {
            if (!request.equals(old.path("request"))) throw new IllegalStateException("同一收尾事件不能更改内容或操作人");
            return publicResult(old.path("result"));
        }
        String blocked = fallbackDisabledReason(theme, world, teams, leaders);
        if (blocked != null) throw new IllegalStateException(blocked);
        if (world.getThemeRuntime().path("cooperation").path("receipts").size() >= 4096) throw new IllegalStateException("本场事件容量已满，请导出并复核");
        State next = world.copy(); ObjectNode c = next.getThemeRuntime().with("cooperation");
        ObjectNode result = JSON.createObjectNode().put("terminal", true).put("fallback", true).put("reason", reason)
                .put("operatorMemberId", operatorMemberId).put("eventId", eventId).put("round", c.path("round").asInt());
        JsonNode storyFallback = null;
        for (JsonNode ending : endingCatalog(theme)) if (ending.path("fallback").asBoolean()) {
            result.put("endingKey", ending.path("id").asText(ending.path("code").asText())).put("title", ending.path("title").asText()).put("success", ending.path("success").asBoolean());
            if(ending.has("summary"))result.put("summary",ending.path("summary").asText());
            if(ending.has("code")){result.put("storyEndingId",ending.path("code").asText()).put("endingChapterId",ending.path("chapterId").asLong());storyFallback=ending;}
        }
        if (rules != null && storyFallback != null) for (State state : teams.values()) if (isEffectiveTarget(theme, state)) inventory.terminalStory(theme, state, rules, storyFallback, now);
        c.remove("locks");
        for (JsonNode trade : c.path("trades")) if (!"COMPLETED".equals(trade.path("status").asText())) ((ObjectNode) trade).put("status", "CANCELLED").remove("signatures");
        c.set("result", result.deepCopy()); c.put("stage", "EXECUTED");
        ObjectNode receipt = c.with("receipts").putObject(eventId); receipt.set("request", request); receipt.set("result", result.deepCopy());
        c.withArray("history").addObject().put("action", "PLAN_FALLBACK").put("eventId", eventId).set("result", result.deepCopy());
        copyState(next, world); return publicResult(result);
    }

    private JsonNode endingCatalog(JsonNode theme) {
        return theme.path("gameplayRuntime").has("storyEndings") ? theme.path("gameplayRuntime").path("storyEndings") : theme.path("endings");
    }
    /**
     * 作者明确声明「公开线索只首次生效」（publicOnce）的全场工具：其公开线索已由任一途径公开时，视为同一共享操作已完成，
     * 后来者不再收费、计次或发放，返回已公开结果（如同一项校验的多种途径）。未声明时公开线索只去重写入，奖励/费用/次数照常结算。
     */
    public static boolean publicFactsSettled(JsonNode facility, State world) {
        if (world == null || !facility.path("publicOnce").asBoolean() || !"WORLD".equals(facility.path("scope").asText()) || facility.path("worldEffects").isEmpty()) return false;
        for (JsonNode effect : facility.path("worldEffects")) {
            Object value = world.getVariables().get(effect.path("var").asText());
            if (!(value instanceof Number) || ((Number) value).intValue() != effect.path("value").asInt()) return false;
        }
        return true;
    }
    /** 额外节点（不计章节完成）上的工具：所属幕已过后仍可使用，次数与费用照常（如营地休整、应急救助、公共供暖）。 */
    public static boolean openExtraFacility(JsonNode theme, State state, JsonNode facility) {
        JsonNode quest = null;
        for (JsonNode row : theme.path("gameplayRuntime").path("quests")) if (facility.path("facilityKey").asText().equals(row.path("facilityKey").asText())) quest = row;
        if (quest == null || !quest.path("extraOnly").asBoolean()) return false;
        return actPassed(theme, state, facility.path("actKey").asText());
    }
    /** 某幕是否已是本归属走过的幕（当前幕之前）。 */
    public static boolean actPassed(JsonNode theme, State state, String actKey) {
        JsonNode acts = theme.path("gameplayRuntime").path("acts");
        int current = state.getThemeRuntime().path("gameplay").path("actIndex").asInt();
        for (int i = 0; i < acts.size() && i < current; i++) if (actKey.equals(acts.get(i).path("actKey").asText())) return true;
        return false;
    }
    public static boolean isEffectiveTarget(JsonNode theme, State state) {
        if (state == null) return false;
        if ("ENDED".equals(state.getStatus()) && "HP_ZERO".equals(state.getThemeRuntime().path("result").path("reason").asText())) return false;
        if (state.getThemeRuntime().path("awaitingChallenge").asBoolean() || !"COMPLETED".equals(state.getStatus())
                || !"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(theme))) return true;
        for (JsonNode plan : theme.path("gameplayRuntime").path("plans")) if (plan.path("terminal").asBoolean(true)) return true;
        return false;
    }

    private void requireEffectiveTarget(JsonNode theme, State state) {
        if (!isEffectiveTarget(theme, state)) throw new IllegalStateException("本队已完成最后章节，不能继续玩法操作");
    }

    public ObjectNode execute(JsonNode theme, JourneyRules rules, State world, Map<Long, State> teams,
                              Map<Long, Long> currentLeaders, Long actorTeamId, Long actorMemberId,
                              String action, ObjectNode payload, String eventId) {
        return execute(theme,rules,world,teams,currentLeaders,actorTeamId,actorMemberId,action,payload,eventId,System.currentTimeMillis());
    }

    public ObjectNode execute(JsonNode theme, JourneyRules rules, State world, Map<Long, State> teams,
                              Map<Long, Long> currentLeaders, Long actorTeamId, Long actorMemberId,
                              String action, ObjectNode payload, String eventId, long now) {
        return execute(theme,rules,world,teams,currentLeaders,actorTeamId,actorMemberId,action,payload,eventId,now,null);
    }

    public ObjectNode execute(JsonNode theme, JourneyRules rules, State world, Map<Long, State> teams,
                              Map<Long, Long> currentLeaders, Long actorTeamId, Long actorMemberId,
                              String action, ObjectNode payload, String eventId, long now, JsonNode frozen) {
        if (!supports(action) || world == null || payload == null || eventId == null || eventId.length() > 120
                || eventId.isEmpty()) throw new IllegalArgumentException("协作命令或事件标识无效");
        if (!"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(theme))) throw new IllegalStateException("本局未启用公共协作");
        if (!teams.containsKey(actorTeamId) || !Objects.equals(currentLeaders.get(actorTeamId), actorMemberId)
                || actorMemberId == null) throw new IllegalStateException("仅当前真实队长可以签署本队决定");
        ObjectNode request = JSON.createObjectNode().put("action", action).put("teamId", actorTeamId).put("memberId", actorMemberId);
        request.set("payload", payload.deepCopy());
        JsonNode previous = world.getThemeRuntime().path("cooperation").path("receipts").path(eventId);
        if (previous.isObject()) {
            if (!previous.path("request").equals(request)) throw new IllegalStateException("同一事件不能更改内容或签署人");
            return (ObjectNode) previous.path("result").deepCopy();
        }
        requireEffectiveTarget(theme, teams.get(actorTeamId));
        if (world.getThemeRuntime().path("cooperation").has("result")) throw new IllegalStateException("公共结果已冻结");
        if (world.getThemeRuntime().path("cooperation").path("receipts").size() >= 4096) throw new IllegalStateException("本场事件容量已满，请导出并复核");
        State nextWorld = world.copy();
        Map<Long, State> next = new LinkedHashMap<>();
        teams.forEach((id, state) -> next.put(id, state.copy()));
        ObjectNode c = nextWorld.getThemeRuntime().with("cooperation");
        ObjectNode result;
        switch (action) {
            case "TRADE_OPEN": result = openTrade(theme, nextWorld, next, actorTeamId, payload); break;
            case "TRADE_CONFIRM": result = confirmTrade(theme, rules, nextWorld, next, currentLeaders, actorTeamId, actorMemberId, payload); break;
            case "TRADE_CANCEL": result = cancelTrade(c, actorTeamId, payload); break;
            case "FACILITY_USE": requireUnboundFacility(theme,configured(theme,"facilities","facilityKey",key(payload,"facilityKey")),next.get(actorTeamId));result = participantFacility(theme, rules, nextWorld, next, actorTeamId, payload, eventId, now); break;
            default:
                Map<Long, State> effective = new LinkedHashMap<>();
                next.forEach((id, state) -> { if (isEffectiveTarget(theme, state)) effective.put(id, state); });
                result = deliberate(theme, rules, nextWorld, effective, currentLeaders, actorTeamId, actorMemberId, action, payload, eventId, now, frozen);
        }
        ObjectNode stored = c.with("receipts").putObject(eventId);
        stored.set("request", request); stored.set("result", result.deepCopy());
        ObjectNode event = c.withArray("history").addObject();
        event.put("eventId", eventId).put("action", action).put("teamId", actorTeamId).put("memberId", actorMemberId);
        event.set("result", result.deepCopy());
        validateJournal(next.values());world.setVariables(nextWorld.getVariables()); world.setTags(nextWorld.getTags()); world.setThemeRuntime(nextWorld.getThemeRuntime());
        teams.putAll(next);
        return result;
    }

    public ObjectNode ownerFacility(JsonNode theme,JourneyRules rules,State state,Long realOwnerId,ObjectNode payload,String eventId) {
        if(realOwnerId==null || realOwnerId<=0)throw new IllegalArgumentException("需要真实归属ID");
        requireEffectiveTarget(theme, state);
        JsonNode f=configured(theme,"facilities","facilityKey",key(payload,"facilityKey"));
        if(!"OWNER".equals(f.path("scope").asText()) || !"OWNER".equals(f.path("targetScope").asText()))throw new IllegalArgumentException("独立运行只支持本归属设施");
        State next=state.copy();Map<Long,State> owners=new LinkedHashMap<>();owners.put(realOwnerId,next);
        requireUnboundFacility(theme,f,state);
        boolean alreadySettled=state.getThemeRuntime().path("cooperation").path("facilities").path(f.path("settlementSlot").asText()).path("count").asInt()>=f.path("limit").asInt();
        Map<String,Set<JourneyStatLinks.Reason>> reasons=new LinkedHashMap<>();
        ObjectNode result=facility(theme,rules,new State(),owners,realOwnerId,payload,eventId,false,false,reasons);
        if(!alreadySettled)JourneyStatLinks.apply(rules,state,next,("PLAYER".equals(GameplayRuntimeConfig.getRuntimeScope(theme))?"MEMBER:":"TEAM:")+realOwnerId,eventId,reasons);
        if("PLAYER".equals(GameplayRuntimeConfig.getRuntimeScope(theme))) {if(result.has("paidByTeamId"))result.put("paidByOwnerId",realOwnerId);result.put("ownerId",realOwnerId);result.remove(Arrays.asList("paidByTeamId","targets"));next.getThemeRuntime().with("cooperation").with("facilities").with(f.path("settlementSlot").asText()).set("result",result.deepCopy());}
        validateJournal(Collections.singleton(next));copyState(next,state);return result;
    }

    public ObjectNode previewFacility(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Map<Long,Long> leaders,
                                      Long actor,Long member,ObjectNode payload,long now) {
        ObjectNode simulation=((ObjectNode)theme).deepCopy();simulation.put("capacity",Integer.MAX_VALUE);
        ObjectNode selection=payload.deepCopy();selection.remove("previewVersion");
        ObjectNode result=settleFacility(simulation,rules,world.copy(),copyOwners(teams),leaders,actor,member,selection,"preview",now,false);
        result.put("capacity",theme.path("capacity").asInt());boolean overflow=false;
        for(JsonNode owner:result.path("rewardOwners")) {((ObjectNode)owner).put("capacity",theme.path("capacity").asInt());boolean full=owner.path("finalUsed").asInt()>theme.path("capacity").asInt();((ObjectNode)owner).put("overflow",full);overflow|=full;}
        result.put("overflow",overflow).put("previewVersion",facilityPreviewVersion(theme,world,teams,actor,member,selection,result.path("rewardOwners")));
        return result;
    }
    public ObjectNode settleFacility(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Map<Long,Long> leaders,
                                     Long actor,Long member,ObjectNode payload,String eventId,long now) {
        return settleFacility(theme,rules,world,teams,leaders,actor,member,payload,eventId,now,true);
    }
    private ObjectNode settleFacility(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Map<Long,Long> leaders,
                                      Long actor,Long member,ObjectNode payload,String eventId,long now,boolean checkPreview) {
        if(!teams.containsKey(actor) || !Objects.equals(leaders.get(actor),member) || member==null)throw new IllegalStateException("只能由当前归属签署结算");
        ObjectNode request=JSON.createObjectNode().put("memberId",member);request.set("payload",payload.deepCopy());
        JsonNode prior=teams.get(actor).getThemeRuntime().path("cooperation").path("rewardReceipts").path(eventId);
        if(prior.isObject()){if(!request.equals(prior.path("request")))throw new IllegalStateException("同一结算不能更换装载选项");return (ObjectNode)prior.path("result").deepCopy();}
        if(teams.get(actor).getThemeRuntime().path("cooperation").path("rewardReceipts").size()>=4096)throw new IllegalStateException("结算回执容量已满");
        ArrayNode selection=inventory.rewardSelection(theme,payload.get("discardRewards"));
        if(checkPreview && (!selection.isEmpty() || payload.has("previewVersion"))) {
            if(!payload.path("previewVersion").asText().equals(previewFacility(theme,rules,world,teams,leaders,actor,member,payload,now).path("previewVersion").asText()))throw new IllegalStateException("装载预览已过期，请重新确认");
        }
        State nextWorld=world.copy();Map<Long,State> next=copyOwners(teams);
        next.forEach((id,state)->inventory.beginRewards(theme,state,id.equals(actor)?selection:null));
        ObjectNode clean=payload.deepCopy();clean.remove(Arrays.asList("discardRewards","previewVersion"));ObjectNode result;
        if("WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(theme)))result=execute(theme,rules,nextWorld,next,leaders,actor,member,"FACILITY_USE",clean,eventId,now);
        else {result=ownerFacility(theme,rules,next.get(actor),actor,clean,eventId);}
        if("BLOCKED_COST".equals(result.path("status").asText())){next.values().forEach(state->state.getThemeRuntime().remove("rewardSettlement"));copyState(nextWorld,world);teams.putAll(next);return result;}
        ArrayNode owners=JSON.createArrayNode();Set<Long> affected=new LinkedHashSet<>();affected.add(actor);for(JsonNode target:result.path("targets"))affected.add(target.asLong());
        for(Map.Entry<Long,State> entry:next.entrySet()) {
            State state=entry.getValue();if(!affected.contains(entry.getKey())){state.getThemeRuntime().remove("rewardSettlement");continue;}
            State before=state.copy();inventory.events(theme,state,rules);
            JourneyStatLinks.apply(rules,before,state,("PLAYER".equals(GameplayRuntimeConfig.getRuntimeScope(theme))?"MEMBER:":"TEAM:")+entry.getKey(),eventId+":events",JourneyStatLinks.Reason.TEMPLATE_EFFECT);
            ObjectNode rewards=inventory.finishRewards(state);rewards.put("ownerId",entry.getKey()).put("finalUsed",inventory.used(theme,state));owners.add(rewards);
        }
        result.set("rewardOwners",owners);ObjectNode receipt=next.get(actor).getThemeRuntime().with("cooperation").with("rewardReceipts").putObject(eventId);receipt.set("request",request);receipt.set("result",result.deepCopy());
        copyState(nextWorld,world);teams.putAll(next);return result;
    }
    private Map<Long,State> copyOwners(Map<Long,State> teams) {Map<Long,State> next=new LinkedHashMap<>();teams.forEach((id,state)->next.put(id,state.copy()));return next;}
    private String facilityPreviewVersion(JsonNode theme,State world,Map<Long,State> teams,Long actor,Long member,JsonNode payload,JsonNode rewards) {
        ObjectNode value=JSON.createObjectNode();value.set("theme",theme);value.set("world",JSON.valueToTree(world));value.set("owners",JSON.valueToTree(teams));value.set("payload",payload);value.set("rewards",rewards);value.put("actor",actor).put("member",member);
        try {byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder hash=new StringBuilder();for(byte b:bytes)hash.append(String.format("%02x",b&255));return hash.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    private ObjectNode participantFacility(JsonNode theme,JourneyRules rules,State world,Map<Long,State> owners,Long actor,ObjectNode payload,String eventId,long now) {
        JsonNode f=configured(theme,"facilities","facilityKey",key(payload,"facilityKey"));
        if(!"PARTICIPANTS".equals(f.path("targetScope").asText()))return facility(theme,rules,world,owners,actor,payload,eventId);
        Map<Long,State> before=new LinkedHashMap<>();owners.forEach((id,state)->before.put(id,state.copy()));
        Map<Long,Map<String,Set<JourneyStatLinks.Reason>>> reasons=new LinkedHashMap<>();owners.keySet().forEach(id->reasons.put(id,new LinkedHashMap<>()));
        JsonNode slot=("WORLD".equals(f.path("scope").asText())?world:owners.get(actor)).getThemeRuntime().path("cooperation").path("facilities").path(f.path("settlementSlot").asText());
        if(slot.path("count").asInt()>=f.path("limit").asInt())return facility(theme,rules,world,owners,actor,payload,eventId);
        ObjectNode result=facility(theme,rules,world,owners,actor,payload,eventId,false,true,reasons.get(actor));
        if("BLOCKED_COST".equals(result.path("status").asText()))return result;
        JsonNode trustedEffects = trustedFacilityEffects(theme, f, before.get(actor));
        JsonNode operationEffects=before.get(actor).getThemeRuntime().path("operationFacts").path("FACILITY:"+f.path("facilityKey").asText()).path("effects");
        GameplayTaskEngine tasks=new GameplayTaskEngine();
        for(JsonNode target:result.path("targets")) {
            Long id=target.asLong();State state=owners.get(id);String active=state.getThemeRuntime().path("gameplay").path("activeQuest").asText("");
            if(!active.isEmpty()) {
                JsonNode q=tasks.quest(theme,active);
                if(q.path("nodeId").asLong()!=f.path("operationNodeId").asLong() || q.has("facilityKey") || q.has("costOptions") || tasks.costs(q).path("hpCost").asInt()!=0 || tasks.costs(q).path("luckCost").asInt()!=0 || tasks.costs(q).path("actionCost").asInt()!=0 || q.path("statCosts").size()!=0 || q.path("resourceCosts").size()!=0 || q.path("grants").size()!=0 || q.path("choices").size()!=0)throw new IllegalStateException("参与工具只能联合完成当前同节点的免费任务");
                ObjectNode pending = state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").with(active);
                boolean ownEffects=pending.has("effects");
                if (!pending.has("effects")) pending.set("effects", trustedEffects.deepCopy());
                pending.with("sources");
                tasks.execute(theme,rules,state,active,"QUEST_RESOLVE",null,eventId+":task:"+id,"TEAM:"+id,now,reasons.get(id),false);
                if(ownEffects)TemplateEffects.apply(operationEffects,rules,state);
            }
            applyFacilityEffects(f,rules,state,active.isEmpty() ? trustedEffects : JSON.createArrayNode(),reasons.get(id));
            State rewardBefore=state.copy();inventory.grantAll(theme,state,rules,"facility:"+f.path("settlementSlot").asText()+":"+slot.path("count").asInt()+":reward",array(f.path("grants")));inventory.reconcileHeld(theme,state,rules);
            JourneyStatLinks.recordDeclines(rules,rewardBefore,state,JourneyStatLinks.Reason.FACILITY_EFFECT,reasons.get(id));
            State eventBefore=state.copy();inventory.events(theme,state,rules);JourneyStatLinks.recordDeclines(rules,eventBefore,state,JourneyStatLinks.Reason.TEMPLATE_EFFECT,reasons.get(id));
            JourneyStatLinks.apply(rules,before.get(id),state,"TEAM:"+id,eventId,reasons.get(id));
        }
        consumeFacilityEffects(owners.get(actor), f);
        return result;
    }

    public ObjectNode previewTrade(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Long actorTeamId,ObjectNode payload) {
        if(!"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(theme)) || !teams.containsKey(actorTeamId))throw new IllegalArgumentException("交易需要本场真实队伍");
        State nextWorld=world.copy();Map<Long,State> next=new LinkedHashMap<>();teams.forEach((id,state)->next.put(id,state.copy()));
        ObjectNode row=openTrade(theme,nextWorld,next,actorTeamId,payload);
        Long to=positiveId(payload,"toTeamId");
        inventory.consumeAll(theme,next.get(actorTeamId),rules,"preview:from:out",row.path("give"));
        inventory.consumeAll(theme,next.get(to),rules,"preview:to:out",row.path("take"));
        inventory.grantAll(theme,next.get(actorTeamId),rules,"preview:from:in",row.path("take"));
        inventory.grantAll(theme,next.get(to),rules,"preview:to:in",row.path("give"));
        row.put("preview",true).put("fromUsed",inventory.view(theme,next.get(actorTeamId)).path("used").asInt()).put("toUsed",inventory.view(theme,next.get(to)).path("used").asInt()).put("capacity",theme.path("capacity").asInt());return row;
    }

    public ObjectNode previewQuest(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Map<Long,Long> leaders,
                                   Long actor,Long member,String questKey,String choiceKey,JsonNode discardRewards,Integer costOption,String ownerKey,long now) {
        GameplayTaskEngine tasks=new GameplayTaskEngine();JsonNode quest=tasks.quest(theme,questKey);State source=teams.get(actor);
        if(source==null || !Objects.equals(leaders.get(actor),member))throw new IllegalStateException("预览须使用当前真实归属");
        if(!quest.has("facilityKey"))return tasks.preview(theme,rules,source,questKey,choiceKey,discardRewards,ownerKey,costOption,now);
        Integer selected=costOption!=null?costOption:source.getThemeRuntime().path("gameplay").path("activeQuestCostOption").isInt()?Integer.valueOf(source.getThemeRuntime().path("gameplay").path("activeQuestCostOption").asInt()):null;
        ObjectNode simulation=((ObjectNode)theme).deepCopy();simulation.put("capacity",Integer.MAX_VALUE);Map<Long,State> next=copyOwners(teams);ArrayNode selection=inventory.rewardSelection(theme,discardRewards);
        next.forEach((id,state)->inventory.beginRewards(simulation,state,id.equals(actor)?selection:null));
        next.get(actor).getThemeRuntime().with("gameplay").put("activeQuest",questKey);next.get(actor).getThemeRuntime().with("verifiedNodes").put(quest.path("nodeId").asText(),true);
        ObjectNode result=executeQuest(simulation,rules,world.copy(),next,leaders,actor,member,questKey,"QUEST_RESOLVE",choiceKey,"preview",ownerKey,now,null,null,selected);
        ArrayNode owners=JSON.createArrayNode();boolean overflow=false;
        for(Map.Entry<Long,State> entry:next.entrySet()) {
            if(!entry.getKey().equals(actor) && entry.getValue().getThemeRuntime().path("rewardSettlement").path("rewardSources").isMissingNode()){entry.getValue().getThemeRuntime().remove("rewardSettlement");continue;}
            ObjectNode reward=inventory.finishRewards(entry.getValue());reward.put("ownerId",entry.getKey()).put("finalUsed",inventory.used(simulation,entry.getValue())).put("capacity",theme.path("capacity").asInt());
            boolean full=reward.path("finalUsed").asInt()>theme.path("capacity").asInt();reward.put("overflow",full);overflow|=full;owners.add(reward);
            if(entry.getKey().equals(actor)){result.set("grants",reward.path("grants"));result.set("rewardSources",reward.path("rewardSources"));ArrayNode actual=JSON.createArrayNode();reward.path("actualGrants").fields().forEachRemaining(row->{if(row.getValue().asInt()>0)actual.addObject().put("itemId",row.getKey()).put("quantity",row.getValue().asInt());});result.set("actualGrants",actual);result.put("finalUsed",reward.path("finalUsed").asInt());}
        }
        result.set("rewardOwners",owners);result.set("discardRewards",selection);result.put("capacity",theme.path("capacity").asInt()).put("overflow",overflow);
        ObjectNode payload=JSON.createObjectNode().put("questKey",questKey).put("choiceKey",choiceKey).put("ownerKey",ownerKey);if(selected!=null)payload.put("costOption",selected);payload.set("discardRewards",selection);
        result.put("previewVersion",facilityPreviewVersion(theme,world,teams,actor,member,payload,owners));return result;
    }

    public ObjectNode executeQuest(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Map<Long,Long> leaders,
                                   Long actorTeamId,Long actorMemberId,String questKey,String action,String choiceKey,String eventId,String ownerKey,long now) {
        return executeQuest(theme,rules,world,teams,leaders,actorTeamId,actorMemberId,questKey,action,choiceKey,eventId,ownerKey,now,JSON.createArrayNode(),null);
    }
    public ObjectNode executeQuest(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Map<Long,Long> leaders,
                                   Long actorTeamId,Long actorMemberId,String questKey,String action,String choiceKey,String eventId,String ownerKey,long now,JsonNode discardRewards,String previewVersion) {
        return executeQuest(theme,rules,world,teams,leaders,actorTeamId,actorMemberId,questKey,action,choiceKey,eventId,ownerKey,now,discardRewards,previewVersion,null);
    }
    public ObjectNode executeQuest(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Map<Long,Long> leaders,
                                   Long actorTeamId,Long actorMemberId,String questKey,String action,String choiceKey,String eventId,String ownerKey,long now,JsonNode discardRewards,String previewVersion,Integer costOption) {
        if(!Arrays.asList("QUEST_START","QUEST_CANCEL","QUEST_RESOLVE").contains(action))throw new IllegalArgumentException("不是任务动作");
        if(!teams.containsKey(actorTeamId) || actorMemberId==null || !Objects.equals(leaders.get(actorTeamId),actorMemberId))throw new IllegalStateException("仅当前真实队长可结算任务");
        requireEffectiveTarget(theme, teams.get(actorTeamId));
        GameplayTaskEngine tasks=new GameplayTaskEngine();JsonNode q=tasks.quest(theme,questKey);
        if(!q.has("facilityKey") || "QUEST_CANCEL".equals(action))return tasks.execute(theme,rules,teams.get(actorTeamId),questKey,action,choiceKey,eventId,ownerKey,now,discardRewards,previewVersion,costOption);
        requireMutable(world);State source=teams.get(actorTeamId);
        if(source.getThemeRuntime().path("gameplay").path("slots").has(q.path("settlementSlot").asText()))return tasks.execute(theme,rules,source,questKey,action,choiceKey,eventId,ownerKey,now,discardRewards,previewVersion,costOption);
        tasks.eligible(theme,source,q);
        String active=source.getThemeRuntime().path("gameplay").path("activeQuest").asText();
        if("QUEST_START".equals(action) && !active.isEmpty() && !active.equals(questKey))throw new IllegalStateException("请先完成或取消当前任务");
        JsonNode f=configured(theme,"facilities","facilityKey",q.path("facilityKey").asText());
        if("QUEST_RESOLVE".equals(action) && (!questKey.equals(source.getThemeRuntime().path("gameplay").path("activeQuest").asText()) || !trusted(source.getThemeRuntime().path("verifiedNodes").path(String.valueOf(q.path("nodeId").asLong())))))throw new IllegalStateException("请先开始任务并通过可信题组");
        JsonNode used=("WORLD".equals(f.path("scope").asText())?world:source).getThemeRuntime().path("cooperation").path("facilities").path(f.path("settlementSlot").asText());
        if(used.path("count").asInt()>=f.path("limit").asInt() || publicFactsSettled(f,world)) {
            State loser=source.copy();ObjectNode progress=loser.getThemeRuntime().with("gameplay");
            if(active.equals(questKey))progress.remove(Arrays.asList("activeQuest","activeQuestReceiptId","activeQuestCostOption"));
            JsonNode pending=progress.path("pendingTaskEffects");if(pending.isObject())((ObjectNode)pending).remove(questKey);
            String node=q.path("nodeId").asText();loser.getThemeRuntime().with("verifiedNodes").remove(node);loser.getVariables().remove("sys.passed."+node);
            JsonNode proof=loser.getThemeRuntime().path("operationFacts").path("FACILITY:"+q.path("facilityKey").asText());
            if(proof.path("sourceNodeId").asLong(-1)==q.path("nodeId").asLong())loser.getThemeRuntime().with("operationFacts").remove("FACILITY:"+q.path("facilityKey").asText());
            tasks.refresh(theme,loser);copyState(loser,source);
            ObjectNode result=JSON.createObjectNode().put("questKey",questKey).put("facilityKey",f.path("facilityKey").asText()).put("competitionLost",true).put("settled",false);result.set("facilityResult",used.path("result").deepCopy());return result;
        }
        Integer selected=costOption!=null?costOption:source.getThemeRuntime().path("gameplay").path("activeQuestCostOption").isInt()?Integer.valueOf(source.getThemeRuntime().path("gameplay").path("activeQuestCostOption").asInt()):null;
        if(!"QUEST_RESOLVE".equals(action)) {
            if("QUEST_START".equals(action)) {ObjectNode fee=boundFacilityCosts(theme,rules,source,teams,f,q,selected);State check=source.copy();paidGuard(theme,rules,check,fee);inventory.settle(theme,check,rules,"preflight:facility:"+eventId,fee,JSON.createArrayNode());}
            return tasks.execute(theme,rules,source,questKey,action,choiceKey,eventId,ownerKey,now,discardRewards,previewVersion,costOption);
        }
        if(previewVersion!=null || (discardRewards!=null && discardRewards.size()>0))previewQuest(theme,rules,world,teams,leaders,actorTeamId,actorMemberId,questKey,choiceKey,discardRewards,selected,ownerKey,now);
        State nextWorld=world.copy();Map<Long,State> next=new LinkedHashMap<>();teams.forEach((id,state)->next.put(id,state.copy()));
        boolean ownRewards=!source.getThemeRuntime().has("rewardSettlement");
        if(ownRewards)next.forEach((id,state)->inventory.beginRewards(theme,state,id.equals(actorTeamId)?discardRewards:null));
        ObjectNode facilityCosts=boundFacilityCosts(theme,rules,source,teams,f,q,selected),questCosts=tasks.costs(q,q.has("costOptions")?selected:null);
        Map<String,Integer> combined=amountMap(questCosts.path("resourceCosts"));add(combined,facilityCosts.path("resourceCosts"));ArrayNode needed=JSON.createArrayNode();combined.forEach((id,qty)->needed.addObject().put("itemId",id).put("quantity",qty));requireAvailable(world,actorTeamId,source,needed);
        State costPreview=source.copy();paidGuard(theme,rules,costPreview,facilityCosts);inventory.settle(theme,costPreview,rules,"preflight:facility:"+eventId,facilityCosts,JSON.createArrayNode());paidGuard(theme,rules,costPreview,questCosts);inventory.settle(theme,costPreview,rules,"preflight:quest:"+eventId,questCosts,JSON.createArrayNode());
        ObjectNode facilityPayload=JSON.createObjectNode().put("facilityKey",q.path("facilityKey").asText());
        if(f.has("costOptions"))facilityPayload.put("costOption",selected);
        if("PARTICIPANTS".equals(f.path("targetScope").asText()))throw new IllegalArgumentException("参与设施须使用独立集合命令，不能绑定单队任务");
        Map<String,Set<JourneyStatLinks.Reason>> reasons=new LinkedHashMap<>();
        ObjectNode facilityResult=facility(theme,rules,nextWorld,next,actorTeamId,facilityPayload,eventId,false,true,reasons);
        if("BLOCKED_COST".equals(facilityResult.path("status").asText())){if(ownRewards)next.values().forEach(state->state.getThemeRuntime().remove("rewardSettlement"));copyState(nextWorld,world);teams.putAll(next);return facilityResult;}
        // 配包指纹属于效果前状态，先验证，再以同一冻结奖励在克隆状态结算。
        if((previewVersion!=null || (discardRewards!=null && discardRewards.size()>0)) && !Objects.equals(previewVersion,previewQuest(theme,rules,world,teams,leaders,actorTeamId,actorMemberId,questKey,choiceKey,discardRewards,selected,ownerKey,now).path("previewVersion").asText()))throw new IllegalStateException("配包预览已过期");
        next.get(actorTeamId).getThemeRuntime().with("gameplay").put("facilitySettled",questKey);
        ObjectNode result=tasks.execute(theme,rules,next.get(actorTeamId),questKey,action,choiceKey,eventId,ownerKey,now,null,null,selected,reasons,false);result.set("facilityResult",facilityResult);
        if(!facilityResult.path("noEffect").asBoolean() || theme.path("gameplayRuntime").path("survival").path("consumeOnNoEffect").asBoolean())applyFacilityEffects(f,rules,next.get(actorTeamId),next.get(actorTeamId).getThemeRuntime().path("operationFacts").path("FACILITY:"+f.path("facilityKey").asText()).path("effects"),reasons);
        consumeFacilityEffects(next.get(actorTeamId), f);
        State beforeGrant=next.get(actorTeamId).copy();
        inventory.grantAll(theme,next.get(actorTeamId),rules,"facility:"+f.path("settlementSlot").asText()+":"+used.path("count").asInt()+":reward",array(f.path("grants")));
        inventory.reconcileHeld(theme,next.get(actorTeamId),rules);JourneyStatLinks.recordDeclines(rules,beforeGrant,next.get(actorTeamId),JourneyStatLinks.Reason.FACILITY_EFFECT,reasons);
        State eventBefore=next.get(actorTeamId).copy();inventory.events(theme,next.get(actorTeamId),rules);JourneyInitialState.clamp(rules,next.get(actorTeamId));JourneyStatLinks.recordDeclines(rules,eventBefore,next.get(actorTeamId),JourneyStatLinks.Reason.TEMPLATE_EFFECT,reasons);
        next.get(actorTeamId).getThemeRuntime().with("gameplay").remove("facilitySettled");
        JourneyStatLinks.apply(rules,source,next.get(actorTeamId),ownerKey,eventId,reasons);
        for(JsonNode targetId:facilityResult.path("targets")) {
            Long target=targetId.asLong();if(target.equals(actorTeamId))continue;State eventEntry=next.get(target).copy();Map<String,Set<JourneyStatLinks.Reason>> targetReasons=new LinkedHashMap<>();
            inventory.events(theme,next.get(target),rules);JourneyStatLinks.recordDeclines(rules,teams.get(target),eventEntry,JourneyStatLinks.Reason.FACILITY_EFFECT,targetReasons);
            JourneyStatLinks.recordDeclines(rules,eventEntry,next.get(target),JourneyStatLinks.Reason.TEMPLATE_EFFECT,targetReasons);JourneyStatLinks.apply(rules,teams.get(target),next.get(target),"TEAM:"+target,eventId,targetReasons);
        }
        State actor=next.get(actorTeamId);if("COMPLETED".equals(actor.getStatus()) && !"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(theme)) && !GameplaySurvivalEngine.deferCompletion(theme,actor))inventory.terminal(theme,actor,rules,"COMPLETED",now);
        if(ownRewards){
            ArrayNode rewards=JSON.createArrayNode();for(Map.Entry<Long,State> entry:next.entrySet()) {
                if(!entry.getKey().equals(actorTeamId) && entry.getValue().getThemeRuntime().path("rewardSettlement").path("rewardSources").isMissingNode()){entry.getValue().getThemeRuntime().remove("rewardSettlement");continue;}
                ObjectNode reward=inventory.finishRewards(entry.getValue());reward.put("ownerId",entry.getKey());rewards.add(reward);if(entry.getKey().equals(actorTeamId))result.set("rewards",reward);
            }
            result.set("rewardOwners",rewards);if(discardRewards!=null && !discardRewards.isEmpty())result.set("discardRewards",inventory.rewardSelection(theme,discardRewards));
        }
        result.put("used",inventory.view(theme,actor).path("used").asInt());actor.getThemeRuntime().with("gameplay").with("slots").set(q.path("settlementSlot").asText(),result.deepCopy());
        validateJournal(next.values());copyState(nextWorld,world);teams.putAll(next);return result;
    }
    public ObjectNode ownerQuest(JsonNode theme,JourneyRules rules,State state,Long realOwnerId,String questKey,String action,String choiceKey,String eventId,String ownerKey,long now,JsonNode discardRewards,String previewVersion) {
        return ownerQuest(theme,rules,state,realOwnerId,questKey,action,choiceKey,eventId,ownerKey,now,discardRewards,previewVersion,null);
    }
    public ObjectNode ownerQuest(JsonNode theme,JourneyRules rules,State state,Long realOwnerId,String questKey,String action,String choiceKey,String eventId,String ownerKey,long now,JsonNode discardRewards,String previewVersion,Integer costOption) {
        if(realOwnerId==null || realOwnerId<=0)throw new IllegalArgumentException("需要真实归属ID");
        JsonNode q=new GameplayTaskEngine().quest(theme,questKey);if(q.has("facilityKey")) {JsonNode f=configured(theme,"facilities","facilityKey",q.path("facilityKey").asText());if(!"OWNER".equals(f.path("scope").asText()) || !"OWNER".equals(f.path("targetScope").asText()))throw new IllegalArgumentException("独立任务只支持本归属设施");}
        Map<Long,State> owners=new LinkedHashMap<>();owners.put(realOwnerId,state.copy());Map<Long,Long> principals=new LinkedHashMap<>();principals.put(realOwnerId,realOwnerId);
        ObjectNode result=executeQuest(theme,rules,new State(),owners,principals,realOwnerId,realOwnerId,questKey,action,choiceKey,eventId,ownerKey,now,discardRewards,previewVersion,costOption);copyState(owners.get(realOwnerId),state);return result;
    }
    private void applyFacilityEffects(JsonNode facility,JourneyRules rules,State state,JsonNode trustedEffects,Map<String,Set<JourneyStatLinks.Reason>> reasons) {
        State before=reasons==null?null:state.copy();
        if(facility.has("hpDelta"))inventory.changeStat(state,rules,"sys.hp",facility.path("hpDelta").asInt());
        for(JsonNode change:facility.path("statChanges"))inventory.changeStat(state,rules,change.path("statKey").asText(),change.path("delta").asInt());
        TemplateEffects.apply(facility.path("effects"),rules,state);
        JourneyInitialState.clamp(rules,state);
        if(reasons!=null)JourneyStatLinks.recordDeclines(rules,before,state,JourneyStatLinks.Reason.FACILITY_EFFECT,reasons);
        if(trustedEffects.isArray()) {
            State operationBefore=reasons==null?null:state.copy();
            TemplateEffects.apply(trustedEffects,rules,state);
            JourneyInitialState.clamp(rules,state);
            if(reasons!=null)JourneyStatLinks.recordDeclines(rules,operationBefore,state,JourneyStatLinks.Reason.TEMPLATE_EFFECT,reasons);
        }
    }
    private void consumeFacilityEffects(State source, JsonNode facility) {
        JsonNode proof=source.getThemeRuntime().path("operationFacts").path("FACILITY:"+facility.path("facilityKey").asText());
        if(proof.isObject())((ObjectNode)proof).remove("effects");
    }
    private ArrayNode trustedFacilityEffects(JsonNode theme, JsonNode facility, State source) {
        ArrayNode effects = JSON.createArrayNode();
        JsonNode proof=source.getThemeRuntime().path("operationFacts").path("FACILITY:"+facility.path("facilityKey").asText()).path("effects");
        if(proof.isArray())effects.addAll((ArrayNode)proof.deepCopy());
        String active=source.getThemeRuntime().path("gameplay").path("activeQuest").asText();
        for(JsonNode quest:theme.path("gameplayRuntime").path("quests")) if(active.equals(quest.path("questKey").asText()) && quest.path("nodeId").asLong()==facility.path("operationNodeId").asLong()) {
            JsonNode pending=source.getThemeRuntime().path("gameplay").path("pendingTaskEffects").path(active).path("effects");
            if(pending.isArray())effects.addAll((ArrayNode)pending.deepCopy());
        }
        return effects;
    }
    private void requireUnboundFacility(JsonNode theme,JsonNode facility,State state) {
        for(JsonNode quest:theme.path("gameplayRuntime").path("quests"))if(facility.path("facilityKey").equals(quest.path("facilityKey")) && !state.getThemeRuntime().path("gameplay").path("slots").has(quest.path("settlementSlot").asText()))throw new IllegalStateException("此设施通过绑定任务结算，请从任务入口完成");
    }
    private void validateJournal(Collection<State> states) {
        for(State state:states)if(state.getThemeRuntime().path("journal").size()>4096)throw new IllegalStateException("本局事件明细容量已满，请暂停并导出复核");
    }
    private void copyState(State from,State to) {
        to.setVariables(from.getVariables());to.setTags(from.getTags());to.setThemeRuntime(from.getThemeRuntime());to.setStatus(from.getStatus());to.setNodeStates(from.getNodeStates());to.setCompletedNodeIds(from.getCompletedNodeIds());to.setVisitedNodeIds(from.getVisitedNodeIds());to.setCurrentNodeId(from.getCurrentNodeId());to.setRecommendedNodeId(from.getRecommendedNodeId());
    }

    private ObjectNode openTrade(JsonNode theme, State world, Map<Long, State> teams, Long actor, ObjectNode p) {
        fields(p, "tradeKey", "toTeamId", "give", "take");
        if (!theme.path("gameplayRuntime").path("allowTrade").asBoolean()) throw new IllegalStateException("主题未开放队间转移");
        requireMutable(world);
        String key = key(p, "tradeKey"); Long to = positiveId(p, "toTeamId");
        if (to.equals(actor) || !teams.containsKey(to)) throw new IllegalArgumentException("交易目标不在本场有效队伍中");
        requireTradeAccess(theme, teams.get(actor), teams.get(to));
        ObjectNode trades = world.getThemeRuntime().with("cooperation").with("trades");
        if (trades.has(key)) throw new IllegalStateException("交易标识已经使用");
        if (trades.size() >= 200) throw new IllegalStateException("本场交易容量已满");
        ArrayNode give = amounts(theme, p.path("give")), take = amounts(theme, p.path("take"));
        if (give.isEmpty() && take.isEmpty()) throw new IllegalArgumentException("交易不能为空");
        requireAvailable(world, actor, teams.get(actor), give); requireAvailable(world, to, teams.get(to), take);
        ObjectNode row = trades.putObject(key).put("tradeKey", key).put("fromTeamId", actor).put("toTeamId", to).put("version", 1).put("status", "OPEN");
        row.set("give", give); row.set("take", take);
        row.set("fromSnapshot", balance(teams.get(actor))); row.set("toSnapshot", balance(teams.get(to)));
        return publicTrade(row);
    }

    private ObjectNode confirmTrade(JsonNode theme, JourneyRules rules, State world, Map<Long, State> teams,
                                    Map<Long, Long> leaders, Long actor, Long member, ObjectNode p) {
        fields(p, "tradeKey", "version"); requireMutable(world);
        ObjectNode row = row(world.getThemeRuntime().with("cooperation").with("trades"), key(p, "tradeKey"));
        requireVersion(row, p); requireParty(row, actor);
        if (!"OPEN".equals(row.path("status").asText())) throw new IllegalStateException("交易已经结束");
        Long from = row.path("fromTeamId").asLong(), to = row.path("toTeamId").asLong();
        if (!teams.containsKey(from) || !teams.containsKey(to)) throw new IllegalStateException("交易队伍已退出");
        requireTradeAccess(theme, teams.get(actor), teams.get(actor.equals(from) ? to : from));
        if (!balance(teams.get(from)).equals(row.path("fromSnapshot")) || !balance(teams.get(to)).equals(row.path("toSnapshot")))
            throw new IllegalStateException("交易预览已过期，请取消并重新确认");
        row.put("status", "SETTLING");
        requireAvailable(world, actor, teams.get(actor), actor.equals(from) ? row.path("give") : row.path("take"));
        row.put("status", "OPEN"); row.with("signatures").put(String.valueOf(actor), member);
        if (!currentSignature(row.path("signatures"), leaders, from) || !currentSignature(row.path("signatures"), leaders, to)) return publicTrade(row);
        // 本单不再占预留；两边成本先移除后检查最终容量，不拒绝合法满包互换。
        row.put("status", "SETTLING");
        requireAvailable(world, from, teams.get(from), row.path("give")); requireAvailable(world, to, teams.get(to), row.path("take"));
        inventory.consumeAll(theme, teams.get(from), rules, "trade:" + row.path("tradeKey").asText() + ":from:out", row.path("give"));
        inventory.consumeAll(theme, teams.get(to), rules, "trade:" + row.path("tradeKey").asText() + ":to:out", row.path("take"));
        inventory.grantAll(theme, teams.get(from), rules, "trade:" + row.path("tradeKey").asText() + ":from:in", row.path("take"));
        inventory.grantAll(theme, teams.get(to), rules, "trade:" + row.path("tradeKey").asText() + ":to:in", row.path("give"));
        row.put("status", "COMPLETED"); return publicTrade(row);
    }

    private ObjectNode cancelTrade(ObjectNode c, Long actor, ObjectNode p) {
        fields(p, "tradeKey", "version"); ObjectNode row = row(c.with("trades"), key(p, "tradeKey"));
        requireVersion(row, p); requireParty(row, actor);
        if ("COMPLETED".equals(row.path("status").asText())) throw new IllegalStateException("已成交只能另建双方同意的反向交易");
        row.put("status", "CANCELLED"); row.remove("signatures"); return publicTrade(row);
    }

    public void requireAvailable(State world, Long teamId, State team, JsonNode costs) {
        ArrayNode missing=resourceShortfall(world,teamId,team,costs);
        if(!missing.isEmpty())throw new IllegalStateException("物资不足或已被本队确认预留："+missing.path(0).path("key").asText()+"，缺少"+missing.path(0).path("missing").asLong());
    }
    public ArrayNode resourceShortfall(State world,Long teamId,State team,JsonNode costs) {
        ArrayNode missing=JSON.createArrayNode();
        Map<String, Integer> locked = locked(world, teamId);
        for (JsonNode cost : costs) {
            String id = cost.path("itemId").asText();
            if ((long) team.getThemeRuntime().path("inventory").path(id).asInt() - locked.getOrDefault(id, 0) < cost.path("quantity").asInt())
                missing.addObject().put("type","ITEM").put("key",id).put("missing",(long)cost.path("quantity").asInt()-team.getThemeRuntime().path("inventory").path(id).asInt()+locked.getOrDefault(id,0));
        }
        return missing;
    }

    private Map<String, Integer> locked(State world, Long team) {
        Map<String, Integer> locked = new LinkedHashMap<>();
        JsonNode c = world.getThemeRuntime().path("cooperation");
        for (JsonNode trade : c.path("trades")) if ("OPEN".equals(trade.path("status").asText()) && trade.path("signatures").has(String.valueOf(team))) {
            if (trade.path("fromTeamId").asLong() == team) add(locked, trade.path("give"));
            if (trade.path("toTeamId").asLong() == team) add(locked, trade.path("take"));
        }
        add(locked, c.path("locks").path(String.valueOf(team)).path("amounts"));
        return locked;
    }

    public boolean isVotingFrozen(State world) {
        String stage = world.getThemeRuntime().path("cooperation").path("stage").asText();
        return Arrays.asList("VOTING", "TIED", "ACCEPTING", "CONSENSUS", "SELECTED").contains(stage);
    }
    private void requireMutable(State world) { if (isVotingFrozen(world)) throw new IllegalStateException("表决与托管期间禁止转移或消费，请先改案释放"); }

    private ObjectNode facility(JsonNode theme, JourneyRules rules, State world, Map<Long, State> teams,
                                Long actor, ObjectNode p, String eventId) {
        return facility(theme,rules,world,teams,actor,p,eventId,true);
    }
    private ObjectNode facility(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Long actor,ObjectNode p,String eventId,boolean applyLinks) {
        return facility(theme,rules,world,teams,actor,p,eventId,applyLinks,false);
    }
    private ObjectNode facility(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Long actor,ObjectNode p,String eventId,boolean applyLinks,boolean deferActorGrants) {
        return facility(theme,rules,world,teams,actor,p,eventId,applyLinks,deferActorGrants,null);
    }
    private ObjectNode facility(JsonNode theme,JourneyRules rules,State world,Map<Long,State> teams,Long actor,ObjectNode p,String eventId,boolean applyLinks,boolean deferActorGrants,Map<String,Set<JourneyStatLinks.Reason>> actorReasons) {
        if(actorReasons==null)actorReasons=new LinkedHashMap<>();
        fields(p, "facilityKey", "participants", "costOption"); requireMutable(world);
        JsonNode f = configured(theme, "facilities", "facilityKey", key(p, "facilityKey"));
        Integer costOption=costOption(p);
        ObjectNode costs = costs(f,costOption);
        State source = teams.get(actor);
        requireEffectiveTarget(theme, source);
        JsonNode act = theme.path("gameplayRuntime").path("acts").path(source.getThemeRuntime().path("gameplay").path("actIndex").asInt());
        if (!f.path("actKey").asText().equals(act.path("actKey").asText())) {
            String active=source.getThemeRuntime().path("gameplay").path("activeQuest").asText();
            JsonNode bound=active.isEmpty()?null:new GameplayTaskEngine().quest(theme,active);
            boolean deferred=deferActorGrants && bound!=null && f.path("actKey").equals(bound.path("actKey")) && f.path("facilityKey").equals(bound.path("facilityKey"));
            if(!deferred && !openExtraFacility(theme,source,f))throw new IllegalStateException("设施不属于当前幕");
            if(deferred)new GameplayTaskEngine().eligible(theme,source,bound);
        }
        ObjectNode slots = ("WORLD".equals(f.path("scope").asText()) ? world : source).getThemeRuntime().with("cooperation").with("facilities");
        String slot = f.path("settlementSlot").asText();
        int count = slots.path(slot).path("count").asInt();
        if (count >= f.path("limit").asInt()) return (ObjectNode) slots.path(slot).path("result").deepCopy();
        if (publicFactsSettled(f, world)) return JSON.createObjectNode().put("facilityKey", f.path("facilityKey").asText()).put("competitionLost", true).put("settled", false).put("consumed", false);
        if (!inventory.conditions(f.path("when"), source)) throw new IllegalStateException("设施前置不满足");
        if (f.has("operationNodeId") && !trusted(source.getThemeRuntime().path("verifiedNodes").path(String.valueOf(f.path("operationNodeId").asLong()))))
            throw new IllegalStateException("设施操作题组尚未通过可信判定");
        Set<Long> targets = new LinkedHashSet<>();
        String scope = f.path("targetScope").asText();
        if ("OWNER".equals(scope)) targets.add(actor);
        else if ("WORLD".equals(scope)) targets.addAll(teams.keySet());
        else {
            if (!p.path("participants").isArray() || p.path("participants").isEmpty()) throw new IllegalArgumentException("请选择真实参与队伍");
            for (JsonNode id : p.path("participants")) {
                if (!id.isIntegralNumber() || !teams.containsKey(id.longValue()) || !targets.add(id.longValue())) throw new IllegalArgumentException("参与队伍无效或重复");
            }
            if (!targets.contains(actor)) throw new IllegalArgumentException("费用来源队必须参与");
        }
        if (!"PARTICIPANTS".equals(scope) && p.has("participants")) throw new IllegalArgumentException("该设施无需客户端指定效果目标");
        targets.removeIf(target -> !isEffectiveTarget(theme, teams.get(target)));
        if (f.has("arrivalLocationKey")) for (Long target : targets)
            if (!teams.get(target).getThemeRuntime().path("hostArrivals").has(f.path("arrivalLocationKey").asText())) throw new IllegalStateException("参与队伍尚未经可信集合核验");
        // 绑定任务的扣费、节点效果与恢复按同一净变化结算，保留混合结算口径。
        Map<Long,State> before=new LinkedHashMap<>();teams.forEach((id,state)->before.put(id,state.copy()));
        String outcomeId=source.getThemeRuntime().path("operationFacts").path("FACILITY:"+f.path("facilityKey").asText()).path("outcomeAttemptId").asText();
        if(outcomeId.isEmpty())outcomeId=source.getThemeRuntime().path("gameplay").path("pendingTaskEffects").path(source.getThemeRuntime().path("gameplay").path("activeQuest").asText()).path("outcomeAttemptId").asText();
        if(!outcomeId.isEmpty()) {
            JsonNode previous=source.getThemeRuntime().path("outcomeAttempts").path(outcomeId).path("receipt").path("facilityResult");
            if(previous.isObject())return (ObjectNode)previous.deepCopy();
            Map<Long,State> recipients=new LinkedHashMap<>();for(Long target:targets)recipients.put(target,teams.get(target));
            ObjectNode outcome=new GameplayOutcomeEngine().settleRemembered(theme,rules,source,recipients,outcomeId,System.currentTimeMillis(),outcomeOnlyNoEffect(theme,rules,f,source,recipients.values()));
            if(!outcome.path("settled").asBoolean())return outcome;
            if(!outcome.path("consumed").asBoolean()) {
                ObjectNode noChange=JSON.createObjectNode().put("facilityKey",f.path("facilityKey").asText()).put("noEffect",true).put("consumed",false).put("count",count);
                source.getThemeRuntime().with("outcomeAttempts").with(outcomeId).with("receipt").set("facilityResult",noChange.deepCopy());return noChange;
            }
            JourneyStatLinks.recordDeclines(rules,before.get(actor),source,JourneyStatLinks.Reason.TASK_COST,actorReasons);
            JourneyStatLinks.recordDeclines(rules,before.get(actor),source,JourneyStatLinks.Reason.TEMPLATE_EFFECT,actorReasons);
        }
        JsonNode trustedEffects = trustedFacilityEffects(theme, f, source);
        boolean pureRecovery = outcomeId.isEmpty() && pureRecovery(theme, f, source);
        if (pureRecovery && deferActorGrants) for (Long target : targets) {
            if (!target.equals(actor) && !"PARTICIPANTS".equals(scope)) continue;
            State state = teams.get(target);
            String active = state.getThemeRuntime().path("gameplay").path("activeQuest").asText("");
            if ((!active.isEmpty() && mixedTaskSettlement(theme, state, new GameplayTaskEngine().quest(theme, active)))
                    || ("PARTICIPANTS".equals(scope) && !theme.path("events").isEmpty())) {
                pureRecovery = false;
                break;
            }
        }
        boolean noEffect = pureRecovery && targets.stream().noneMatch(target -> recoveryWouldChange(f, trustedEffects, rules, teams.get(target)));
        if (noEffect && !theme.path("gameplayRuntime").path("survival").path("consumeOnNoEffect").asBoolean()) {
            ObjectNode result=JSON.createObjectNode().put("facilityKey",f.path("facilityKey").asText()).put("noEffect",true).put("consumed",false).put("count",count);
            if(costOption!=null)result.put("costOption",costOption);return result;
        }
        requireAvailable(world, actor, source, costs.path("resourceCosts"));
        paidGuard(theme, rules, source, costs);
        ObjectNode costReceipt=inventory.settle(theme, source, rules, "facility:" + slot + ":" + count, costs, JSON.createArrayNode());
        boolean paid=costReceipt.path("resourceCosts").size()>0 || costs.path("actionCost").asInt()>0;
        for(JsonNode delta:costReceipt.path("statDeltas"))paid |= delta.asInt()!=0;
        JourneyStatLinks.recordDeclines(rules,before.get(actor),source,JourneyStatLinks.Reason.FACILITY_EFFECT,actorReasons);
        for (Long target : targets) {
            State state = teams.get(target);
            if(!deferActorGrants || (!target.equals(actor) && !"PARTICIPANTS".equals(scope)))applyFacilityEffects(f,rules,state,trustedEffects,target.equals(actor)?actorReasons:null);
            State rewardBefore=target.equals(actor)?state.copy():null;
            if(!deferActorGrants || (!target.equals(actor) && !"PARTICIPANTS".equals(scope)))inventory.grantAll(theme, state, rules, "facility:" + slot + ":" + count + ":reward", array(f.path("grants")));
            inventory.reconcileHeld(theme, state, rules);
            if(rewardBefore!=null)JourneyStatLinks.recordDeclines(rules,rewardBefore,state,JourneyStatLinks.Reason.FACILITY_EFFECT,actorReasons);
            if(applyLinks) {
                if(target.equals(actor))JourneyStatLinks.apply(rules,before.get(target),state,"TEAM:"+target,eventId,actorReasons);
                else JourneyStatLinks.apply(rules,before.get(target),state,"TEAM:"+target,eventId,JourneyStatLinks.Reason.FACILITY_EFFECT);
            }
        }
        if(!deferActorGrants)consumeFacilityEffects(source, f);
        new TopicRouteEngine().applyEffects(f.path("worldEffects"),world);
        ObjectNode result = JSON.createObjectNode().put("facilityKey", f.path("facilityKey").asText()).put("consumed",true).put("count", count + 1);
        if(paid)result.put("paidByTeamId",actor);
        if(costOption!=null)result.put("costOption",costOption);
        if(noEffect)result.put("noEffect",true);
        result.set("targets", JSON.valueToTree(targets));
        ObjectNode currentSlots = ("WORLD".equals(f.path("scope").asText()) ? world : source).getThemeRuntime().with("cooperation").with("facilities");
        ObjectNode stored = currentSlots.putObject(slot).put("count", count + 1); stored.set("result", result.deepCopy());
        if(!outcomeId.isEmpty())source.getThemeRuntime().with("outcomeAttempts").with(outcomeId).with("receipt").set("facilityResult",result.deepCopy());
        return result;
    }

    private boolean outcomeOnlyNoEffect(JsonNode theme,JourneyRules rules,JsonNode facility,State payer,Iterable<State> targets) {
        if(!theme.path("events").isEmpty() || !theme.path("chapters").isEmpty())return false;
        String active=payer.getThemeRuntime().path("gameplay").path("activeQuest").asText();
        if(!active.isEmpty() && mixedTaskSettlement(theme,payer,new GameplayTaskEngine().quest(theme,active)))return false;
        if(!facility.path("worldEffects").isEmpty() || !facility.path("grants").isEmpty())return false;
        JsonNode trusted=trustedFacilityEffects(theme,facility,payer);
        for(JsonNode effects:Arrays.asList(facility.path("effects"),trusted))if(!effects.isEmpty() && !TemplateEffects.pureRecovery(effects))return false;
        if(facility.path("hpDelta").asInt()<0)return false;
        for(JsonNode change:facility.path("statChanges"))if(change.path("delta").asInt()<=0)return false;
        for(State target:targets)if(recoveryWouldChange(facility,trusted,rules,target))return false;
        return true;
    }
    private boolean mixedTaskSettlement(JsonNode theme,State payer,JsonNode quest) {
        for(String field:Arrays.asList("hpCost","luckCost","actionCost"))if(quest.path(field).asInt()>0)return true;
        for(String field:Arrays.asList("statCosts","resourceCosts","costOptions","grants","choices"))if(!quest.path(field).isEmpty())return true;
        JsonNode pending=payer.getThemeRuntime().path("gameplay").path("pendingTaskEffects").path(quest.path("questKey").asText()).path("effects");
        if(!pending.isEmpty() && !TemplateEffects.pureRecovery(pending))return true;
        return !theme.path("chapters").isEmpty() || !theme.path("events").isEmpty() || !theme.path("gameplayRuntime").path("survival").path("rations").isEmpty();
    }

    public boolean boundFacilityNoEffect(JsonNode theme,JourneyRules rules,State payer,JsonNode facility,JsonNode quest,Iterable<State> targets) {
        return !mixedTaskSettlement(theme,payer,quest) && facilityRecoveryNoEffect(theme,rules,payer,facility,targets);
    }

    public boolean facilityRecoveryNoEffect(JsonNode theme,JourneyRules rules,State payer,JsonNode facility,Iterable<State> targets) {
        if(theme.path("gameplayRuntime").path("survival").path("consumeOnNoEffect").asBoolean() || !pureRecovery(theme,facility,payer))return false;
        JsonNode trustedEffects=trustedFacilityEffects(theme,facility,payer);
        for(State target:targets)if(isEffectiveTarget(theme,target) && recoveryWouldChange(facility,trustedEffects,rules,target))return false;
        return true;
    }

    private ObjectNode boundFacilityCosts(JsonNode theme,JourneyRules rules,State payer,Map<Long,State> owners,JsonNode facility,JsonNode quest,Integer selected) {
        ObjectNode fee=costs(facility,facility.has("costOptions")?selected:null);
        Iterable<State> targets="WORLD".equals(facility.path("targetScope").asText())?owners.values():Collections.singletonList(payer);
        return boundFacilityNoEffect(theme,rules,payer,facility,quest,targets)?JSON.createObjectNode():fee;
    }

    private boolean pureRecovery(JsonNode theme,JsonNode facility,State payer) {
        if(!facility.path("worldEffects").isEmpty() || !facility.path("grants").isEmpty())return false;
        JsonNode trustedEffects=trustedFacilityEffects(theme,facility,payer);
        for(JsonNode effects:Arrays.asList(facility.path("effects"),trustedEffects))if(!effects.isEmpty() && !TemplateEffects.pureRecovery(effects))return false;
        boolean positive=facility.path("hpDelta").asInt()>0;
        if(facility.has("hpDelta") && facility.path("hpDelta").asInt()<0)return false;
        for(JsonNode change:facility.path("statChanges")) {
            if(change.path("delta").asInt()<=0)return false;
            positive=true;
        }
        return positive || TemplateEffects.pureRecovery(facility.path("effects")) || TemplateEffects.pureRecovery(trustedEffects);
    }

    private boolean recoveryWouldChange(JsonNode facility,JsonNode trustedEffects,JourneyRules rules,State state) {
        State preview=state.copy();
        if(facility.path("hpDelta").asInt()>0 && inventory.changeStat(preview,rules,"sys.hp",facility.path("hpDelta").asInt())>0)return true;
        for(JsonNode change:facility.path("statChanges"))
            if(inventory.changeStat(preview,rules,change.path("statKey").asText(),change.path("delta").asInt())>0)return true;
        TemplateEffects.apply(facility.path("effects"),rules,preview);TemplateEffects.apply(trustedEffects,rules,preview);
        for(Map.Entry<String,Number> value:preview.getVariables().entrySet())if(value.getValue().doubleValue()>state.getVariables().getOrDefault(value.getKey(),0).doubleValue())return true;
        return false;
    }

    private ObjectNode deliberate(JsonNode theme, JourneyRules rules, State world, Map<Long, State> teams,
                                   Map<Long, Long> leaders, Long actor, Long member, String action, ObjectNode p, String eventId,long now,JsonNode frozen) {
        ObjectNode c = world.getThemeRuntime().with("cooperation"), proposals = c.with("proposals");
        switch (action) {
            case "PLAN_REJECT_ALL": {
                fields(p); requireMutable(world);
                if ("SELECTED".equals(c.path("stage").asText()) || "CONSENSUS".equals(c.path("stage").asText())) throw new IllegalStateException("请先改案解除已通过方案");
                ObjectNode rejection = c.with("rejections").putObject(String.valueOf(actor)).put("memberId", member);
                rejection.set("scope", rejectionScope(theme, c)); return JSON.createObjectNode().put("rejectedAll", true);
            }
            case "PLAN_PROPOSE": {
                fields(p, "proposalKey", "endingKey"); requireMutable(world);
                String id = key(p, "proposalKey"); if (proposals.has(id) || proposals.size() >= 100) throw new IllegalStateException("提案标识已用或容量已满");
                JsonNode plan = configured(theme, "plans", "endingKey", key(p, "endingKey"));
                if(planAlreadyExecuted(theme,c,plan))throw new IllegalStateException("该共同操作已经执行");
                if (!inventory.conditions(plan.path("when"), world)) throw new IllegalStateException("方案公共前置未满足");
                proposals.putObject(id).put("proposalKey", id).put("endingKey", plan.path("endingKey").asText()).put("teamId", actor).put("memberId", member).put("status", "OPEN").put("version", 1);
                return publicProposal(proposals.get(id));
            }
            case "PLAN_INTENT": {
                fields(p, "proposalKey", "version", "amounts"); requireMutable(world);
                ObjectNode row = row(proposals, key(p, "proposalKey")); requireVersion(row, p); requireOpen(row);
                ArrayNode quantities = amounts(theme, p.path("amounts"));
                requireAvailable(world, actor, teams.get(actor), quantities);
                JsonNode plan = configured(theme, "plans", "endingKey", row.path("endingKey").asText());
                Map<String, Integer> total = new LinkedHashMap<>();
                row.path("intents").fields().forEachRemaining(e -> { if (!e.getKey().equals(String.valueOf(actor))) add(total, e.getValue().path("amounts")); });
                add(total, quantities); Map<String, Integer> needed = amountMap(plan.path("materialCost"));
                for (Map.Entry<String, Integer> e : total.entrySet()) if (e.getValue() > needed.getOrDefault(e.getKey(), 0)) throw new IllegalArgumentException("出资意向不能超过方案需求");
                ObjectNode intent = row.with("intents").putObject(String.valueOf(actor)).put("memberId", member); intent.set("amounts", quantities);
                return publicProposal(row);
            }
            case "PLAN_WITHDRAW": {
                fields(p, "proposalKey", "version"); ObjectNode row = row(proposals, key(p, "proposalKey")); requireVersion(row, p);
                if (row.path("teamId").asLong() != actor) throw new IllegalStateException("只能撤回本队提出的方案");
                row.put("status", "WITHDRAWN"); reset(c); return publicProposal(row);
            }
            case "PLAN_CHANGE": fields(p); reset(c); return deliberationView(theme, c);
            case "VOTE_OPEN":
            case "VOTE_NEXT": {
                fields(p); if ("SELECTED".equals(c.path("stage").asText())) throw new IllegalStateException("请先改案解除旧同意");
                if ("VOTE_NEXT".equals(action) && !"TIED".equals(c.path("stage").asText())) throw new IllegalStateException("本轮尚未完成或不需要重投");
                if ("VOTE_OPEN".equals(action) && isVotingFrozen(world)) throw new IllegalStateException("票轮已经开始");
                if (!Arrays.asList("MAJORITY", "UNANIMOUS", "MAJORITY_ACCEPTANCE").contains(theme.path("gameplayRuntime").path("votePolicy").asText())) throw new IllegalStateException("作者须明确选择表决策略");
                ArrayNode candidates = JSON.createArrayNode();
                for (JsonNode row : proposals) if ("OPEN".equals(row.path("status").asText()) && funded(theme, row, world, teams, leaders)) candidates.add(row.path("proposalKey").asText());
                if (candidates.isEmpty()) throw new IllegalStateException("尚无前置满足且出资意向足额的方案");
                for(State state:teams.values())if(state.getThemeRuntime().path("gameplay").has("activeQuest"))for(JsonNode candidate:candidates) {
                    JsonNode plan=configured(theme,"plans","endingKey",proposals.path(candidate.asText()).path("endingKey").asText());
                    requirePlanQuest(theme,state,plan);
                }
                c.put("round", c.path("round").asInt() + 1).put("stage", "VOTING"); c.set("candidates", candidates);
                c.remove(Arrays.asList("votes", "acceptances", "consensus", "selected", "locks"));
                c.set("voters", JSON.valueToTree(teams.keySet())); c.putObject("ballotBalances");
                teams.forEach((id, state) -> c.with("ballotBalances").set(String.valueOf(id), balance(state)));
                return deliberationView(theme, c);
            }
            case "VOTE_CAST": {
                fields(p, "round", "proposalKey"); requireRound(c, p, "VOTING"); requireFrozenBalances(c, teams);
                String candidate = p.path("proposalKey").asText(); requireCandidate(c, candidate, true);
                sign(c.with("votes"), actor, member, candidate); choose(theme, c, teams, leaders, "votes", false);
                return deliberationView(theme, c);
            }
            case "ACCEPT_SET": {
                fields(p, "round", "proposalKeys");
                if (!"MAJORITY_ACCEPTANCE".equals(theme.path("gameplayRuntime").path("votePolicy").asText())) throw new IllegalStateException("主题未启用接受集合恢复");
                if ("TIED".equals(c.path("stage").asText())) c.put("stage", "ACCEPTING");
                requireRound(c, p, "ACCEPTING"); requireFrozenBalances(c, teams);
                if (!p.path("proposalKeys").isArray()) throw new IllegalArgumentException("接受集合须为数组");
                Set<String> seen = new LinkedHashSet<>();
                for (JsonNode id : p.path("proposalKeys")) { requireCandidate(c, id.asText(), false); if (!id.isTextual() || !seen.add(id.asText())) throw new IllegalArgumentException("接受项重复或无效"); }
                ObjectNode row = c.with("acceptances").putObject(String.valueOf(actor)).put("memberId", member); row.set("choices", JSON.valueToTree(seen));
                choose(theme, c, teams, leaders, "acceptances", true); return deliberationView(theme, c);
            }
            case "CONSENSUS_CAST": {
                fields(p, "round", "proposalKey"); requireRound(c, p, "CONSENSUS"); requireFrozenBalances(c, teams);
                String candidate = p.path("proposalKey").asText(); if (!contains(c.path("tiedCandidates"), candidate)) throw new IllegalArgumentException("共识只能选择并列可执行案");
                sign(c.with("consensus"), actor, member, candidate);
                boolean all = true; for (Long team : teams.keySet()) if (!currentSignature(c.path("consensus"), leaders, team) || !candidate.equals(c.path("consensus").path(String.valueOf(team)).path("choice").asText())) all = false;
                if (all) select(c, candidate, "consensus"); return deliberationView(theme, c);
            }
            case "PLAN_CONFIRM": {
                fields(p, "round", "proposalKey"); requireRound(c, p, "SELECTED");
                String id = key(p, "proposalKey"); if (!id.equals(c.path("selected").asText())) throw new IllegalStateException("获选方案已变更");
                requireSelectedSignatures(c, teams, leaders);
                ObjectNode proposal = row(proposals, id); JsonNode own = proposal.path("intents").path(String.valueOf(actor));
                if (!own.isObject() || own.path("amounts").isEmpty()) throw new IllegalStateException("本队没有此案出资意向");
                if (own.path("memberId").asLong() != member) throw new IllegalStateException("队长已变更，请改案重新表达意向");
                c.with("locks").remove(String.valueOf(actor)); requireAvailable(world, actor, teams.get(actor), own.path("amounts"));
                ObjectNode lock = c.with("locks").putObject(String.valueOf(actor)).put("memberId", member).put("proposalKey", id); lock.set("amounts", own.path("amounts").deepCopy());
                return deliberationView(theme, c);
            }
            case "PLAN_EXECUTE": {
                fields(p, "round", "proposalKey"); requireRound(c, p, "SELECTED");
                String id = key(p, "proposalKey"); if (!id.equals(c.path("selected").asText())) throw new IllegalStateException("方案已经变更");
                requireSelectedSignatures(c, teams, leaders);
                ObjectNode proposal = row(proposals, id); JsonNode plan = configured(theme, "plans", "endingKey", proposal.path("endingKey").asText());
                if(planAlreadyExecuted(theme,c,plan))throw new IllegalStateException("该共同操作已经执行");
                if (!inventory.conditions(plan.path("when"), world)) throw new IllegalStateException("公共执行前置不满足");
                if (!trusted(teams.get(actor).getThemeRuntime().path("verifiedNodes").path(String.valueOf(plan.path("operationNodeId").asLong())))) throw new IllegalStateException("方案操作尚未通过可信题组");
                Map<String, Integer> pledged = new LinkedHashMap<>();
                ObjectNode contributions = JSON.createObjectNode();
                Iterator<Map.Entry<String, JsonNode>> intents = proposal.path("intents").fields();
                while (intents.hasNext()) {
                    Map.Entry<String, JsonNode> intent = intents.next(); Long team = Long.valueOf(intent.getKey());
                    if (intent.getValue().path("amounts").isEmpty()) continue;
                    JsonNode lock = c.path("locks").path(intent.getKey());
                    if (!teams.containsKey(team) || !currentSignature(c.path("locks"), leaders, team) || !id.equals(lock.path("proposalKey").asText()) || !lock.path("amounts").equals(intent.getValue().path("amounts"))) throw new IllegalStateException("每个出资队必须由当前队长重新确认本案");
                    add(pledged, lock.path("amounts"));
                    contributions.set(intent.getKey(), lock.deepCopy());
                }
                if (!pledged.equals(amountMap(plan.path("materialCost")))) throw new IllegalStateException("已确认出资尚未足额");
                if(c.path("locks").size()!=contributions.size())throw new IllegalStateException("托管来源与本案确认集合不一致，请改案复核");
                JsonNode block=frozen==null?JSON.missingNode():frozen.path("nodeConfigs").path(plan.path("operationNodeId").asText());
                boolean r5=block.path("outcomeRules").path("version").asInt()==1;
                Map<Long,String> attempts=new LinkedHashMap<>();String useKey="plan:"+plan.path("operationNodeId").asText();
                if(r5)for(Map.Entry<Long,State> entry:teams.entrySet()) {
                    JsonNode proof=entry.getValue().getThemeRuntime().path("operationFacts").path("PLAN:"+plan.path("endingKey").asText());String attempt=proof.path("outcomeAttemptId").asText();
                    JsonNode decision=entry.getValue().getThemeRuntime().path("outcomeAttempts").path(attempt).path("decision");
                    if(attempt.isEmpty() || proof.path("round").asInt(-1)!=c.path("round").asInt() || decision.path("nodeId").asLong()!=plan.path("operationNodeId").asLong() || !"PASS".equals(decision.path("outcome").asText()))throw new IllegalStateException("每个执行队必须有自己的真实 PASS");
                    State useOwner="WORLD".equals(block.path("interactionRules").path("scope").asText())?world:entry.getValue();
                    if(useOwner.getThemeRuntime().path("cooperation").path("facilities").path(useKey).path("count").asInt()>=block.path("interactionRules").path("limit").asInt(1))throw new IllegalStateException("方案成功次数已用完");
                    State probe=entry.getValue().copy();ObjectNode outcome=new GameplayOutcomeEngine().settleRemembered(theme,rules,probe,Collections.singletonMap(entry.getKey(),probe),attempt,now,false);
                    if(!outcome.path("settled").asBoolean())throw new IllegalStateException("每个执行队必须能全额支付 PASS 费用");attempts.put(entry.getKey(),attempt);
                }
                State contributionWorld=world.copy();contributionWorld.getThemeRuntime().with("cooperation").remove("locks");
                Iterator<Map.Entry<String,JsonNode>> precheck=contributions.fields();while(precheck.hasNext()){Map.Entry<String,JsonNode> source=precheck.next();requireAvailable(contributionWorld,Long.valueOf(source.getKey()),teams.get(Long.valueOf(source.getKey())),source.getValue().path("amounts"));}
                Map<Long,State> before=new LinkedHashMap<>();teams.forEach((team,state)->before.put(team,state.copy()));
                if(!plan.path("terminal").asBoolean(true) || plan.has("storyEndingId"))for(State state:teams.values())requirePlanQuest(theme,state,plan);
                c.remove("locks");
                Iterator<Map.Entry<String, JsonNode>> sources = contributions.fields();
                while (sources.hasNext()) {
                    Map.Entry<String, JsonNode> source = sources.next(); Long team = Long.valueOf(source.getKey());
                    requireAvailable(world, team, teams.get(team), source.getValue().path("amounts"));
                    inventory.consumeAll(theme, teams.get(team), rules, "final:" + id, source.getValue().path("amounts"));
                }
                if(r5) {
                    for(Map.Entry<Long,String> attempt:attempts.entrySet()) {
                        State state=teams.get(attempt.getKey());new GameplayOutcomeEngine().settleRemembered(theme,rules,state,Collections.singletonMap(attempt.getKey(),state),attempt.getValue(),now,false);
                        String active=state.getThemeRuntime().path("gameplay").path("activeQuest").asText();
                        if(active.isEmpty())TemplateEffects.apply(state.getThemeRuntime().path("operationFacts").path("PLAN:"+plan.path("endingKey").asText()).path("effects"),rules,state);
                    }
                    if("WORLD".equals(block.path("interactionRules").path("scope").asText()))world.getThemeRuntime().with("cooperation").with("facilities").with(useKey).put("count",world.getThemeRuntime().path("cooperation").path("facilities").path(useKey).path("count").asInt()+1);
                    else for(State state:teams.values())state.getThemeRuntime().with("cooperation").with("facilities").with(useKey).put("count",state.getThemeRuntime().path("cooperation").path("facilities").path(useKey).path("count").asInt()+1);
                }
                if(r5 || !plan.path("terminal").asBoolean(true) || plan.has("storyEndingId"))for(Map.Entry<Long,State> entry:teams.entrySet()) {
                    Long team=entry.getKey();State state=entry.getValue();Map<String,Set<JourneyStatLinks.Reason>> reasons=new LinkedHashMap<>();
                    JourneyStatLinks.recordDeclines(rules,before.get(team),state,JourneyStatLinks.Reason.TEMPLATE_EFFECT,reasons);
                    String active=state.getThemeRuntime().path("gameplay").path("activeQuest").asText("");
                    boolean finalChapterPlan=plan.has("storyEndingId") && plan.path("storyEndingId").asText().equals(plan.path("endingKey").asText());
                    if(finalChapterPlan && active.isEmpty() && isEffectiveTarget(theme,state) && !"COMPLETED".equals(state.getStatus())) {
                        // 最终章收场：各有效队伍的本节点任务随共同执行一并完成（免费表决任务），其余完成要求照常计算
                        for(JsonNode q:theme.path("gameplayRuntime").path("quests"))if(q.path("nodeId").asLong()==plan.path("operationNodeId").asLong() && !state.getThemeRuntime().path("gameplay").path("quests").has(q.path("questKey").asText())) {
                            // 开始任务会清空本节点题组证明；本队已在方案操作中取得的证明（含 R5 PASS）须保留
                            JsonNode earned=state.getThemeRuntime().path("verifiedNodes").path(plan.path("operationNodeId").asText());
                            try {new GameplayTaskEngine().execute(theme,rules,state,q.path("questKey").asText(),"QUEST_START",null,eventId+":start:"+team,"TEAM:"+team,now);}
                            catch(IllegalArgumentException|IllegalStateException notReady){throw new IllegalStateException("还有队伍未进入最终章或未完成本章其他要求，暂不能执行收场方案");}
                            if(!earned.isMissingNode())state.getThemeRuntime().with("verifiedNodes").set(plan.path("operationNodeId").asText(),earned.deepCopy());
                            active=q.path("questKey").asText();
                        }
                    }
                    if(!active.isEmpty()) {
                        // 旧投票沿表决完成；R5 只消费本队已冻结的 PASS。
                        if(!r5)state.getThemeRuntime().with("verifiedNodes").put(plan.path("operationNodeId").asText(),now);
                        new GameplayTaskEngine().execute(theme,rules,state,active,"QUEST_RESOLVE",null,eventId+":task:"+team,"TEAM:"+team,now,reasons,false);
                    }
                    JourneyStatLinks.apply(rules,before.get(team),state,"TEAM:"+team,eventId,reasons);
                    if(finalChapterPlan && isEffectiveTarget(theme,before.get(team)) && !"COMPLETED".equals(state.getStatus()))
                        throw new IllegalStateException("最终章还有其他未完成的要求，暂不能执行收场方案");
                }
                ObjectNode result = JSON.createObjectNode().put("terminal",plan.path("terminal").asBoolean(true)).put("endingKey", plan.path("endingKey").asText()).put("title", plan.path("title").asText()).put("summary", plan.path("summary").asText()).put("proposalKey", id).put("eventId", eventId).put("round", c.path("round").asInt());
                if(plan.has("storyEndingId")) {
                    JsonNode ending=null;for(JsonNode candidate:theme.path("gameplayRuntime").path("storyEndings"))if(plan.path("storyEndingId").asText().equals(candidate.path("code").asText()))ending=candidate;
                    if(ending==null)throw new IllegalStateException("终局方案缺少冻结剧情结局");
                    for(State state:teams.values())inventory.terminalStory(theme,state,rules,ending,now);
                    result.put("storyEndingId",ending.path("code").asText()).put("endingChapterId",ending.path("chapterId").asLong())
                            .put("title",ending.path("title").asText()).put("summary",ending.path("summary").asText());
                }
                result.set("contributions", contributions);
                if(plan.path("terminal").asBoolean(true)){c.set("result",result);c.put("stage","EXECUTED");}
                else {c.with("executedPlans").set(plan.path("endingKey").asText(),result.deepCopy());c.set("lastOperationResult",result.deepCopy());proposal.put("status","EXECUTED");reset(c);}
                return publicResult(result);
            }
            default: throw new IllegalArgumentException("不支持协作动作");
        }
    }

    private boolean planAlreadyExecuted(JsonNode theme,JsonNode cooperation,JsonNode plan) {
        JsonNode executed=cooperation.path("executedPlans");if(executed.has(plan.path("endingKey").asText()))return true;
        for(JsonNode node:theme.path("gameplayRuntime").path("templateTools").path("nodes"))
            if(node.path("nodeId").asLong()==plan.path("operationNodeId").asLong())for(JsonNode key:node.path("planKeys"))if(executed.has(key.asText()))return true;
        return false;
    }

    private void requirePlanQuest(JsonNode theme,State state,JsonNode plan) {
        String active=state.getThemeRuntime().path("gameplay").path("activeQuest").asText("");if(active.isEmpty())return;
        GameplayTaskEngine tasks=new GameplayTaskEngine();JsonNode q=tasks.quest(theme,active);
        if(plan.path("terminal").asBoolean(true) && !plan.has("storyEndingId") || q.path("nodeId").asLong()!=plan.path("operationNodeId").asLong()
                || q.has("facilityKey") || q.has("costOptions") || tasks.costs(q).path("hpCost").asInt()!=0 || tasks.costs(q).path("luckCost").asInt()!=0
                || tasks.costs(q).path("actionCost").asInt()!=0 || q.path("statCosts").size()!=0 || q.path("resourceCosts").size()!=0
                || q.path("grants").size()!=0 || q.path("choices").size()!=0)throw new IllegalStateException("请先完成或取消其他任务再表决");
    }

    private void choose(JsonNode theme, ObjectNode c, Map<Long, State> teams, Map<Long, Long> leaders, String field, boolean acceptance) {
        for (Long team : teams.keySet()) if (!currentSignature(c.path(field), leaders, team)) return;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode id : c.path("candidates")) counts.put(id.asText(), 0);
        for (Long team : teams.keySet()) {
            JsonNode vote = c.path(field).path(String.valueOf(team));
            if (acceptance) for (JsonNode id : vote.path("choices")) counts.put(id.asText(), counts.get(id.asText()) + 1);
            else { String id = vote.path("choice").asText(); if (counts.containsKey(id)) counts.put(id, counts.get(id) + 1); }
        }
        int threshold = "UNANIMOUS".equals(theme.path("gameplayRuntime").path("votePolicy").asText()) || teams.size() <= 2 ? teams.size() : teams.size() / 2 + 1;
        int best = threshold - 1; ArrayNode ties = JSON.createArrayNode();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) if (entry.getValue() >= threshold) {
            if (entry.getValue() > best) { best = entry.getValue(); ties.removeAll(); }
            if (entry.getValue() == best) ties.add(entry.getKey());
        }
        if (ties.size() == 1) select(c, ties.get(0).asText(), field);
        else if (ties.size() > 1) { c.put("stage", "CONSENSUS"); c.set("tiedCandidates", ties); c.remove("consensus"); }
        else c.put("stage", "TIED");
    }

    private boolean funded(JsonNode theme, JsonNode proposal, State world, Map<Long, State> teams, Map<Long, Long> leaders) {
        JsonNode plan=configured(theme,"plans","endingKey",proposal.path("endingKey").asText());
        if(planAlreadyExecuted(theme,world.getThemeRuntime().path("cooperation"),plan) || !inventory.conditions(plan.path("when"),world))return false;
        Map<String, Integer> total = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> intents = proposal.path("intents").fields();
        while (intents.hasNext()) {
            Map.Entry<String, JsonNode> entry = intents.next(); Long team = Long.valueOf(entry.getKey());
            if (!teams.containsKey(team) || !currentSignature(proposal.path("intents"), leaders, team)) return false;
            try{requireAvailable(world,team,teams.get(team),entry.getValue().path("amounts"));}catch(IllegalStateException unavailable){return false;}
            add(total, entry.getValue().path("amounts"));
        }
        return total.equals(amountMap(configured(theme, "plans", "endingKey", proposal.path("endingKey").asText()).path("materialCost")));
    }
    private void select(ObjectNode c, String candidate, String source) { c.put("stage", "SELECTED").put("selected", candidate).put("selectedBy",source); c.remove("locks"); }
    private void requireSelectedSignatures(JsonNode c,Map<Long,State> teams,Map<Long,Long> leaders) {
        for(Long team:teams.keySet())if(!currentSignature(c.path(c.path("selectedBy").asText()),leaders,team))throw new IllegalStateException("队长已变更，请改案重新表决");
    }
    private void reset(ObjectNode c) {
        c.put("stage", "DRAFT"); c.put("round", c.path("round").asInt() + 1);
        c.remove(Arrays.asList("selected", "selectedBy", "locks", "votes", "acceptances", "consensus", "candidates", "tiedCandidates", "ballotBalances", "voters"));
    }
    public void exit(State world, Long teamId) {
        ObjectNode c = world.getThemeRuntime().with("cooperation");
        for (JsonNode trade : c.with("trades")) if (!"COMPLETED".equals(trade.path("status").asText()) && (trade.path("fromTeamId").asLong() == teamId || trade.path("toTeamId").asLong() == teamId)) ((ObjectNode) trade).put("status", "CANCELLED").remove("signatures");
        if (!c.has("result")) {
            for (JsonNode proposal : c.with("proposals")) {
                ((ObjectNode) proposal).with("intents").remove(String.valueOf(teamId));
                if (proposal.path("teamId").asLong() == teamId) ((ObjectNode) proposal).put("status", "WITHDRAWN");
            }
            reset(c);
        }
    }

    public ObjectNode view(JsonNode theme, State world, Map<Long, State> teams, Long viewerTeamId) {
        return view(theme, world, teams, viewerTeamId, Collections.emptyMap());
    }

    public ObjectNode view(JsonNode theme, State world, Map<Long, State> teams, Long viewerTeamId, Map<Long, Long> leaders) {
        JsonNode c = world.getThemeRuntime().path("cooperation"); ObjectNode out = deliberationView(theme, c);
        String fallbackReason = fallbackDisabledReason(theme, world, teams, leaders);
        out.put("fallbackAvailable", fallbackReason == null);
        if (fallbackReason == null) out.putNull("fallbackDisabledReason"); else out.put("fallbackDisabledReason", fallbackReason);
        if (viewerTeamId != null && teams.containsKey(viewerTeamId)) out.put("ownRejectedAll", currentSignature(c.path("rejections"), leaders, viewerTeamId)
                && rejectionScope(theme, c).equals(c.path("rejections").path(String.valueOf(viewerTeamId)).path("scope")));
        out.set("itemCatalog",itemCatalog(theme));
        voteView(out, theme, world, teams, viewerTeamId, leaders);
        if (viewerTeamId != null && teams.containsKey(viewerTeamId)) for (JsonNode proposal : out.path("proposals")) {
            String key = proposal.path("proposalKey").asText();
            JsonNode source = c.path("proposals").path(key), intent = source.path("intents").path(String.valueOf(viewerTeamId));
            if (intent.isObject()) {
                ObjectNode own = ((ObjectNode)proposal).putObject("ownIntent");
                own.set("amounts", intent.path("amounts").deepCopy());
                own.put("signatureCurrent", currentSignature(source.path("intents"), leaders, viewerTeamId));
            }
            JsonNode lock = c.path("locks").path(String.valueOf(viewerTeamId));
            ((ObjectNode)proposal).put("ownConfirmed", key.equals(lock.path("proposalKey").asText()) && currentSignature(c.path("locks"), leaders, viewerTeamId));
        }
        if(c.has("lastOperationResult"))out.set("lastOperationResult",publicResult(c.path("lastOperationResult")));
        ObjectNode facts=out.putObject("publicFacts");
        for(JsonNode facility:theme.path("gameplayRuntime").path("facilities"))for(JsonNode effect:facility.path("worldEffects")) {String key=effect.path("var").asText();if(key.startsWith("clue.") && world.getVariables().containsKey(key))facts.set(key,JSON.valueToTree(world.getVariables().get(key)));}
        ArrayNode trades = out.putArray("trades");
        for (JsonNode trade : c.path("trades")) if (viewerTeamId != null && (trade.path("fromTeamId").asLong() == viewerTeamId || trade.path("toTeamId").asLong() == viewerTeamId)) {
            ObjectNode row = publicTrade(trade);
            long counterpart = trade.path("fromTeamId").asLong() == viewerTeamId ? trade.path("toTeamId").asLong() : trade.path("fromTeamId").asLong();
            row.setAll(tradeAccess(theme, teams.get(viewerTeamId), teams.get(counterpart)));
            row.put("ownSigned", currentSignature(trade.path("signatures"), leaders, viewerTeamId));
            ArrayNode confirmed = row.putArray("confirmedTeams");
            for (Long id : teams.keySet()) if (currentSignature(trade.path("signatures"), leaders, id)) confirmed.add(id);
            tradeViewState(row, theme, world, teams.get(viewerTeamId), "OPEN".equals(trade.path("status").asText()) ? 0 : 1, 1, teams.containsKey(counterpart) && !isEffectiveTarget(theme,teams.get(counterpart)));
            trades.add(row);
        }
        ArrayNode targets = out.putArray("tradeTargets");
        if (viewerTeamId != null && teams.containsKey(viewerTeamId)) teams.forEach((id, state) -> {
            if (!id.equals(viewerTeamId)) {
                boolean completed=!isEffectiveTarget(theme,state);
                ObjectNode target = tradeAccess(theme, teams.get(viewerTeamId), state).put("teamId", id).put("completed",completed);
                tradeViewState(target, theme, world, teams.get(viewerTeamId), c.path("trades").size(), 200, completed);
                targets.add(target);
            }
        });
        out.set("facilities", c.path("facilities").isObject() ? c.path("facilities").deepCopy() : JSON.createObjectNode());
        if (viewerTeamId != null && teams.containsKey(viewerTeamId)) out.set("locked", JSON.valueToTree(locked(world, viewerTeamId)));
        return out;
    }

    private void voteView(ObjectNode out, JsonNode theme, State world, Map<Long, State> teams,
                          Long viewer, Map<Long, Long> leaders) {
        JsonNode cooperation = world.getThemeRuntime().path("cooperation");
        String stage = cooperation.path("stage").asText("DRAFT");
        out.put("votePolicy", theme.path("gameplayRuntime").path("votePolicy").asText());
        out.put("votePhase", cooperation.path("result").path("fallback").asBoolean() ? "FALLBACK" : "DRAFT".equals(stage) ? "FUNDING" : "ACCEPTING".equals(stage) ? "ACCEPTANCE" : stage);
        out.putNull("ownVote"); out.putNull("ownAcceptance");
        State owner = viewer == null ? null : teams.get(viewer);
        if (owner != null) {
            JsonNode vote = cooperation.path("votes").path(String.valueOf(viewer));
            if (vote.isObject()) out.putObject("ownVote").put("planKey", vote.path("choice").asText())
                    .put("ownSignatureCurrent", currentSignature(cooperation.path("votes"), leaders, viewer));
            JsonNode acceptance = cooperation.path("acceptances").path(String.valueOf(viewer));
            if (acceptance.isObject()) {
                ObjectNode own = out.putObject("ownAcceptance");
                own.set("planKeys", acceptance.path("choices").deepCopy());
                own.put("ownSignatureCurrent", currentSignature(cooperation.path("acceptances"), leaders, viewer));
            }
        }
        ArrayNode materials = out.putArray("ownMaterials");
        Map<String, Integer> reserved = owner == null ? Collections.emptyMap() : locked(world, viewer);
        if (owner != null) for (JsonNode item : itemCatalog(theme)) {
            String id = item.path("id").asText(); int held = owner.getThemeRuntime().path("inventory").path(id).asInt();
            int locked = reserved.getOrDefault(id, 0);
            materials.addObject().put("itemId", id).put("available", Math.max(0, held - locked)).put("locked", locked);
        }
        ArrayNode plans = out.putArray("plans");
        for (JsonNode plan : theme.path("gameplayRuntime").path("plans")) {
            ObjectNode row = plans.addObject();
            for (String field : Arrays.asList("endingKey", "title", "summary", "materialCost", "operationNodeId", "terminal"))
                if (plan.has(field)) row.set(field, plan.get(field).deepCopy());
            boolean conditions = inventory.conditions(plan.path("when"), world);
            row.put("conditionsMet", conditions);
            JsonNode proposal = null;
            for (JsonNode candidate : cooperation.path("proposals")) if (plan.path("endingKey").equals(candidate.path("endingKey"))
                    && "OPEN".equals(candidate.path("status").asText())) {
                proposal = candidate;
                if (candidate.path("proposalKey").asText().equals(cooperation.path("selected").asText())) break;
            }
            String key = proposal == null ? "" : proposal.path("proposalKey").asText();
            if (proposal != null) row.put("proposalKey", key).put("version", proposal.path("version").asInt());
            boolean live = owner != null && isEffectiveTarget(theme, owner) && !cooperation.has("result")
                    && !planAlreadyExecuted(theme, cooperation, plan) && conditions;
            boolean balances = true;
            if (isVotingFrozen(world)) for (Map.Entry<Long, State> team : teams.entrySet())
                if (!balance(team.getValue()).equals(cooperation.path("ballotBalances").path(String.valueOf(team.getKey())))) balances = false;
            boolean selected = live && proposal != null && "SELECTED".equals(stage) && key.equals(cooperation.path("selected").asText());
            boolean confirmed = selected;
            if (selected) {
                try { requireSelectedSignatures(cooperation, teams, leaders); }
                catch (IllegalStateException changed) { confirmed = false; }
                Map<String, Integer> pledged = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> intents = proposal.path("intents").fields();
                while (intents.hasNext()) {
                    Map.Entry<String, JsonNode> entry = intents.next();
                    if (entry.getValue().path("amounts").isEmpty()) continue;
                    Long team = Long.valueOf(entry.getKey()); JsonNode lock = cooperation.path("locks").path(entry.getKey());
                    if (!teams.containsKey(team) || !currentSignature(cooperation.path("locks"), leaders, team)
                            || !key.equals(lock.path("proposalKey").asText()) || !lock.path("amounts").equals(entry.getValue().path("amounts"))) confirmed = false;
                    add(pledged, lock.path("amounts"));
                }
                confirmed = confirmed && pledged.equals(amountMap(plan.path("materialCost")));
            }
            JsonNode proof = owner == null ? JSON.createObjectNode() : owner.getThemeRuntime().path("operationFacts").path("PLAN:" + plan.path("endingKey").asText());
            confirmed = confirmed && proof.isObject() && proof.path("round").asInt(-1) == cooperation.path("round").asInt()
                    && trusted(owner.getThemeRuntime().path("verifiedNodes").path(plan.path("operationNodeId").asText()));
            ObjectNode actions = row.putObject("actions");
            actions.put("canFund", live && "DRAFT".equals(stage));
            actions.put("canVote", live && balances && ("VOTING".equals(stage) && contains(cooperation.path("candidates"), key)
                    || "CONSENSUS".equals(stage) && contains(cooperation.path("tiedCandidates"), key)));
            actions.put("canAccept", live && balances && Arrays.asList("TIED", "ACCEPTING").contains(stage)
                    && "MAJORITY_ACCEPTANCE".equals(out.path("votePolicy").asText()) && contains(cooperation.path("candidates"), key));
            actions.put("canExecute", confirmed);
            if (actions.path("canFund").asBoolean() || actions.path("canVote").asBoolean() || actions.path("canAccept").asBoolean() || confirmed) actions.putNull("disabledReason");
            else actions.put("disabledReason", cooperation.has("result") ? "SESSION_ENDED" : owner != null && !isEffectiveTarget(theme, owner) ? "TEAM_COMPLETED" : "RULE_BLOCKED");
        }
    }

    public static ArrayNode itemCatalog(JsonNode theme) {
        ArrayNode catalog=JSON.createArrayNode();
        for(JsonNode item:theme.path("items"))if("ITEM".equals(item.path("kind").asText()) && !item.path("wear").asBoolean())
            catalog.addObject().put("id",item.path("id").asText()).put("name",item.path("name").asText()).put("slotCost",item.path("slotCost").asInt(1));
        return catalog;
    }

    private void tradeViewState(ObjectNode row, JsonNode theme, State world, State viewer, int count, int limit, boolean counterpartCompleted) {
        GameplayToolState.Reason reason = null;
        if (world.getThemeRuntime().path("cooperation").has("result")) reason = GameplayToolState.Reason.SESSION_ENDED;
        else if (!isEffectiveTarget(theme, viewer)) reason = GameplayToolState.Reason.TEAM_COMPLETED;
        else if (counterpartCompleted) reason = GameplayToolState.Reason.COUNTERPART_COMPLETED;
        else if (isVotingFrozen(world)) reason = GameplayToolState.Reason.WORLD_LOCKED;
        else if (count >= limit) reason = GameplayToolState.Reason.LIMIT_REACHED;
        else if (row.path("disabled").asBoolean()) {
            String message = row.path("disabledReason").asText();
            row.put("disabledMessage", message);
            reason = GameplayToolState.Reason.valueOf(row.path("disabledReasonCode").asText());
        }
        row.remove("disabledReasonCode");
        GameplayToolState.usage(row, limit, count, "WORLD");
        GameplayToolState.availability(row, reason, false);
        row.put("disabled", reason != null);
    }
    private void requireTradeAccess(JsonNode theme, State actor, State counterpart) {
        ObjectNode access = tradeAccess(theme, actor, counterpart);
        if (access.path("disabled").asBoolean()) throw new IllegalStateException(access.path("disabledReason").asText());
    }

    private ObjectNode tradeAccess(JsonNode theme, State actor, State counterpart) {
        ObjectNode access = JSON.createObjectNode().put("disabled", true);
        if (actor == null || counterpart == null) return access.put("disabledReasonCode", "RULE_BLOCKED").put("disabledReason", "交易队伍已退出");
        if (!isEffectiveTarget(theme, actor) || !isEffectiveTarget(theme, counterpart)) return access.put("disabledReasonCode", "TEAM_COMPLETED").put("disabledReason", "交易队伍已完成最后章节");
        if (!theme.path("gameplayRuntime").path("allowTrade").asBoolean()) return access.put("disabledReasonCode", "RULE_BLOCKED").put("disabledReason", "主题未开放队间转移");
        boolean nodeTool = false, actorArrived = false;
        for (JsonNode node : theme.path("gameplayRuntime").path("templateTools").path("nodes")) {
            if (!node.path("allowTrade").asBoolean()) continue;
            nodeTool = true;
            long nodeId = node.path("nodeId").asLong();
            if (!arrived(actor, nodeId)) continue;
            actorArrived = true;
            if (arrived(counterpart, nodeId)) return access.put("disabled", false).put("nodeId", nodeId);
        }
        if (!nodeTool) return access.put("disabled", false);
        return access.put("disabledReasonCode", "NOT_ARRIVED").put("disabledReason", actorArrived ? "对方尚未到达同一可交易站点" : "本队尚未到达可交易站点");
    }

    private boolean arrived(State state, long nodeId) {
        if (nodeId <= 0) return false;
        if (trusted(state.getThemeRuntime().path("verifiedNodes").path(String.valueOf(nodeId)))) return true;
        for (JsonNode arrival : state.getThemeRuntime().path("gameplay").path("arrivalFacts"))
            if (arrival.path("sourceNodeId").asLong() == nodeId && arrival.path("receiptId").asLong() > 0) return true;
        for (JsonNode arrival : state.getThemeRuntime().path("hostArrivals"))
            if (arrival.path("sourceNodeId").asLong() == nodeId && arrival.path("used").asBoolean()) return true;
        return false;
    }
    private ObjectNode deliberationView(JsonNode theme, JsonNode c) {
        ObjectNode out = JSON.createObjectNode().put("stage", c.path("stage").asText("DRAFT")).put("round", c.path("round").asInt());
        for (String field : Arrays.asList("selected", "candidates", "tiedCandidates", "voters", "result")) if (c.has(field)) out.set(field, c.get(field).deepCopy());
        ArrayNode rows = out.putArray("proposals");
        for (JsonNode proposal : c.path("proposals")) {
            ObjectNode row = publicProposal(proposal); JsonNode plan = configured(theme, "plans", "endingKey", proposal.path("endingKey").asText());
            row.put("title", plan.path("title").asText()).put("summary", plan.path("summary").asText()); row.set("materialCost", plan.path("materialCost").deepCopy());
            ObjectNode confirmed = row.putObject("confirmed");
            c.path("locks").fields().forEachRemaining(e -> { if (proposal.path("proposalKey").asText().equals(e.getValue().path("proposalKey").asText())) confirmed.set(e.getKey(), e.getValue().path("amounts").deepCopy()); });
            rows.add(row);
        }
        for (String field : Arrays.asList("votes", "acceptances", "consensus")) {
            ObjectNode ballots = out.putObject(field);
            c.path(field).fields().forEachRemaining(e -> { JsonNode row = e.getValue(); ballots.set(e.getKey(), (row.has("choices") ? row.path("choices") : row.path("choice")).deepCopy()); });
        }
        if (out.has("result")) {
            out.set("result", publicResult(out.get("result")));
            ((ObjectNode) out.get("result")).remove("operatorMemberId");
        }
        return out;
    }
    private ObjectNode publicProposal(JsonNode row) {
        ObjectNode out = JSON.createObjectNode(); for (String field : Arrays.asList("proposalKey", "endingKey", "teamId", "status", "version")) if (row.has(field)) out.set(field, row.get(field).deepCopy());
        // 非绑定意向不向其他队公开未确认资产；视图只给已签托管合计。
        return out;
    }
    public static ObjectNode publicResult(JsonNode result) {
        ObjectNode out = result.deepCopy();
        redactContributionSigners(out);
        return out;
    }
    private static void redactContributionSigners(JsonNode result) {
        if (result.isObject() && result.path("contributions").isObject()) {
            ObjectNode safe = JSON.createObjectNode();
            result.path("contributions").fields().forEachRemaining(entry -> {
                JsonNode row = entry.getValue();
                JsonNode amounts = row.isArray() ? row : row.path("amounts");
                safe.set(entry.getKey(), amounts.isArray() ? amounts.deepCopy() : JSON.createArrayNode());
            });
            ((ObjectNode) result).set("contributions", safe);
        }
        for (JsonNode child : result) redactContributionSigners(child);
    }
    private boolean trusted(JsonNode value) { return (value.isIntegralNumber() && value.asLong()>0) || (value.isBoolean() && value.asBoolean()); }
    private ObjectNode publicTrade(JsonNode row) {
        ObjectNode out = JSON.createObjectNode(); for (String field : Arrays.asList("tradeKey", "fromTeamId", "toTeamId", "give", "take", "version", "status")) out.set(field, row.path(field).deepCopy());
        ArrayNode confirmed = out.putArray("confirmedTeams"); row.path("signatures").fieldNames().forEachRemaining(id -> confirmed.add(Long.valueOf(id))); return out;
    }
    private ObjectNode balance(State state) {
        ObjectNode out = JSON.createObjectNode(); out.set("inventory", state.getThemeRuntime().path("inventory").deepCopy());
        out.set("variables", JSON.valueToTree(state.getVariables())); out.put("activeQuest", state.getThemeRuntime().path("gameplay").path("activeQuest").asText("")); return out;
    }
    private void requireFrozenBalances(JsonNode c, Map<Long, State> teams) {
        for (Long team : teams.keySet()) if (!balance(teams.get(team)).equals(c.path("ballotBalances").path(String.valueOf(team)))) throw new IllegalStateException("本轮库存版本改变，须改案并重新表决");
    }
    private boolean currentSignature(JsonNode rows, Map<Long, Long> leaders, Long team) {
        JsonNode value = rows.path(String.valueOf(team));
        return leaders.get(team) != null && (value.isIntegralNumber() ? value.asLong() : value.path("memberId").asLong(-1)) == leaders.get(team);
    }
    private void sign(ObjectNode rows, Long team, Long member, String choice) { rows.putObject(String.valueOf(team)).put("memberId", member).put("choice", choice); }
    private void requireRound(JsonNode c, JsonNode p, String stage) {
        if (!p.path("round").isIntegralNumber() || p.path("round").asInt() != c.path("round").asInt() || !stage.equals(c.path("stage").asText())) throw new IllegalStateException("票轮或方案版本已经改变");
    }
    private void requireCandidate(JsonNode c, String id, boolean reject) { if (!(reject && "REJECT".equals(id)) && !contains(c.path("candidates"), id)) throw new IllegalArgumentException("不是本轮有效候选案"); }
    private boolean contains(JsonNode rows, String id) { for (JsonNode row : rows) if (id.equals(row.asText())) return true; return false; }
    private void requireOpen(JsonNode row) { if (!"OPEN".equals(row.path("status").asText())) throw new IllegalStateException("提案已经撤回"); }
    private void requireParty(JsonNode row, Long actor) { if (row.path("fromTeamId").asLong() != actor && row.path("toTeamId").asLong() != actor) throw new IllegalStateException("不能签署其他队伍的交易"); }
    private void requireVersion(JsonNode row, JsonNode p) { if (!p.path("version").isIntegralNumber() || p.path("version").asInt() != row.path("version").asInt()) throw new IllegalStateException("预览版本已改变"); }
    private ObjectNode row(ObjectNode rows, String id) { if (!rows.path(id).isObject()) throw new IllegalArgumentException("引用不存在"); return (ObjectNode) rows.get(id); }
    private JsonNode configured(JsonNode theme, String field, String key, String id) { for (JsonNode row : theme.path("gameplayRuntime").path(field)) if (id.equals(row.path(key).asText())) return row; throw new IllegalArgumentException("本局冻结配置中没有此引用"); }
    private ArrayNode amounts(JsonNode theme, JsonNode rows) {
        if (!rows.isArray() || rows.size() > 200) throw new IllegalArgumentException("物资须为有限数组"); Set<String> seen = new HashSet<>();
        for (JsonNode row : rows) {
            fields(row, "itemId", "quantity"); String id = key(row, "itemId");
            if (!row.path("quantity").isIntegralNumber() || !row.path("quantity").canConvertToInt() || row.path("quantity").asInt() < 1 || row.path("quantity").asInt() > 999 || !seen.add(id)) throw new IllegalArgumentException("物资数量非法或重复");
            JsonNode item = null; for (JsonNode i : theme.path("items")) if (id.equals(i.path("id").asText())) item = i;
            if (item == null || !"ITEM".equals(item.path("kind").asText()) || item.path("wear").asBoolean()) throw new IllegalArgumentException("仅目录内非穿戴物资可用于队间协作");
        }
        return (ArrayNode) rows.deepCopy();
    }
    private void add(Map<String, Integer> target, JsonNode rows) { for (JsonNode row : rows) { String id = row.path("itemId").asText(); target.put(id, Math.addExact(target.getOrDefault(id, 0), row.path("quantity").asInt())); } }
    private Map<String, Integer> amountMap(JsonNode rows) { Map<String, Integer> result = new LinkedHashMap<>(); add(result, rows); return result; }
    private ArrayNode array(JsonNode rows) { return rows.isArray() ? (ArrayNode) rows : JSON.createArrayNode(); }
    private ObjectNode costs(JsonNode node) { return new GameplayTaskEngine().costs(node); }
    private ObjectNode costs(JsonNode node,Integer costOption) { return new GameplayTaskEngine().costs(node,costOption); }
    private Integer costOption(JsonNode payload) {
        if(!payload.has("costOption"))return null;
        JsonNode index=payload.path("costOption");
        if(!index.isInt())throw new IllegalArgumentException("任选费用组下标须为整数");
        return index.asInt();
    }
    private void paidGuard(JsonNode theme, JourneyRules rules, State state, JsonNode costs) {
        String policy=theme.path("gameplayRuntime").path("survival").path("zeroHpPolicy").asText();
        boolean blocked=Arrays.asList("BLOCK_HP_COST","CHALLENGE","WAIT","STEPS","THEME_FAIL").contains(policy)?inventory.reduceHpCost(theme,state,costs.path("hpCost").asInt())>0
                : "BLOCK_PAID".equals(policy) && (costs.path("hpCost").asInt()>0 || costs.path("luckCost").asInt()>0 || costs.path("actionCost").asInt()>0 || costs.path("statCosts").size()>0 || costs.path("resourceCosts").size()>0);
        if(rules.hasRuntimeState() && rules.getHp().isEnabled() && JourneyInitialState.numericValue(state,"sys.hp")<=0 && blocked)throw new IllegalStateException("当前状态不能承担此收费动作，请先恢复");
    }
    private String key(JsonNode p, String field) { if (!p.path(field).isTextual() || !p.path(field).asText().matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalArgumentException(field + " 标识无效"); return p.path(field).asText(); }
    private Long positiveId(JsonNode p, String field) { if (!p.path(field).isIntegralNumber() || !p.path(field).canConvertToLong() || p.path(field).asLong() <= 0) throw new IllegalArgumentException(field + " 必须为真实ID"); return p.path(field).asLong(); }
    private void fields(JsonNode p, String... allowed) { if (!p.isObject()) throw new IllegalArgumentException("参数须为对象"); Set<String> names = new HashSet<>(Arrays.asList(allowed)); p.fieldNames().forEachRemaining(f -> { if (!names.contains(f)) throw new IllegalArgumentException("不接受参数：" + f); }); }
}
