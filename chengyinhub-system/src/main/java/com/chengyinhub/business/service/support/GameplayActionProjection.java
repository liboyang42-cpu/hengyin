package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.GameplayToolState.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared gameplay projections; runtime ownership and access checks stay with callers. */
public final class GameplayActionProjection {
    private static final ObjectMapper JSON = new ObjectMapper();
    private GameplayActionProjection() {}

    public static ObjectNode gameplayChallengeSummary(JsonNode theme,TopicRouteEngine.State state) {
        ObjectNode view=new GameplaySurvivalEngine().challengeView(theme,state);
        int limit=view.path("limit").asInt(),count=state.getThemeRuntime().path("zeroChallenges").path("used").asInt();
        GameplayToolState.usage(view,limit,count,"OWNER");
        boolean passed=false;
        for(JsonNode submission:state.getThemeRuntime().path("challenge").path("submissions"))if(submission.path("receipt").path("success").asBoolean())passed=true;
        Reason reason=count>=limit?Reason.CHALLENGE_LIMIT:!state.getThemeRuntime().path("awaitingChallenge").asBoolean()?Reason.RULE_BLOCKED:null;
        return GameplayToolState.availability(view,reason,passed);
    }

    /** 供个人与共享局复用的动作摘要；归属和可用性须由调用方先按真实当前局判断。 */
    public static ObjectNode gameplayActionSummary(JsonNode theme,JourneyRules rules,TopicRouteEngine.State ownerState,
                                                   TopicRouteEngine.State worldState,String operation,String contentId) {
        ObjectNode summary=JSON.createObjectNode(),costs=summary.putObject("costs");
        JsonNode game=theme.path("gameplayRuntime"),content=null,facility=null;
        boolean quest=operation.startsWith("QUEST_") || "GAMEPLAY_PREVIEW".equals(operation);
        if(quest)for(JsonNode row:game.path("quests"))if(contentId.equals(row.path("questKey").asText()))content=row;
        if(quest && content!=null && content.has("facilityKey"))for(JsonNode row:game.path("facilities"))if(content.path("facilityKey").asText().equals(row.path("facilityKey").asText()))facility=row;
        if("FACILITY_USE".equals(operation) || "OPERATION_START".equals(operation) || "OPERATION_CANCEL".equals(operation))for(JsonNode row:game.path("facilities"))if(contentId.equals(row.path("facilityKey").asText()))content=row;
        if("RECOVER".equals(operation))for(JsonNode row:game.path("survival").path("recoveries"))if(contentId.equals(row.path("recoveryKey").asText()))content=row;
        if(operation.startsWith("PLAN_") || "PLAN".equals(operation))for(JsonNode row:game.path("plans"))if(contentId.equals(row.path("endingKey").asText()))content=row;
        if(content==null)return JSON.createObjectNode();
        if(!operation.endsWith("CANCEL")) {addActionCosts(costs,content);if(facility!=null)addActionCosts(costs,facility);}
        JsonNode optional=content.has("costOptions")?content:facility!=null && facility.has("costOptions")?facility:null;
        if(optional!=null) {
            ArrayNode options=summary.putArray("costOptions");
            for(JsonNode option:optional.path("costOptions")) {
                ObjectNode row=option.deepCopy(),fees=costs.deepCopy();addActionCosts(fees,option);
                boolean affordable=true;
                try {new ThemeRuntimeEngine().settle(theme,ownerState.copy(),rules,"preview:costOption",fees,JSON.createArrayNode());}
                catch(IllegalArgumentException|IllegalStateException unavailable){affordable=false;}
                row.put("affordable",affordable);row.set("shortfall",affordable?JSON.createArrayNode():gameplayCostShortfall(theme,rules,ownerState,fees));options.add(row);
            }
        }
        summary.put("scope",facility!=null?facility.path("scope").asText("OWNER"):content.path("scope").asText("OWNER"));
        summary.put("targetScope",facility!=null?facility.path("targetScope").asText("OWNER"):content.path("targetScope").asText("OWNER"));
        if(quest) {
            GameplayToolState.usage(summary,1,ownerState.getThemeRuntime().path("gameplay").path("slots").has(content.path("settlementSlot").asText())?1:0,"OWNER");
            if(facility!=null)summary.set("facility",facilityActionSummary(facility,ownerState,worldState));
            if(!operation.endsWith("CANCEL"))summary.put("paymentOperation","QUEST_RESOLVE");
        } else if("RECOVER".equals(operation))GameplayToolState.usage(summary,content.path("limit").asInt(),ownerState.getThemeRuntime().path("recoveries").path(contentId).asInt(),"OWNER");
        else if(operation.startsWith("PLAN_") || "PLAN".equals(operation)) {
            summary.put("scope","WORLD").put("targetScope","PARTICIPANTS").put("limit",1).put("remainingCount",worldState!=null && worldState.getThemeRuntime().path("cooperation").path("executedPlans").has(contentId)?0:1);
            GameplayToolState.usage(summary,1,summary.path("remainingCount").asInt()==0?1:0,"WORLD");
            if(content.path("materialCost").isArray())costs.set("resourceCosts",content.path("materialCost").deepCopy());
            summary.put("paymentOperation","PLAN_EXECUTE");
        } else {
            ObjectNode usage=facilityActionSummary(content,ownerState,worldState);summary.setAll(usage);
            if(!operation.endsWith("CANCEL"))summary.put("paymentOperation","FACILITY_USE");
        }
        Reason reason=summary.path("remainingCount").asInt()==0 || summary.has("facility") && summary.path("facility").path("remainingCount").asInt()==0?Reason.LIMIT_REACHED:null;
        boolean affordable=true;
        if(optional!=null) {
            affordable=false;for(JsonNode option:summary.path("costOptions"))if(option.path("affordable").asBoolean())affordable=true;
        } else if(!operation.startsWith("PLAN") && !operation.endsWith("CANCEL"))try {
            new ThemeRuntimeEngine().settle(theme,ownerState.copy(),rules,"view:costs",costs,JSON.createArrayNode());
        } catch(IllegalArgumentException|IllegalStateException unavailable) {affordable=false;}
        if(reason==null && !affordable)reason=Reason.INSUFFICIENT_RESOURCES;
        if(reason==Reason.INSUFFICIENT_RESOURCES && "FACILITY_USE".equals(operation) && "OWNER".equals(content.path("targetScope").asText())
                && new GameplayCooperationEngine().facilityRecoveryNoEffect(theme,rules,ownerState,content,Collections.singletonList(ownerState)))reason=null;
        if(summary.has("facilityKey") && "PARTICIPANTS".equals(summary.path("targetScope").asText())) {
            summary.putArray("participantCatalog");summary.put("payerRequired",true);
        }
        GameplayToolState.availability(summary,reason,false);
        JourneyInitialState.redactHidden(rules,summary);return summary;
    }

