package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.service.support.TopicRouteEngine.State;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/** 主题恢复次数和幕末配给沿用原HP、库存与结算槽。 */
public final class GameplaySurvivalEngine {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ThemeRuntimeEngine inventory=new ThemeRuntimeEngine();
    public static boolean deferCompletion(JsonNode theme,State state) {
        if(!java.util.Arrays.asList("CHALLENGE", "WAIT", "STEPS", "THEME_FAIL").contains(theme.path("gameplayRuntime").path("survival").path("zeroHpPolicy").asText()) || state.getVariables().getOrDefault("sys.hp",0).intValue()>0)return false;
        state.getThemeRuntime().put("finishAfterChallenge",true);state.setStatus("ACTIVE");return true;
    }

    public static boolean challengeNode(JsonNode theme,Long nodeId) {
        JsonNode survival=theme.path("gameplayRuntime").path("survival");
        if(nodeId==null || !"CHALLENGE".equals(survival.path("zeroHpPolicy").asText()))return false;
        JsonNode challenge=survival.path("zeroChallenge");
        if(challenge.path("challengeNodeId").asLong()==nodeId)return true;
        for(JsonNode id:challenge.path("alternativeNodeIds"))if(id.asLong()==nodeId)return true;
        return false;
    }

    public void syncChallenge(JsonNode snapshot,JourneyRules rules,State state) {syncChallenge(snapshot,rules,state,System.currentTimeMillis());}

    public void syncChallenge(JsonNode snapshot,JourneyRules rules,State state,long now) {
        JsonNode definition=snapshot.path("themeConfig").path("gameplayRuntime").path("survival");
        if (r5Policy(definition)) { syncZeroHp(snapshot, rules, state, now); return; }
        if(!"CHALLENGE".equals(definition.path("zeroHpPolicy").asText()))return;
        ObjectNode runtime=state.getThemeRuntime();
        if(JourneyInitialState.numericValue(state,"sys.hp")>0) {
            runtime.put("awaitingChallenge",false);
            if(runtime.path("finishAfterChallenge").asBoolean()) {runtime.remove("finishAfterChallenge");inventory.terminal(snapshot.path("themeConfig"),state,rules,"COMPLETED",now);}
            return;
        }
        JsonNode config=definition.path("zeroChallenge");
        int used=runtime.path("zeroChallenges").path("used").asInt();
        if(!runtime.path("awaitingChallenge").asBoolean() || !runtime.path("challenge").isObject() || runtime.path("challenge").path("resolved").asBoolean()) {
            ObjectNode instance=runtime.putObject("challenge");
            instance.put("instanceId",UUID.randomUUID().toString()).put("nodeId",config.path("challengeNodeId").asLong()).put("progress",0);
            instance.set("alternativeNodeIds",config.path("alternativeNodeIds").isArray()?config.path("alternativeNodeIds").deepCopy():JSON.createArrayNode());
            instance.put("goal",snapshot.path("nodeConfigs").path(config.path("challengeNodeId").asText()).path("ballShake").path("goal").asLong(1));
        }
        runtime.put("awaitingChallenge",true);
        ObjectNode instance=runtime.with("challenge");instance.put("used",used).put("limit",config.path("limit").asInt());
        if(used>=config.path("limit").asInt())instance.put("disabledReason","CHALLENGE_LIMIT");else instance.remove("disabledReason");
    }

    public ObjectNode challengeView(State state) {
        ObjectNode view=JSON.createObjectNode();
        for(String field:new String[]{"instanceId","nodeId","alternativeNodeIds","progress","goal","used","limit","disabledReason","episodeId","deadlineAt","requiredCompletions","completions","gameplayKey"})
            if(state.getThemeRuntime().path("challenge").has(field))view.set(field,state.getThemeRuntime().path("challenge").get(field).deepCopy());
        return view;
    }
    public ObjectNode challengeView(JsonNode theme,State state) {
        return challengeView(state).put("restoreTo",theme.path("gameplayRuntime").path("survival").path("zeroChallenge").path("restoreTo").asInt());
    }

    public ObjectNode submitChallenge(JsonNode snapshot,JourneyRules rules,State state,Long memberId,String instanceId,
                                      Long nodeId,String submissionId,boolean passed,Long hits,boolean retry) {
        return submitChallenge(snapshot, rules, state, memberId, instanceId, nodeId, submissionId, passed, hits, retry, System.currentTimeMillis());
    }

