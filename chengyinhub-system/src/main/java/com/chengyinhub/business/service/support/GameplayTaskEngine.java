package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.TopicRouteEngine.State;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** PLAYER和真实TEAM共用任务/幕结算；身份、到达和数据库回执由服务校验。 */
public final class GameplayTaskEngine {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ThemeRuntimeEngine inventory=new ThemeRuntimeEngine();
    private final GameplaySurvivalEngine survival=new GameplaySurvivalEngine();

    public JsonNode quest(JsonNode theme,String key) {
        for(JsonNode quest:theme.path("gameplayRuntime").path("quests"))if(key.equals(quest.path("questKey").asText()))return quest;
        throw new IllegalArgumentException("本局没有此任务");
    }
    public void initialize(JsonNode theme,JourneyRules rules,State state,long now) {
        if(state.getThemeRuntime().isEmpty())inventory.initialize(theme,state);
        if(theme.path("gameplayRuntime").path("initialGrants").isArray())inventory.grantAll(theme,state,rules,"initial",theme.path("gameplayRuntime").path("initialGrants"));
        ObjectNode progress=state.getThemeRuntime().with("gameplay");if(!progress.has("actIndex"))progress.put("actIndex",0);
        JsonNode act=current(theme,state);if(!progress.has("awaitingUnlock"))progress.put("awaitingUnlock",act.path("hostUnlock").asBoolean());
        refresh(theme,state);
        for(JsonNode chapter:theme.path("chapters"))if(containsNode(chapter,state.getCurrentNodeId()))inventory.chapterStart(theme,state,rules,chapter,now);
    }
    public void eligible(JsonNode theme,State state,JsonNode quest) {
        JsonNode act=current(theme,state);ObjectNode p=state.getThemeRuntime().with("gameplay");
        boolean currentAct=quest.path("actKey").asText().equals(act.path("actKey").asText());
        if((currentAct && p.path("awaitingUnlock").asBoolean()) || (!currentAct && !("ACTIVE".equals(state.getStatus()) && actIndex(theme,quest)<p.path("actIndex").asInt() && isExtraTask(theme,state,quest))))throw new IllegalStateException("任务幕尚未开放");
        if(p.path("quests").has(quest.path("questKey").asText()))throw new IllegalStateException("任务已结算");
        for(JsonNode prior:quest.path("prerequisites"))if(!p.path("quests").has(prior.asText()))throw new IllegalStateException("任务前置尚未完成");
        if(!inventory.conditions(quest.path("when"),state))throw new IllegalStateException("任务状态前置未满足");
        if(quest.has("exclusiveGroup") && p.path("exclusiveGroups").has(quest.path("exclusiveGroup").asText()))throw new IllegalStateException("互斥任务已选择");
        if(isExtraTask(theme,state,quest) && p.path("extraSlots").has(quest.path("extraSlot").asText()))throw new IllegalStateException("额外任务槽已经使用");
    }