    private static ObjectNode facilityActionSummary(JsonNode facility,TopicRouteEngine.State ownerState,TopicRouteEngine.State worldState) {
        TopicRouteEngine.State countState="WORLD".equals(facility.path("scope").asText())?worldState:ownerState;
        int count=countState==null?0:countState.getThemeRuntime().path("cooperation").path("facilities").path(facility.path("settlementSlot").asText()).path("count").asInt();
        ObjectNode view=JSON.createObjectNode().put("facilityKey",facility.path("facilityKey").asText()).put("scope",facility.path("scope").asText()).put("targetScope",facility.path("targetScope").asText());
        GameplayToolState.usage(view,facility.path("limit").asInt(),count,facility.path("scope").asText("OWNER"));
        return GameplayToolState.availability(view,count>=facility.path("limit").asInt()?Reason.LIMIT_REACHED:null,false);
    }

    public static ArrayNode gameplayCostShortfall(JsonNode theme,JourneyRules rules,TopicRouteEngine.State state,JsonNode costs) {
        ArrayNode missing=JSON.createArrayNode();ThemeRuntimeEngine inventory=new ThemeRuntimeEngine();TopicRouteEngine.State available=state.copy();
        ObjectNode materials=JSON.createObjectNode();ArrayNode paid=materials.putArray("resourceCosts");
        for(JsonNode row:costs.path("resourceCosts")) {
            String key=row.path("itemId").asText();int held=state.getThemeRuntime().path("inventory").path(key).asInt(),needed=row.path("quantity").asInt();
            if(needed>held)missing.addObject().put("type","ITEM").put("key",key).put("missing",needed-held);
            if(Math.min(held,needed)>0)paid.addObject().put("itemId",key).put("quantity",Math.min(held,needed));
        }
        if(!paid.isEmpty())inventory.settle(theme,available,rules,"preview:costOption:materials",materials,JSON.createArrayNode());
        Map<String,Integer> stats=new LinkedHashMap<>();stats.put("sys.hp",costs.path("hpCost").asInt());stats.put("sys.luck",costs.path("luckCost").asInt());
        for(JsonNode row:costs.path("statCosts"))stats.put(row.path("statKey").asText(),row.path("amount").asInt());
        for(Map.Entry<String,Integer> entry:stats.entrySet())if(entry.getValue()>0) {
            String key=entry.getKey();int min=key.startsWith("sys.")?0:JourneyInitialState.numericAttribute(rules,key).path("min").asInt();
            if(!key.startsWith("sys.") && !JourneyInitialState.numericAttribute(rules,key).path("visible").asBoolean(true))continue;
            long deficit=(long)inventory.reduceStatCost(theme,available,key,entry.getValue())-JourneyInitialState.numericValue(available,key)+min;
            if(deficit>0)missing.addObject().put("type","STAT").put("key",key).put("missing",deficit);
        }
        long actions=((Number)state.getVariables().getOrDefault("sys.actions",0)).longValue()+costs.path("actionCost").asInt()-theme.path("actionLimit").asInt();
        if(theme.has("actionLimit") && actions>0)missing.addObject().put("type","STAT").put("key","sys.actions").put("missing",actions);
        return missing;
    }

    private static void addActionCosts(ObjectNode costs,JsonNode source) {
        for(String field:Arrays.asList("hpCost","luckCost","actionCost"))if(source.has(field))costs.put(field,costs.path(field).asInt()+source.path(field).asInt());
        for(String field:Arrays.asList("statCosts","resourceCosts")) {
            if(!source.path(field).isArray())continue;
            String key="statCosts".equals(field)?"statKey":"itemId",value="statCosts".equals(field)?"amount":"quantity";
            Map<String,Integer> totals=new LinkedHashMap<>();for(JsonNode row:costs.path(field))totals.put(row.path(key).asText(),row.path(value).asInt());
            for(JsonNode row:source.path(field))totals.put(row.path(key).asText(),totals.getOrDefault(row.path(key).asText(),0)+row.path(value).asInt());
            ArrayNode result=costs.putArray(field);totals.forEach((id,amount)->result.addObject().put(key,id).put(value,amount));
        }
    }
}