    public ObjectNode submitChallenge(JsonNode snapshot,JourneyRules rules,State state,Long memberId,String instanceId,
                                      Long nodeId,String submissionId,boolean passed,Long hits,boolean retry,long now) {
        if(instanceId==null || memberId==null || memberId<=0 || submissionId==null || submissionId.isEmpty() || submissionId.length()>128
                || !challengeNode(snapshot.path("themeConfig"),nodeId))throw new IllegalArgumentException("挑战提交参数无效");
        ObjectNode runtime=state.getThemeRuntime(),instance=runtime.with("challenge");
        JsonNode config=snapshot.path("themeConfig").path("gameplayRuntime").path("survival").path("zeroChallenge");
        boolean v2 = config.path("version").asInt() == 2;
        String key=memberId+":"+submissionId;
        ObjectNode request=JSON.createObjectNode().put("nodeId",nodeId).put("passed",passed).put("retry",retry);if(hits!=null)request.put("hits",hits);
        if (v2) request.put("instanceId", instanceId);
        ObjectNode submissions = v2 ? runtime.with("zeroHp").with("submissions") : instance.with("submissions");
        JsonNode previous=submissions.path(key);
        if(previous.isObject()) {
            if(!request.equals(previous.path("request")))throw new IllegalStateException("同一挑战提交不能更改成绩");
            return (ObjectNode)previous.path("receipt").deepCopy();
        }
        if(!instanceId.equals(instance.path("instanceId").asText()))throw new IllegalStateException("挑战实例已更新，请重新开始");
        if (v2 && now > instance.path("deadlineAt").asLong()) {
            syncZeroHp(snapshot, rules, state, now);
            return challengeView(snapshot.path("themeConfig"), state).put("success", false).put("retry", false).put("disabledReason", "CHALLENGE_TIMEOUT");
        }
        if(!runtime.path("awaitingChallenge").asBoolean() || instance.path("resolved").asBoolean())throw new IllegalStateException("本局没有待完成挑战");
        if(instance.has("disabledReason"))throw new IllegalStateException("CHALLENGE_LIMIT");
        boolean ball=(!v2 || nodeId!=config.path("challengeNodeId").asLong() || "ballShake".equals(config.path("gameplayKey").asText())) && snapshot.path("nodeConfigs").path(String.valueOf(nodeId)).path("ballShake").path("enabled").asBoolean();
        if(hits!=null && (!ball || hits<0))throw new IllegalArgumentException("该挑战不接受撞击次数");
        long progress=instance.path("progress").asLong();
        if(hits!=null)progress=Math.addExact(progress,hits);
        instance.put("progress",progress);
        boolean success=!retry && (ball?hits!=null && progress>=instance.path("goal").asLong():passed);
        boolean gameCompleted = success;
        if (v2 && gameCompleted) {
            int completions = instance.path("completions").asInt() + 1;
            instance.put("completions", completions);
            success = completions >= config.path("requiredCompletions").asInt();
        }
        int used=runtime.path("zeroChallenges").path("used").asInt();
        if(success) {
            if(!ball)instance.put("progress",instance.path("goal").asLong(1));
            int restore=config.path("restoreTo").asInt();
            if(restore<1 || restore>rules.getHp().getMax())throw new IllegalStateException("挑战恢复值不正确");
            state.getVariables().put("sys.hp",Math.max(JourneyInitialState.numericValue(state,"sys.hp"),restore));
            runtime.with("zeroChallenges").put("used",++used);runtime.put("awaitingChallenge",false);instance.put("resolved",true).put("used",used);
            if (v2) runtime.with("zeroHp").put("used", used).put("resolved", true).put("phase", "HEALTHY");
        }
        ObjectNode receipt=challengeView(snapshot.path("themeConfig"),state).put("success",success).put("gameCompleted",gameCompleted).put("retry",retry);
        ObjectNode stored=submissions.putObject(key);stored.set("request",request);stored.set("receipt",receipt.deepCopy());
        if (v2 && !success && (retry || gameCompleted)) {
            instance.put("progress", 0).put("instanceId", UUID.randomUUID().toString());
        } else if(retry) {instance.put("resolved",true);syncChallenge(snapshot,rules,state,now);}
        return receipt;
    }