    public ObjectNode preview(JsonNode theme,JourneyRules rules,State state,String questKey,String choiceKey,
                              JsonNode discardRewards,String ownerKey) {
        return preview(theme,rules,state,questKey,choiceKey,discardRewards,ownerKey,null);
    }
    public ObjectNode preview(JsonNode theme,JourneyRules rules,State state,String questKey,String choiceKey,
                              JsonNode discardRewards,String ownerKey,Integer costOption) {
        return preview(theme,rules,state,questKey,choiceKey,discardRewards,ownerKey,costOption,System.currentTimeMillis());
    }
    public ObjectNode preview(JsonNode theme,JourneyRules rules,State state,String questKey,String choiceKey,
                              JsonNode discardRewards,String ownerKey,Integer costOption,long now) {
        State next=state.copy();JsonNode q=quest(theme,questKey);eligible(theme,next,q);
        Integer selected=selectedCostOption(theme,state,q,"QUEST_RESOLVE",costOption);
        ArrayNode discarded=inventory.rewardSelection(theme,discardRewards);
        ObjectNode simulation=((ObjectNode)theme).deepCopy();simulation.put("capacity",Integer.MAX_VALUE);
        next.getThemeRuntime().remove("rewardSettlement");inventory.beginRewards(simulation,next,discarded);
        // 只在预览副本假定题组通过；正式执行仍校验当前可信证明。
        next.getThemeRuntime().with("gameplay").put("activeQuest",questKey);
        next.getThemeRuntime().with("verifiedNodes").put(q.path("nodeId").asText(),true);
        ObjectNode result;
        if(previewFacility(theme,state,q)!=null) {
            if(ownerKey==null || !ownerKey.matches("(MEMBER|TEAM):[1-9][0-9]{0,18}"))throw new IllegalArgumentException("装载预览需要真实所有者");
            Long ownerId=Long.valueOf(ownerKey.substring(ownerKey.indexOf(':')+1));Map<Long,State> owners=new LinkedHashMap<>();owners.put(ownerId,next);
            Map<Long,Long> leaders=Collections.singletonMap(ownerId,ownerId);
            result=new GameplayCooperationEngine().executeQuest(simulation,rules,new State(),owners,leaders,ownerId,ownerId,questKey,"QUEST_RESOLVE",choiceKey,"preview",ownerKey,now,null,null,selected);
            next=owners.get(ownerId);
        } else result=execute(simulation,rules,next,questKey,"QUEST_RESOLVE",choiceKey,"preview",ownerKey,now,null,null,selected);
        ObjectNode rewards=inventory.finishRewards(next);
        result.set("grants",rewards.path("grants"));result.set("rewardSources",rewards.path("rewardSources"));
        ArrayNode actual=JSON.createArrayNode();rewards.path("actualGrants").fields().forEachRemaining(row->{if(row.getValue().asInt()>0)actual.addObject().put("itemId",row.getKey()).put("quantity",row.getValue().asInt());});
        result.set("actualGrants",actual);result.set("discardRewards",discarded);
        result.put("questKey",questKey).put("finalUsed",inventory.used(simulation,next)).put("capacity",theme.path("capacity").asInt());
        result.put("overflow",result.path("finalUsed").asInt()>theme.path("capacity").asInt()).put("previewRequiredForDiscard",true);
        if(selected!=null)result.put("costOption",selected);
        result.put("previewVersion",previewVersion(theme,state,q,choiceKey,discarded,ownerKey,selected,rewards));
        return result;
    }