    private static boolean r5Policy(JsonNode survival) {
        String policy = survival.path("zeroHpPolicy").asText();
        return java.util.Arrays.asList("THEME_FAIL", "WAIT", "STEPS").contains(policy)
                || "CHALLENGE".equals(policy) && survival.path("zeroChallenge").path("version").asInt() == 2;
    }

    public void syncZeroHp(JsonNode snapshot, JourneyRules rules, State state, long now) {
        JsonNode theme = snapshot.path("themeConfig"), survival = theme.path("gameplayRuntime").path("survival");
        if (!r5Policy(survival) || state.getThemeRuntime().has("result")) return;
        ObjectNode runtime = state.getThemeRuntime();
        if (JourneyInitialState.numericValue(state, "sys.hp") > 0) {
            if (runtime.path("zeroHp").isObject()) runtime.with("zeroHp").put("resolved", true).put("phase", "HEALTHY");
            runtime.put("awaitingChallenge", false);
            if (runtime.path("finishAfterChallenge").asBoolean()) {
                runtime.remove("finishAfterChallenge"); inventory.terminal(theme, state, rules, "COMPLETED", now);
            }
            return;
        }
        String policy = survival.path("zeroHpPolicy").asText();
        JsonNode config = survival.path("CHALLENGE".equals(policy) ? "zeroChallenge" : "WAIT".equals(policy) ? "zeroWait" : "zeroSteps");
        int used = Math.max(runtime.path("zeroHp").path("used").asInt(), runtime.path("zeroChallenges").path("used").asInt());
        boolean exhausted = !"THEME_FAIL".equals(policy) && used >= config.path("limit").asInt();
        if (!runtime.path("zeroHp").isObject() || runtime.path("zeroHp").path("resolved").asBoolean()) {
            ObjectNode zero = runtime.putObject("zeroHp");
            zero.put("version", 1).put("episodeId", UUID.randomUUID().toString()).put("zeroAt", now).put("used", used)
                    .put("policy", policy).put("effectivePolicy", policy).put("resolved", false).put("phase", "RECOVERING");
            zero.put("message", survival.path("zeroMessage").asText()).put("limit", config.path("limit").asInt()).put("restoreTo", config.path("restoreTo").asInt());
            if ("WAIT".equals(policy)) zero.put("deadlineAt", Math.addExact(now, Math.multiplyExact(config.path("minutes").asLong(), 60000L)));
            if ("CHALLENGE".equals(policy)) {
                zero.put("deadlineAt", Math.addExact(now, Math.multiplyExact(config.path("withinSeconds").asLong(), 1000L)));
                ObjectNode challenge = runtime.putObject("challenge");
                challenge.put("instanceId", UUID.randomUUID().toString()).put("episodeId", zero.path("episodeId").asText()).put("nodeId", config.path("challengeNodeId").asLong())
                        .put("progress", 0).put("completions", 0).put("requiredCompletions", config.path("requiredCompletions").asInt())
                        .put("deadlineAt", zero.path("deadlineAt").asLong()).put("used", used).put("limit", config.path("limit").asInt());
                challenge.set("alternativeNodeIds", config.path("alternativeNodeIds").isArray() ? config.path("alternativeNodeIds").deepCopy() : JSON.createArrayNode());
                challenge.put("gameplayKey",config.path("gameplayKey").asText());
                challenge.put("goal", snapshot.path("nodeConfigs").path(config.path("challengeNodeId").asText()).path("ballShake").path("goal").asLong(1));
                runtime.put("awaitingChallenge", true);
            }
            if ("STEPS".equals(policy)) zero.put("phase", "AWAITING_BASELINE").put("required", config.path("steps").asInt()).put("progress", 0);
        }
        ObjectNode zero = runtime.with("zeroHp");
        if ("THEME_FAIL".equals(policy) || exhausted || "CHALLENGE".equals(policy) && now > zero.path("deadlineAt").asLong()) {
            String effective = "THEME_FAIL".equals(policy) ? policy : config.path("thenPolicy").asText();
            zero.put("effectivePolicy", effective).put("phase", "BLOCKED"); runtime.put("awaitingChallenge", false);
            if (runtime.path("challenge").isObject()) runtime.with("challenge").put("disabledReason", exhausted ? "CHALLENGE_LIMIT" : "CHALLENGE_TIMEOUT");
            if ("THEME_FAIL".equals(effective)) {
                inventory.terminal(theme, state, rules, "HP_ZERO", now);
                state.getThemeRuntime().with("gameplay").remove(java.util.Arrays.asList("activeQuest", "activeOperation"));
            }
            return;
        }
        if ("BLOCKED".equals(zero.path("phase").asText())) return;
        if ("WAIT".equals(policy) && now >= zero.path("deadlineAt").asLong()) {
            int restore = config.path("restoreTo").asInt();
            if (restore < 1 || restore > rules.getHp().getMax()) throw new IllegalStateException("归零恢复值不正确");
            inventory.changeStat(state, rules, "sys.hp", restore);
            zero.put("used", used + 1).put("resolved", true).put("phase", "HEALTHY"); state.getVariables().remove("sys.exhausted");
            if (runtime.path("finishAfterChallenge").asBoolean()) {
                runtime.remove("finishAfterChallenge"); inventory.terminal(theme, state, rules, "COMPLETED", now);
            }
        }
    }

    public ObjectNode syncZeroHpSteps(JsonNode snapshot, JourneyRules rules, State state, String episodeId,
            Long memberId, WeRunDecryptor.VerifiedWeRunReading reading, boolean mayEstablish, boolean baselineMemberPresent, long now) {
        State next=state.copy();syncZeroHp(snapshot,rules,next,now);
        ObjectNode zero=next.getThemeRuntime().with("zeroHp");
        if(!"STEPS".equals(zero.path("policy").asText()) || !episodeId.equals(zero.path("episodeId").asText()))throw new IllegalStateException("微信运动恢复不属于当前归零实例");
        if(zero.path("resolved").asBoolean())return zeroView(snapshot.path("themeConfig"),next,now);
        if(!"STEPS".equals(zero.path("effectivePolicy").asText()) || "BLOCKED".equals(zero.path("phase").asText()))throw new IllegalStateException("本次步数恢复已不可用");
        String today=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
        if(!reading.getDays().containsKey(today))throw new IllegalArgumentException("微信运动缺少今天读数");
        long bound=zero.path("recoveryMemberId").asLong();
        boolean establish=bound==0 || !baselineMemberPresent;
        if(establish) {
            if(!mayEstablish)throw new IllegalStateException("仅当前队长可建立微信运动恢复基线");
            zero.put("recoveryMemberId",memberId).put("baselineDay",today).put("baselineSteps",reading.getDays().get(today)).put("progress",0).put("phase","RECOVERING");
            zero.putObject("highWaterByDay").put(today,reading.getDays().get(today));
        } else {
            if(bound!=memberId.longValue())throw new IllegalStateException("请由建立基线的队员同步微信运动");
            if(reading.getWatermarkTime()<zero.path("lastWatermarkTime").asLong())return zeroView(snapshot.path("themeConfig"),state,now);
            java.time.LocalDate day=java.time.LocalDate.parse(zero.path("baselineDay").asText());
            java.time.LocalDate end=java.time.LocalDate.parse(today);
            long progress=zero.path("progress").asLong();ObjectNode high=zero.with("highWaterByDay");
            for(;!day.isAfter(end);day=day.plusDays(1)) {
                String key=day.toString();Integer value=reading.getDays().get(key);
                if(value==null)throw new IllegalArgumentException("微信运动缺少恢复区间的完整日期记录，请重新同步");
                int previous=high.path(key).asInt();
                if(value>previous){progress=Math.addExact(progress,(long)value-previous);high.put(key,value);}
            }
            zero.put("progress",Math.min(progress,zero.path("required").asLong()));
            if(progress>=zero.path("required").asLong()) {
                inventory.changeStat(next,rules,"sys.hp",zero.path("restoreTo").asInt());
                zero.put("used",zero.path("used").asInt()+1).put("resolved",true).put("phase","HEALTHY");next.getVariables().remove("sys.exhausted");
                syncZeroHp(snapshot,rules,next,now);
            }
        }
        zero.put("lastWatermarkTime",reading.getWatermarkTime());
        state.setVariables(next.getVariables());state.setThemeRuntime(next.getThemeRuntime());state.setStatus(next.getStatus());state.setCurrentNodeId(next.getCurrentNodeId());state.setRecommendedNodeId(next.getRecommendedNodeId());
        return zeroView(snapshot.path("themeConfig"),state,now);
    }