    public ObjectNode execute(JsonNode theme,JourneyRules rules,State state,String questKey,String action,
                              String choiceKey,String eventId,String ownerKey,long now) {
        return execute(theme,rules,state,questKey,action,choiceKey,eventId,ownerKey,now,null,null,new LinkedHashMap<>(),true);
    }
    public ObjectNode execute(JsonNode theme,JourneyRules rules,State state,String questKey,String action,
                              String choiceKey,String eventId,String ownerKey,long now,Map<String,Set<JourneyStatLinks.Reason>> reasons,boolean applyLinks) {
        return execute(theme,rules,state,questKey,action,choiceKey,eventId,ownerKey,now,null,null,reasons,applyLinks);
    }
    public ObjectNode execute(JsonNode theme,JourneyRules rules,State state,String questKey,String action,
                              String choiceKey,String eventId,String ownerKey,long now,JsonNode discardRewards,String previewVersion) {
        return execute(theme,rules,state,questKey,action,choiceKey,eventId,ownerKey,now,discardRewards,previewVersion,new LinkedHashMap<>(),true);
    }
    public ObjectNode execute(JsonNode theme,JourneyRules rules,State state,String questKey,String action,
                              String choiceKey,String eventId,String ownerKey,long now,JsonNode discardRewards,String previewVersion,Integer costOption) {
        return execute(theme,rules,state,questKey,action,choiceKey,eventId,ownerKey,now,discardRewards,previewVersion,costOption,new LinkedHashMap<>(),true);
    }
    public ObjectNode execute(JsonNode theme,JourneyRules rules,State state,String questKey,String action,
                              String choiceKey,String eventId,String ownerKey,long now,JsonNode discardRewards,String previewVersion,
                              Map<String,Set<JourneyStatLinks.Reason>> reasons,boolean applyLinks) {
        return execute(theme,rules,state,questKey,action,choiceKey,eventId,ownerKey,now,discardRewards,previewVersion,null,reasons,applyLinks);
    }
    public ObjectNode execute(JsonNode theme,JourneyRules rules,State state,String questKey,String action,
                              String choiceKey,String eventId,String ownerKey,long now,JsonNode discardRewards,String previewVersion,Integer costOption,
                              Map<String,Set<JourneyStatLinks.Reason>> reasons,boolean applyLinks) {
        if(!Arrays.asList("QUEST_START","QUEST_CANCEL","QUEST_RESOLVE").contains(action))throw new IllegalArgumentException("不是任务动作");
        if(reasons==null)throw new IllegalArgumentException("任务联动需要可信原因集合");
        JsonNode q=quest(theme,questKey);String slot=q.path("settlementSlot").asText();
        Integer selected=selectedCostOption(theme,state,q,action,costOption);ObjectNode questFee=selectedCosts(q,selected);
        ArrayNode discarded=JSON.createArrayNode();
        JsonNode prior=state.getThemeRuntime().path("gameplay").path("slots").path(slot);
        if(prior.isObject()) {
            if("QUEST_RESOLVE".equals(action) && !Objects.equals(choiceKey,prior.path("choiceKey").asText(null)))throw new IllegalStateException("任务已结算，不能更换配包");
            if("QUEST_RESOLVE".equals(action) && selected!=null && prior.path("costOption").asInt(-1)!=selected)throw new IllegalStateException("任务已结算，不能更换费用组");
            if("QUEST_RESOLVE".equals(action))discarded=inventory.rewardSelection(theme,discardRewards);
            if("QUEST_RESOLVE".equals(action) && !discarded.equals(prior.has("discardRewards")?prior.get("discardRewards"):JSON.createArrayNode()))throw new IllegalStateException("任务已结算，不能更换放弃物资");
            return (ObjectNode)prior.deepCopy();
        }
        if("QUEST_RESOLVE".equals(action))discarded=inventory.rewardSelection(theme,discardRewards);
        else if((discardRewards!=null && (!discardRewards.isArray() || !discardRewards.isEmpty())) || previewVersion!=null)throw new IllegalArgumentException("仅结算时确认装载预览");
        if(state.getThemeRuntime().path("journal").size()>=4096)throw new IllegalStateException("本局任务事件容量已满，请导出并复核");
        if("QUEST_RESOLVE".equals(action) && !state.getThemeRuntime().has("rewardSettlement") && (!discarded.isEmpty() || previewVersion!=null)) {
            if(previewVersion==null || !previewVersion.equals(preview(theme,rules,state,questKey,choiceKey,discarded,ownerKey,selected,now).path("previewVersion").asText()))throw new IllegalStateException("装载预览已过期，请重新预览并确认");
        }
        if(choiceKey!=null && !"QUEST_RESOLVE".equals(action))throw new IllegalArgumentException("仅结算时选择配包");
        State next=state.copy(),before=state.copy();eligible(theme,next,q);ObjectNode p=next.getThemeRuntime().with("gameplay");
        String active=p.path("activeQuest").asText();
        ObjectNode result;
        if("QUEST_START".equals(action)) {
            if(!active.isEmpty() && !active.equals(questKey))throw new IllegalStateException("请先完成或取消当前任务");
            paidGuard(theme,rules,before,questFee);inventory.settle(theme,next.copy(),rules,"preview:"+slot,questFee,JSON.createArrayNode());
            if(active.isEmpty())clearTaskProof(next,q);
            p.put("activeQuest",questKey);if(selected!=null)p.put("activeQuestCostOption",selected);result=JSON.createObjectNode().put("activeQuest",questKey);if(selected!=null)result.put("costOption",selected);
        } else if("QUEST_CANCEL".equals(action)) {
            if(!active.equals(questKey))throw new IllegalStateException("任务未开始");p.remove(Arrays.asList("activeQuest","activeQuestCostOption"));clearTaskProof(next,q);result=JSON.createObjectNode().put("cancelledQuest",questKey);
        } else {
            if(!active.equals(questKey))throw new IllegalStateException("请先开始任务");
            JsonNode verified=next.getThemeRuntime().path("verifiedNodes").path(String.valueOf(q.path("nodeId").asLong()));
            if(!((verified.isIntegralNumber() && verified.asLong()>0) || (verified.isBoolean() && verified.asBoolean())))throw new IllegalStateException("题组尚未通过服务端判定");
            ObjectNode blocked=settlePendingOutcome(theme,next,rules,questKey,reasons,now);
            if(blocked!=null){copy(next,state);return blocked;}
            boolean ownRewards=!next.getThemeRuntime().has("rewardSettlement");
            if(ownRewards)inventory.beginRewards(theme,next,discarded);
            paidGuard(theme,rules,before,questFee);JsonNode grants=grants(q,choiceKey);
            boolean extraTask=isExtraTask(theme,next,q);
            result=inventory.settle(theme,next,rules,"quest:"+slot,questFee,JSON.createArrayNode());
            JourneyStatLinks.recordDeclines(rules,before,next,JourneyStatLinks.Reason.TASK_COST,reasons);
            consumePendingEffects(theme,next,rules,questKey,reasons,now);
            State rewardBefore=next.copy();ObjectNode reward=inventory.grantAll(theme,next,rules,"quest:"+slot+":rewards",grants);
            JourneyStatLinks.recordDeclines(rules,rewardBefore,next,JourneyStatLinks.Reason.TEMPLATE_EFFECT,reasons);
            result.set("grants",reward.path("grants").deepCopy());result.put("used",inventory.used(theme,next));
            result.put("questKey",questKey).put("eventId",eventId);if(choiceKey!=null)result.put("choiceKey",choiceKey);if(selected!=null)result.put("costOption",selected);
            if(!discarded.isEmpty())result.set("discardRewards",discarded.deepCopy());
            p=next.getThemeRuntime().with("gameplay");p.with("quests").put(questKey,eventId);p.remove(Arrays.asList("activeQuest","activeQuestCostOption"));
            if(q.has("exclusiveGroup"))p.with("exclusiveGroups").put(q.path("exclusiveGroup").asText(),questKey);
            if(extraTask)p.with("extraSlots").put(q.path("extraSlot").asText(),questKey);
            long node=q.path("nodeId").asLong();next.getCompletedNodeIds().add(node);next.getNodeStates().put(node,"COMPLETED");next.getVariables().put("sys.passed."+node,1);
            JsonNode act=actFor(theme,q);boolean complete=actComplete(theme,next,act);
            for(JsonNode chapter:theme.path("chapters")) {
                boolean ended=next.getThemeRuntime().path("chapters").path(String.valueOf(chapter.path("index").asInt())).path("ended").asBoolean();
                if(ended)continue;
                boolean ready=false;
                for(JsonNode id:chapter.path("binding").path("nodeIds"))if(!GameplaySurvivalEngine.challengeNode(theme,id.asLong())) {
                    if(!next.getCompletedNodeIds().contains(id.asLong())) {ready=false;break;}
                    ready=true;
                }
                if(complete && chapterMatchesAct(theme,chapter,act))ready=true;
                if(ready) {
                    paidGuard(theme,rules,before,chapter);State chapterBefore=next.copy();inventory.chapterEnd(theme,next,rules,chapter,now);
                    JourneyStatLinks.recordDeclines(rules,chapterBefore,next,JourneyStatLinks.Reason.CHAPTER_COST,reasons);
                }
            }
            if(complete && actIndex(theme,q)==p.path("actIndex").asInt()) {
                State rationBefore=next.copy();survival.ration(theme,rules,next,act.path("actKey").asText(),ownerKey,eventId+":ration");
                JourneyStatLinks.recordDeclines(rules,rationBefore,next,JourneyStatLinks.Reason.CHAPTER_COST,reasons);
                p=next.getThemeRuntime().with("gameplay");int index=p.path("actIndex").asInt()+1;p.put("actIndex",index);
                JsonNode nextAct=theme.path("gameplayRuntime").path("acts").path(index);
                if(nextAct.isObject())p.put("awaitingUnlock",nextAct.path("hostUnlock").asBoolean());else next.setStatus("COMPLETED");
            }
            refresh(theme,next);
            State chapterStartBefore=next.copy();for(JsonNode chapter:theme.path("chapters"))if(containsNode(chapter,next.getCurrentNodeId()))inventory.chapterStart(theme,next,rules,chapter,now);
            JourneyStatLinks.recordDeclines(rules,chapterStartBefore,next,JourneyStatLinks.Reason.TEMPLATE_EFFECT,reasons);
            State eventBefore=next.copy();inventory.events(theme,next,rules);JourneyStatLinks.recordDeclines(rules,eventBefore,next,JourneyStatLinks.Reason.TEMPLATE_EFFECT,reasons);JourneyInitialState.clamp(rules,next);
            if(applyLinks)JourneyStatLinks.apply(rules,before,next,ownerKey,"quest:"+slot,reasons);
            if(applyLinks && "COMPLETED".equals(next.getStatus()) && !"WORLD_SHARED".equals(GameplayRuntimeConfig.getWorldMode(theme)) && !GameplaySurvivalEngine.deferCompletion(theme,next))inventory.terminal(theme,next,rules,"COMPLETED",now);
            if(ownRewards)result.set("rewards",inventory.finishRewards(next));
            result.put("used",inventory.used(theme,next));
            next.getThemeRuntime().with("gameplay").with("slots").set(slot,result.deepCopy());
        }
        refresh(theme,next);next.getThemeRuntime().withArray("journal").addObject().put("action",action).put("questKey",questKey).put("eventId",eventId);
        if(next.getThemeRuntime().path("journal").size()>4096)throw new IllegalStateException("本局任务事件容量已满，请导出并复核");
        copy(next,state);return result;
    }