    public static ObjectNode zeroView(JsonNode theme, State state, long now) {
        return zeroView(theme,state,now,null,null,"","请刷新当前恢复状态");
    }

    public static ObjectNode zeroView(JsonNode theme, State state, long now, Long currentMemberId,
            Long recoveryMemberId, String recoveryMemberName, String unavailableReason) {
        ObjectNode view = JSON.createObjectNode();
        JsonNode zero = state.getThemeRuntime().path("zeroHp");
        if(!zero.isObject())zero=JSON.createObjectNode().put("policy",theme.path("gameplayRuntime").path("survival").path("zeroHpPolicy").asText())
                .put("phase",state.getThemeRuntime().path("awaitingChallenge").asBoolean()?"RECOVERING":"BLOCKED")
                .put("message",theme.path("gameplayRuntime").path("survival").path("zeroMessage").asText());
        for (String field : new String[]{"episodeId", "policy", "effectivePolicy", "phase", "zeroAt", "deadlineAt", "progress", "required", "used", "limit", "restoreTo", "message"})
            if (zero.has(field)) view.set(field, zero.get(field).deepCopy());
        view.put("serverNow", now);
        if (zero.isObject() && "CHALLENGE".equals(zero.path("policy").asText())) {
            view.put("progress", state.getThemeRuntime().path("challenge").path("completions").asInt());
            view.put("required", state.getThemeRuntime().path("challenge").path("requiredCompletions").asInt());
        }
        String policy=zero.path("policy").asText();JsonNode survival=theme.path("gameplayRuntime").path("survival");
        JsonNode config=survival.path("CHALLENGE".equals(policy)?"zeroChallenge":"WAIT".equals(policy)?"zeroWait":"zeroSteps");
        view.put("thenPolicy",config.path("thenPolicy").asText(policy));
        view.put("recoveryMemberId",recoveryMemberId);view.put("recoveryMemberName",recoveryMemberName==null?"":recoveryMemberName);
        com.fasterxml.jackson.databind.node.ArrayNode actions=view.putArray("allowedActions");
        String reason=unavailableReason==null?"":unavailableReason;
        if(zero.path("limit").asInt()>0 && zero.path("used").asInt()>=zero.path("limit").asInt())reason="恢复次数已用完";
        else if(state.getThemeRuntime().has("result"))reason="本局已结束";
        else if("BLOCKED".equals(zero.path("phase").asText()))reason="本次恢复已不可用";
        else if(zero.path("resolved").asBoolean() || JourneyInitialState.numericValue(state,"sys.hp")>0)reason="当前无需恢复";
        else if(reason.isEmpty()) {
            if("STEPS".equals(policy)) {
                if(recoveryMemberId==null)reason="暂无可同步微信运动的队长";
                else if(recoveryMemberId.equals(currentMemberId))actions.add("ZERO_HP_STEPS");
                else reason="需由 "+(recoveryMemberName==null || recoveryMemberName.isEmpty()?"指定队员":recoveryMemberName)+" 同步微信运动";
            } else if("WAIT".equals(policy) || "CHALLENGE".equals(policy))actions.add("ZERO_HP_SYNC");
            else reason="当前恢复方式不可同步";
        }
        view.put("disabledReason",reason);
        return view;
    }