    public void refresh(JsonNode theme,State state) {
        Long next=null;boolean nextInCurrentAct=false;String currentAct=current(theme,state).path("actKey").asText();
        for(JsonNode quest:theme.path("gameplayRuntime").path("quests")) {
            Long id=quest.path("nodeId").asLong();if(state.getCompletedNodeIds().contains(id))continue;
            boolean allowed=true;try{eligible(theme,state,quest);}catch(IllegalStateException unavailable){allowed=false;}
            state.getNodeStates().put(id,allowed?"PLAYABLE":"DISCOVERED_LOCKED");
            boolean inCurrentAct=currentAct.equals(quest.path("actKey").asText());
            if(allowed && (next==null || inCurrentAct && !nextInCurrentAct)){next=id;nextInCurrentAct=inCurrentAct;}
        }
        state.setCurrentNodeId(next);state.setRecommendedNodeId(next);if(next!=null)state.getVisitedNodeIds().add(next);
    }
    public static boolean chapterMatchesAct(JsonNode theme,JsonNode chapter,JsonNode act) {
        Set<Long> nodes=new HashSet<>();for(JsonNode q:theme.path("gameplayRuntime").path("quests"))if(act.path("actKey").asText().equals(q.path("actKey").asText()))nodes.add(q.path("nodeId").asLong());
        Set<Long> bound=new HashSet<>();for(JsonNode id:chapter.path("binding").path("nodeIds"))if(!GameplaySurvivalEngine.challengeNode(theme,id.asLong()))bound.add(id.asLong());return !bound.isEmpty() && nodes.equals(bound);
    }
    public ObjectNode costs(JsonNode q){ObjectNode costs=JSON.createObjectNode();for(String f:Arrays.asList("hpCost","luckCost","statCosts","resourceCosts","actionCost"))if(q.has(f))costs.set(f,q.get(f));return costs;}
    public ObjectNode costs(JsonNode q,Integer costOption) {
        JsonNode options=q.path("costOptions");
        if(options.isArray()) {
            if(costOption==null || costOption<0 || costOption>=options.size())throw new IllegalArgumentException("请选择有效的任选费用组");
            return costs(options.get(costOption));
        }
        if(costOption!=null)throw new IllegalArgumentException("该动作没有任选费用组");
        return costs(q);
    }
    private ObjectNode selectedCosts(JsonNode content,Integer selected) {
        return content.has("costOptions")?costs(content,selected):costs(content);
    }
    private Integer selectedCostOption(JsonNode theme,State state,JsonNode quest,String action,Integer requested) {
        JsonNode facility=null;
        if(quest.has("facilityKey"))for(JsonNode row:theme.path("gameplayRuntime").path("facilities"))
            if(quest.path("facilityKey").asText().equals(row.path("facilityKey").asText()))facility=row;
        boolean optional=quest.has("costOptions") || facility!=null && facility.has("costOptions");
        JsonNode game=state.getThemeRuntime().path("gameplay");
        JsonNode frozen=game.path("activeQuestCostOption");
        JsonNode settled=game.path("slots").path(quest.path("settlementSlot").asText()).path("costOption");
        Integer selected=requested!=null?requested:frozen.isInt()?Integer.valueOf(frozen.asInt()):settled.isInt()?Integer.valueOf(settled.asInt()):null;
        if(!optional && requested!=null)throw new IllegalArgumentException("该任务没有任选费用组");
        if(optional && selected==null && !"QUEST_CANCEL".equals(action))throw new IllegalArgumentException("请选择任选费用组");
        if(frozen.isInt() && selected!=null && frozen.asInt()!=selected || settled.isInt() && selected!=null && settled.asInt()!=selected)
            throw new IllegalStateException("任务实例不能更换费用组");
        if(selected!=null) {
            if(quest.has("costOptions"))costs(quest,selected);
            if(facility!=null && facility.has("costOptions"))costs(facility,selected);
        }
        return selected;
    }
    private ObjectNode settlePendingOutcome(JsonNode theme,State state,JourneyRules rules,String questKey,Map<String,Set<JourneyStatLinks.Reason>> reasons,long now) {
        JsonNode stored=state.getThemeRuntime().path("gameplay").path("pendingTaskEffects").path(questKey);
        if(!stored.has("outcomeAttemptId"))return null;
        State before=state.copy();
        ObjectNode outcome=new GameplayOutcomeEngine().settleRemembered(theme,rules,state,Collections.singletonMap(1L,state),stored.path("outcomeAttemptId").asText(),now,pureOutcomeOnly(theme,quest(theme,questKey),stored,rules,state));
        if(!outcome.path("settled").asBoolean())return outcome;
        JourneyStatLinks.recordDeclines(rules,before,state,JourneyStatLinks.Reason.TASK_COST,reasons);
        return null;
    }
    private void consumePendingEffects(JsonNode theme,State state,JourneyRules rules,String questKey,Map<String,Set<JourneyStatLinks.Reason>> reasons,long now) {
        JsonNode stored=state.getThemeRuntime().path("gameplay").path("pendingTaskEffects").get(questKey);
        if(stored==null)return;
        if(!stored.isObject() || !stored.path("effects").isArray() || !stored.path("sources").isObject())throw new IllegalStateException("可信任务效果记录不完整");
        State before=state.copy();TemplateEffects.apply(stored.path("effects"),rules,state);JourneyInitialState.clamp(rules,state);
        JourneyStatLinks.recordDeclines(rules,before,state,JourneyStatLinks.Reason.TEMPLATE_EFFECT,reasons);
        state.getThemeRuntime().with("gameplay").with("pendingTaskEffects").remove(questKey);
    }
    private boolean pureOutcomeOnly(JsonNode theme,JsonNode q,JsonNode stored,JourneyRules rules,State state) {
        for(String field:Arrays.asList("grants","choices"))if(!q.path(field).isEmpty())return false;
        if(!stored.path("effects").isEmpty() && !TemplateEffects.pureRecovery(stored.path("effects")))return false;
        State probe=state.copy();TemplateEffects.apply(stored.path("effects"),rules,probe);
        return probe.getVariables().equals(state.getVariables()) && theme.path("chapters").isEmpty() && theme.path("events").isEmpty() && theme.path("gameplayRuntime").path("survival").path("rations").isEmpty();
    }
    private void clearTaskProof(State state,JsonNode quest) {
        JsonNode pending=state.getThemeRuntime().path("gameplay").path("pendingTaskEffects");if(pending.isObject())((ObjectNode)pending).remove(quest.path("questKey").asText());
        String node=quest.path("nodeId").asText();state.getThemeRuntime().with("verifiedNodes").remove(node);state.getVariables().remove("sys.passed."+node);
    }
    private JsonNode previewFacility(JsonNode theme,State state,JsonNode quest) {
        if(!quest.has("facilityKey"))return null;
        JsonNode settled=state.getThemeRuntime().path("gameplay").path("facilitySettled");
        if(settled.asBoolean() || quest.path("questKey").asText().equals(settled.asText()))return null;
        for(JsonNode facility:theme.path("gameplayRuntime").path("facilities"))if(quest.path("facilityKey").asText().equals(facility.path("facilityKey").asText())) {
            if("OWNER".equals(facility.path("scope").asText()) && state.getThemeRuntime().path("cooperation").path("facilities").path(facility.path("settlementSlot").asText()).path("count").asInt()>=facility.path("limit").asInt())return null;
            return facility;
        }
        throw new IllegalArgumentException("任务引用不存在的设施");
    }
    private JsonNode actFor(JsonNode theme,JsonNode quest){return theme.path("gameplayRuntime").path("acts").path(actIndex(theme,quest));}
    private int actIndex(JsonNode theme,JsonNode quest){int index=0;for(JsonNode act:theme.path("gameplayRuntime").path("acts")){if(act.path("actKey").asText().equals(quest.path("actKey").asText()))return index;index++;}throw new IllegalArgumentException("任务引用不存在的幕");}
    private boolean isExtraTask(JsonNode theme,State state,JsonNode quest) {
        if(!quest.has("extraSlot"))return false;
        if(quest.path("extraOnly").asBoolean())return true;
        JsonNode act=actFor(theme,quest);if(!"ANY_N".equals(act.path("completionRule").path("type").asText()))return true;
        return actComplete(theme,state,act);
    }
    private boolean actComplete(JsonNode theme,State state,JsonNode act) {
        Set<String> required=new HashSet<>(),done=new HashSet<>();
        for(JsonNode key:act.path("questKeys")) {
            JsonNode q=quest(theme,key.asText());if(q.path("extraOnly").asBoolean())continue;
            String unit=q.has("exclusiveGroup")?"group:"+q.path("exclusiveGroup").asText():"quest:"+key.asText();
            required.add(unit);if(state.getThemeRuntime().path("gameplay").path("quests").has(key.asText()))done.add(unit);
        }
        return done.size()>=("ANY_N".equals(act.path("completionRule").path("type").asText())?act.path("completionRule").path("quantity").asInt():required.size());
    }
    private ArrayNode grants(JsonNode quest,String choiceKey) {
        JsonNode grants=quest.path("grants");
        if(quest.path("choices").size()>0) {
            JsonNode chosen=null;for(JsonNode choice:quest.path("choices"))if(choice.path("choiceKey").asText().equals(choiceKey))chosen=choice;
            if(chosen==null)throw new IllegalArgumentException("请选择允许的完整配包");grants=chosen.path("grants");
        } else if(choiceKey!=null)throw new IllegalArgumentException("任务没有配包选项");
        return grants.isArray()?(ArrayNode)grants:JSON.createArrayNode();
    }
    private String previewVersion(JsonNode theme,State state,JsonNode quest,String choice,ArrayNode discarded,String ownerKey,Integer costOption,JsonNode rewards) {
        if(ownerKey==null || !ownerKey.matches("(MEMBER|TEAM):[1-9][0-9]{0,18}"))throw new IllegalArgumentException("装载预览需要真实所有者");
        ObjectNode version=JSON.createObjectNode().put("owner",ownerKey).put("choiceKey",choice).put("status",state.getStatus());
        if(costOption!=null)version.put("costOption",costOption);
        version.set("theme",theme);version.set("quest",quest);version.set("discardRewards",discarded);version.set("rewards",rewards);
        for(String field:Arrays.asList("sources","pending","chapters","events","settlements","cooperation"))version.set(field,state.getThemeRuntime().path(field));
        version.set("variables",JSON.valueToTree(new TreeMap<>(state.getVariables())));version.set("tags",JSON.valueToTree(new TreeSet<>(state.getTags())));
        version.set("inventory",state.getThemeRuntime().path("inventory"));version.set("acquired",state.getThemeRuntime().path("acquired"));
        version.set("completedNodes",JSON.valueToTree(new TreeSet<>(state.getCompletedNodeIds())));
        ObjectNode progress=state.getThemeRuntime().path("gameplay").isObject()?(ObjectNode)state.getThemeRuntime().path("gameplay").deepCopy():JSON.createObjectNode();progress.remove("activeQuest");version.set("progress",progress);
        try {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(version.toString().getBytes(StandardCharsets.UTF_8));StringBuilder hash=new StringBuilder();for(byte value:bytes)hash.append(String.format("%02x",value&255));return hash.toString();}
        catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException("装载预览摘要不可用",unavailable);}
    }
    private JsonNode current(JsonNode theme,State s){return theme.path("gameplayRuntime").path("acts").path(s.getThemeRuntime().path("gameplay").path("actIndex").asInt());}
    private boolean containsNode(JsonNode chapter,Long node){if(node==null)return false;for(JsonNode id:chapter.path("binding").path("nodeIds"))if(id.asLong()==node)return true;return false;}
    private void paidGuard(JsonNode theme,JourneyRules rules,State state,JsonNode q) {
        String policy=theme.path("gameplayRuntime").path("survival").path("zeroHpPolicy").asText();
        boolean blocked=Arrays.asList("BLOCK_HP_COST","CHALLENGE","WAIT","STEPS","THEME_FAIL").contains(policy)?inventory.reduceHpCost(theme,state,q.path("hpCost").asInt())>0
                : "BLOCK_PAID".equals(policy) && (q.path("hpCost").asInt()>0 || q.path("luckCost").asInt()>0 || q.path("actionCost").asInt()>0 || q.path("statCosts").size()>0 || q.path("resourceCosts").size()>0);
        boolean hpPolicyEnabled=rules.hasRuntimeState();
        if(hpPolicyEnabled && rules.getHp().isEnabled() && JourneyInitialState.numericValue(state,"sys.hp")<=0 && blocked)throw new IllegalStateException("当前状态不能承担此收费任务，请先恢复");
    }
    private void copy(State from,State to) {
        to.setVariables(from.getVariables());to.setTags(from.getTags());to.setThemeRuntime(from.getThemeRuntime());to.setStatus(from.getStatus());
        to.setNodeStates(from.getNodeStates());to.setCompletedNodeIds(from.getCompletedNodeIds());to.setVisitedNodeIds(from.getVisitedNodeIds());to.setCurrentNodeId(from.getCurrentNodeId());to.setRecommendedNodeId(from.getRecommendedNodeId());
    }
}