    public ObjectNode recover(JsonNode theme,JourneyRules rules,State state,String recoveryKey,String eventId) {
        JsonNode recovery=null;
        for(JsonNode row:theme.path("gameplayRuntime").path("survival").path("recoveries"))if(row.path("recoveryKey").asText().equals(recoveryKey))recovery=row;
        if(recovery==null)throw new IllegalArgumentException("主题没有此恢复方式");
        if(state.getThemeRuntime().path("gameplay").has("activeQuest"))throw new IllegalStateException("请先完成或取消当前任务");
        int count=state.getThemeRuntime().path("recoveries").path(recoveryKey).asInt();
        if(count>=recovery.path("limit").asInt())throw new IllegalStateException("此恢复次数已用尽");
        if(recovery.path("actKeys").size()>0) {
            String current=theme.path("gameplayRuntime").path("acts").path(state.getThemeRuntime().path("gameplay").path("actIndex").asInt()).path("actKey").asText();
            boolean eligible=false;for(JsonNode act:recovery.path("actKeys"))if(current.equals(act.asText()))eligible=true;
            if(!eligible)throw new IllegalStateException("当前幕不能使用此恢复方式");
        }
        State next=state.copy();int before=JourneyInitialState.numericValue(next,"sys.hp");
        int amount="REST".equals(recovery.path("kind").asText())?recovery.path("hpDelta").asInt():Math.max(0,recovery.path("hpFloor").asInt()-before);
        int actual=inventory.changeStat(next,rules,"sys.hp",amount);
        boolean consume=actual!=0 || theme.path("gameplayRuntime").path("survival").path("consumeOnNoEffect").asBoolean();
        ObjectNode result=JSON.createObjectNode().put("recoveryKey",recoveryKey).put("hpDelta",actual).put("consumed",consume).put("remaining",recovery.path("limit").asInt()-count-(consume?1:0));
        if(consume) {
            next.getThemeRuntime().with("recoveries").put(recoveryKey,count+1);
            if("RESCUE".equals(recovery.path("kind").asText()))next.getThemeRuntime().put("assisted",true);
            inventory.reconcileHeld(theme,next,rules);
            ObjectNode event=next.getThemeRuntime().withArray("journal").addObject().put("action","RECOVER").put("eventId",eventId);event.set("result",result.deepCopy());
            if(next.getThemeRuntime().path("journal").size()>4096)throw new IllegalStateException("本局事件明细容量已满，请导出并复核");
            state.setVariables(next.getVariables());state.setThemeRuntime(next.getThemeRuntime());
        }
        return result;
    }

    public void ration(JsonNode theme,JourneyRules rules,State state,String actKey,String ownerKey,String eventId) {
        State next=state.copy(),before=state.copy();
        for(JsonNode row:theme.path("gameplayRuntime").path("survival").path("rations"))if(actKey.equals(row.path("actKey").asText())) {
            String slot="ration:"+row.path("rationKey").asText();if(next.getThemeRuntime().path("sources").has(slot))continue;
            String item=row.path("itemId").asText();int quantity=row.path("quantity").asInt();
            ObjectNode result=JSON.createObjectNode().put("rationKey",row.path("rationKey").asText());
            if(next.getThemeRuntime().path("inventory").path(item).asInt()>=quantity) {
                com.fasterxml.jackson.databind.node.ArrayNode costs=JSON.createArrayNode();costs.addObject().put("itemId",item).put("quantity",quantity);
                inventory.consumeAll(theme,next,rules,slot,costs);result.put("itemId",item).put("quantity",quantity);
            } else {
                // 物资不足时扣作者选定的数值（fallback.statKey，可为生命或自定义数值）；旧配置 fallbackHpCost 仍扣生命
                JsonNode fallback=row.path("fallback");
                String key=fallback.has("statKey")?fallback.path("statKey").asText():"sys.hp";
                int amount=fallback.has("statKey")?fallback.path("amount").asInt():row.path("fallbackHpCost").asInt();
                if(amount>0) {
                    int minimum=key.startsWith("counter.")?JourneyInitialState.numericAttribute(rules,key).path("min").asInt():0;
                    int current=JourneyInitialState.numericValue(next,key);
                    int changed=inventory.changeStat(next,rules,key,-Math.max(0,Math.min(current-minimum,amount)));
                    if("sys.hp".equals(key))result.put("hpDelta",changed); else result.put("statKey",key).put("statDelta",changed);
                } else result.put("hpDelta",0);
            }
            next.getThemeRuntime().with("sources").put(slot,true);
            ObjectNode event=next.getThemeRuntime().withArray("journal").addObject().put("action","RATION").put("eventId",eventId);event.set("result",result);
        }
        // 幕末配给与任务/章节在调用方按同一次净变化联动，避免拆段重复扣HP。
        state.setVariables(next.getVariables());state.setTags(next.getTags());state.setThemeRuntime(next.getThemeRuntime());
    }
}
