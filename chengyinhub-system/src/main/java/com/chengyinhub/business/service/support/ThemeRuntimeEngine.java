package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.*;

/** 背包、可信触发与终局共用旅程 State；写侧由路线服务的同一次 CAS 提交。 */
public final class ThemeRuntimeEngine {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TopicRouteEngine route = new TopicRouteEngine();
    public void initialize(JsonNode config, TopicRouteEngine.State state) {
        if (config == null || config.isMissingNode() || !config.isObject()) return;
        ObjectNode t = state.getThemeRuntime();
        if (!t.isEmpty()) return;
        t.putObject("inventory"); t.putObject("acquired"); t.putObject("sources"); t.putObject("pending");
        t.putArray("events"); t.putArray("journal");
        state.getVariables().put("sys.actions", 0);
    }
    public void signal(JsonNode config, TopicRouteEngine.State state, JourneyRules rules,
                       String source, Long nodeId, String step, String option, int outcome, String sourceKey) {
        if (!mutable(config,state)) return;
        ObjectNode t = state.getThemeRuntime();
        String signalKey = "signal:" + sourceKey;
        if (t.with("sources").has(signalKey)) return;
        t.with("sources").put(signalKey, true);
        if(!config.has("actionSources") || contains(config.path("actionSources"),source))state.getVariables().put("sys.actions", number(state, "sys.actions") + 1);
        for (JsonNode g : config.path("grants")) {
            if (!source.equals(g.path("source").asText()) || !conditions(g.path("when"),state)) continue;
            if (nodeId != null && g.path("nodeId").asLong() != nodeId) continue;
            if ("CHOICE".equals(source) && (!step.equals(g.path("step").asText()) || !option.equals(g.path("option").asText()))) continue;
            if ("DRAW".equals(source) && outcome != g.path("outcome").asInt()) continue;
            grant(config, state, rules, "grant:" + g.path("id").asText(), g.path("itemId").asText(), g.path("quantity").asInt());
        }
        events(config, state, rules);
    }
    public void events(JsonNode config, TopicRouteEngine.State state, JourneyRules rules) {
        if (!mutable(config,state)) return;
        // 单次有限遍历到固定点；事件只能触发一次，因此最多 events.size() 轮。
        for (int pass = 0; pass <= config.path("events").size(); pass++) {
            boolean changed = false;
            for (JsonNode e : config.path("events")) {
                String key = "event:" + e.path("id").asText();
                if (state.getThemeRuntime().with("sources").has(key) || !conditions(e.path("when"), state)) continue;
                state.getThemeRuntime().with("sources").put(key, true);
                route.applyEffects(e.path("effects"), state);
                int i = 0;
                for (JsonNode g : e.path("grants")) grant(config,state,rules,key + ":" + i++,g.path("itemId").asText(),g.path("quantity").asInt());
                ObjectNode record = JSON.createObjectNode(); record.put("id",e.path("id").asText()); record.put("content",e.path("content").asText());
                record.put("status", "RECORDED"); state.getThemeRuntime().withArray("events").add(record);
                changed = true;
            }
            if (!changed) break;
        }
    }
    private void grant(JsonNode config, TopicRouteEngine.State state, JourneyRules rules, String source, String id, int qty) {
        requireUnfinished(state);
        ObjectNode t = state.getThemeRuntime();
        if (t.with("sources").has(source)) return;
        JsonNode item = item(config,id);
        requireStatEnabled(config, item, rules);
        t.with("sources").put(source,true);
        if (t.has("rewardSettlement")) {
            ObjectNode batch=t.with("rewardSettlement");
            batch.with("grants").put(id,Math.addExact(batch.path("grants").path(id).asInt(),qty));
            int dropped=Math.min(qty,batch.path("remaining").path(id).asInt());
            batch.with("remaining").put(id,batch.path("remaining").path(id).asInt()-dropped);
            batch.with("actualGrants").put(id,Math.addExact(batch.path("actualGrants").path(id).asInt(),qty-dropped));
            batch.withArray("rewardSources").addObject().put("source",source).put("itemId",id).put("quantity",qty).put("discarded",dropped);
            qty-=dropped;if(qty==0)return;
        }
        ObjectNode pending = t.with("pending").putObject(source); pending.put("itemId",id); pending.put("quantity",qty);
        receive(config,state,rules,source,t.has("rewardSettlement"));
    }
    /** 只存在于一次动作的克隆状态中，结束前必须移除；所有奖励来源共用显式放弃额度。 */
    public void beginRewards(JsonNode config,TopicRouteEngine.State state,JsonNode discarded) {
        if(state.getThemeRuntime().has("rewardSettlement"))return;
        ObjectNode batch=state.getThemeRuntime().putObject("rewardSettlement");batch.putObject("remaining");
        batch.set("discardRewards",rewardSelection(config,discarded));
        for(JsonNode row:batch.path("discardRewards"))batch.with("remaining").put(row.path("itemId").asText(),row.path("quantity").asInt());
    }
    public ArrayNode rewardSelection(JsonNode config,JsonNode rows) {
        ArrayNode result=JSON.createArrayNode();if(rows==null || rows.isNull())return result;
        if(!rows.isArray() || rows.size()>200)throw new IllegalArgumentException("放弃物资须为数组");
        Map<String,Integer> amounts=new TreeMap<>();
        for(JsonNode row:rows) {
            if(!row.isObject() || row.size()!=2 || !row.has("itemId") || !row.has("quantity") || !row.path("itemId").isTextual()
                    || !row.path("quantity").isIntegralNumber() || !row.path("quantity").canConvertToInt() || row.path("quantity").asInt()<1)throw new IllegalArgumentException("放弃物资必须是物资与正整数数量");
            String id=row.path("itemId").asText();item(config,id);
            if(amounts.put(id,row.path("quantity").asInt())!=null)throw new IllegalArgumentException("放弃物资不能重复");
        }
        amounts.forEach((id,quantity)->result.addObject().put("itemId",id).put("quantity",quantity));return result;
    }
    public ObjectNode finishRewards(TopicRouteEngine.State state) {
        ObjectNode batch=(ObjectNode)state.getThemeRuntime().get("rewardSettlement");
        for(JsonNode amount:batch.path("remaining"))if(amount.asInt()>0)throw new IllegalArgumentException("只能放弃本次实际触发的奖励数量");
        if("COMPLETED".equals(state.getStatus()) && state.getThemeRuntime().path("pending").size()>0)throw new IllegalStateException("请先解决全部待领奖励再完成本局");
        state.getThemeRuntime().remove("rewardSettlement");batch.remove("remaining");return batch;
    }
    public ObjectNode claimPending(JsonNode config,TopicRouteEngine.State state,String source,JsonNode discardOwned) {
        TopicRouteEngine.State next=state.copy();ObjectNode t=next.getThemeRuntime();JsonNode pending=t.path("pending").get(source);
        if(pending==null || !t.path("sources").path(source).asBoolean() || !pending.path("itemId").isTextual()
                || !pending.path("quantity").isIntegralNumber() || !pending.path("quantity").canConvertToInt() || pending.path("quantity").asInt()<1)throw new IllegalStateException("没有真实来源的待领取内容");
        ArrayNode discarded=rewardSelection(config,discardOwned);
        if(!discarded.isEmpty() && config.has("allowDiscard") && !config.path("allowDiscard").asBoolean())throw new IllegalStateException("本主题不允许丢弃物品");
        for(JsonNode row:discarded) {
            String id=row.path("itemId").asText();int quantity=row.path("quantity").asInt();
            if(!"ITEM".equals(item(config,id).path("kind").asText()) || t.path("inventory").path(id).asInt()<quantity)throw new IllegalStateException("没有足够的本人持有物资");
            int left=t.path("inventory").path(id).asInt()-quantity;if(left==0)t.with("inventory").remove(id);else t.with("inventory").put(id,left);
            journal(next,"DISCARD_OWNED",id,quantity);
        }
        String id=pending.path("itemId").asText();int quantity=pending.path("quantity").asInt();JsonNode content=item(config,id);
        if("ITEM".equals(content.path("kind").asText()) && (long)used(config,next)+(long)quantity*content.path("slotCost").asInt(1)>config.path("capacity").asInt())throw new IllegalStateException("背包容量不足，领取未结算");
        t.with("inventory").put(id,Math.addExact(t.path("inventory").path(id).asInt(),quantity));
        t.with("acquired").put(id,Math.addExact(t.path("acquired").path(id).asInt(),quantity));t.with("pending").remove(source);
        project(config,next);journal(next,"RECEIVE",id,quantity);
        state.setVariables(next.getVariables());state.setThemeRuntime(t);
        ObjectNode receipt=JSON.createObjectNode().put("source",source).put("itemId",id).put("quantity",quantity);receipt.set("discardOwned",discarded);return receipt;
    }
    public void receive(JsonNode config, TopicRouteEngine.State state, JourneyRules rules, String source, boolean required) {
        requireUnfinished(state);
        ObjectNode t = state.getThemeRuntime(); JsonNode pending = t.with("pending").get(source);
        if (pending == null) throw new IllegalStateException("没有待领取内容");
        String id = pending.path("itemId").asText(); int qty = pending.path("quantity").asInt(); JsonNode item = item(config,id);
        if ("ITEM".equals(item.path("kind").asText()) && (long)used(config,state) + (long)qty * item.path("slotCost").asInt(1) > config.path("capacity").asInt()) {
            if (required) throw new IllegalStateException("背包已满，请先使用或丢弃物品"); return;
        }
        requireStatEnabled(config,item,rules);
        if (item.has("statKey")) JourneyInitialState.numericValue(state, stat(item));
        t.with("inventory").put(id,t.with("inventory").path(id).asInt() + qty);
        t.with("acquired").put(id,t.with("acquired").path(id).asInt() + qty);
        t.with("pending").remove(source); project(config,state);
        if (item.path("wear").asBoolean()) {
            if ("ONCE".equals(item.path("mode").asText())) change(state,rules,item,qty);
            else if("HELD".equals(item.path("mode").asText())) reconcileHeld(config,state,rules);
        }
        journal(state,"RECEIVE",id,qty);
    }
    public void use(JsonNode config, TopicRouteEngine.State state, JourneyRules rules, String id) {
        requireUnfinished(state);
        JsonNode item = item(config,id); requireStatEnabled(config,item,rules);
        if (!"ITEM".equals(item.path("kind").asText()) || (!item.has("stat") && !item.has("statKey")) || item.path("wear").asBoolean()) throw new IllegalStateException("此内容不能点击使用");
        requireHeld(state,id); int previous = number(state,stat(item));
        change(state,rules,item,1);
        if (number(state,stat(item)) == previous && !config.path("gameplayRuntime").path("survival").path("consumeOnNoEffect").asBoolean())
            throw new IllegalStateException("当前数值下物品没有实际效果，不消耗物资");
        remove(state,id); project(config,state); journal(state,"USE",id,1); events(config,state,rules);
    }
    public void discard(JsonNode config, TopicRouteEngine.State state, JourneyRules rules, String id) {
        requireUnfinished(state);
        if(config.has("allowDiscard") && !config.path("allowDiscard").asBoolean())throw new IllegalStateException("本主题不允许丢弃物品");
        JsonNode item = item(config,id);
        if (!"ITEM".equals(item.path("kind").asText())) throw new IllegalStateException("收藏勋章不可丢弃");
        requireHeld(state,id); remove(state,id);
        if (item.path("wear").asBoolean() && "HELD".equals(item.path("mode").asText())) reconcileHeld(config,state,rules);
        project(config,state); journal(state,"DISCARD",id,1); events(config,state,rules);
    }
    private void remove(TopicRouteEngine.State state,String id) { ObjectNode inventory=state.getThemeRuntime().with("inventory"); int qty=inventory.path(id).asInt()-1; if(qty==0)inventory.remove(id);else inventory.put(id,qty); }
    private void change(TopicRouteEngine.State state,JourneyRules rules,JsonNode item,int qty) {
        changeStat(state, rules, stat(item), Math.toIntExact((long)item.path("delta").asInt()*qty));
    }
    /** 持续修正独立记账。原旅程动作的实际变化同步回基础值，不用反向增减来解除。 */
    public void reconcileHeld(JsonNode config,TopicRouteEngine.State state,JourneyRules rules) {
        if(!mutable(config,state))return;
        ObjectNode modifiers=state.getThemeRuntime().with("modifiers");
        Set<String> targets = new LinkedHashSet<>(Arrays.asList("sys.hp", "sys.luck"));
        for (JsonNode item : catalog(config)) if (item.has("statKey") && item.path("wear").asBoolean() && "HELD".equals(item.path("mode").asText())) targets.add(stat(item));
        for(String key:targets) {
            boolean custom = key.startsWith("counter.");
            if (!custom && (!(config.has("gameplayRuntime") ? rules.hasRuntimeState() : rules.isCheckEnabled()) || ("sys.hp".equals(key) ? !rules.getHp().isEnabled() : !rules.getLuck().isEnabled()))) continue;
            int min = custom ? JourneyInitialState.numericAttribute(rules, key).path("min").asInt() : 0;
            int max = maximum(rules, key);
            ObjectNode ledger=modifiers.with(key); int current=custom ? JourneyInitialState.numericValue(state,key) : number(state,key);
            long delta=0;
            for(JsonNode item:catalog(config)) if(item.path("wear").asBoolean() && "HELD".equals(item.path("mode").asText()) && key.equals(stat(item)))
                delta+=(long)item.path("delta").asInt()*state.getThemeRuntime().with("inventory").path(item.path("id").asText()).asInt();
            int applied=ledger.path("applied").asInt();
            int base=clamp((long)current-applied,min,max);
            // 库存未改变时保持原动作的实际结果；不能因重算上限而隐式回血。
            int effective=ledger.has("requested") && ledger.path("requested").asLong()==delta ? current : clamp(base+delta,min,max);
            ledger.put("base",base);ledger.put("effective",effective);ledger.put("applied",effective-base);ledger.put("requested",delta);state.getVariables().put(key,effective);
        }
    }
    public boolean conditions(JsonNode conditions,TopicRouteEngine.State state) {
        return route.conditionsPass(conditions,state);
    }
    public void terminal(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,String reason,long now) {
        TopicRouteEngine.State next=state.copy();terminalResult(config,next,rules,reason,now);
        state.setVariables(next.getVariables());state.setThemeRuntime(next.getThemeRuntime());state.setStatus(next.getStatus());state.setCurrentNodeId(next.getCurrentNodeId());state.setRecommendedNodeId(next.getRecommendedNodeId());
    }
    public void terminalStory(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,JsonNode ending,long now) {
        ObjectNode effective=((ObjectNode)config).deepCopy();
        effective.putArray("endings").addObject().put("id",ending.path("code").asText()).put("title",ending.path("title").asText())
                .put("summary",ending.path("summary").asText()).put("fallback",true).put("success",true);
        terminalResult(effective,state,rules,"COMPLETED",now);
        state.getThemeRuntime().with("result").put("storyEndingId",ending.path("code").asText()).put("endingChapterId",ending.path("chapterId").asLong());
        state.setStatus("COMPLETED");
    }
    private void terminalResult(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,String reason,long now) {
        if(config==null || !config.isObject() || state.getThemeRuntime().has("result"))return;
        boolean completed="COMPLETED".equals(reason);
        JsonNode chosen=null,fallback=null;int priority=Integer.MIN_VALUE;
        for(JsonNode ending:config.path("endings")) {
            if(ending.path("fallback").asBoolean()) {fallback=ending;continue;}
            if((completed || !ending.path("success").asBoolean()) && (!ending.has("reasons") || contains(ending.path("reasons"),reason)) && conditions(ending.path("when"),state)&&ending.path("priority").asInt()>=priority){chosen=ending;priority=ending.path("priority").asInt();}
        }
        if(chosen==null)chosen=fallback;
        boolean success=completed&&(chosen==null||chosen.path("success").asBoolean());
        for(JsonNode g:config.path("grants")) if("ENDING".equals(g.path("source").asText()) && conditions(g.path("when"),state)
                && (!g.has("reasons")?completed:contains(g.path("reasons"),reason))
                && (!g.has("endingId") || (chosen!=null && g.path("endingId").asText().equals(chosen.path("id").asText())))
                && (g.has("success")?g.path("success").asBoolean()==success:success))
            grant(config,state,rules,"grant:"+g.path("id").asText(),g.path("itemId").asText(),g.path("quantity").asInt());
        if(success)for(JsonNode medal:config.path("legacyMedals"))if("FINISH".equals(medal.path("legacySource").asText()))legacyMedal(config,state,rules,medal.path("id").asText());
        if(completed && state.getThemeRuntime().path("pending").size()>0)throw new IllegalStateException("请先解决全部待领奖励再完成本局");
        ObjectNode result=state.getThemeRuntime().putObject("result");
        result.put("reason",reason);result.put("endedAt",now);result.put("success",success);
        if(chosen!=null){result.put("endingId",chosen.path("id").asText());result.put("title",chosen.path("title").asText());result.put("summary",chosen.path("summary").asText());}
        else {result.put("title",completed?"本局完成":"本局结束");result.put("summary","");}
        result.put("sceneId",config.path("sceneId").asText());result.put("sceneName",config.path("sceneName").asText());
        result.put("hp",number(state,"sys.hp"));result.put("luck",number(state,"sys.luck"));result.put("actions",number(state,"sys.actions"));
        result.put("hpName",config.path("hpName").asText("生命值"));result.put("luckName",config.path("luckName").asText("幸运值"));
        result.set("attributes",JSON.valueToTree(JourneyInitialState.playerView(rules,state)));
        result.set("inventory",state.getThemeRuntime().path("inventory").deepCopy());result.set("acquired",state.getThemeRuntime().path("acquired").deepCopy());
        result.set("catalog",catalog(config).deepCopy());result.set("card",config.path("card").deepCopy());result.put("endingCount",config.path("endings").size());result.put("rescues",state.getThemeRuntime().path("rescues").asInt());
        state.setStatus(success?"COMPLETED":"ENDED");state.setCurrentNodeId(null);state.setRecommendedNodeId(null);
    }
    public int reduceHpCost(JsonNode config,TopicRouteEngine.State state,int cost) {
        return reduceStatCost(config, state, "sys.hp", cost);
    }
    public void chapterStart(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,JsonNode ch,long now) {
        if(!mutable(config,state))return;
        ObjectNode states=state.getThemeRuntime().with("chapters");String key=String.valueOf(ch.path("index").asInt());
        if(states.has(key))return;
        ObjectNode progress=states.putObject(key);progress.put("startedAt",now);progress.put("steps",0);progress.put("ended",false);
        chapterGrants(config,state,rules,ch,"START");
    }
    public void chapterEnd(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,JsonNode ch,long now) {
        if(!mutable(config,state))return;
        chapterStart(config,state,rules,ch,now);
        ObjectNode progress=state.getThemeRuntime().with("chapters").with(String.valueOf(ch.path("index").asInt()));
        if(progress.path("ended").asBoolean())return;
        chapterTick(config,state,rules,now);
        progress.put("ended",true);
        if((config.has("gameplayRuntime") ? rules.hasRuntimeState() : rules.isCheckEnabled()) && rules.getHp().isEnabled())state.getVariables().put("sys.hp",Math.max(0,number(state,"sys.hp")-reduceHpCost(config,state,ch.path("hpCost").asInt())));
        if((config.has("gameplayRuntime") ? rules.hasRuntimeState() : rules.isCheckEnabled()) && rules.getLuck().isEnabled())state.getVariables().put("sys.luck",Math.max(0,number(state,"sys.luck")-ch.path("luckCost").asInt()));
        for (JsonNode cost : ch.path("statCosts")) {
            String key = cost.path("statKey").asText();
            changeStat(state, rules, key, -reduceStatCost(config, state, key, cost.path("amount").asInt()));
        }
        chapterGrants(config,state,rules,ch,"END");
        reconcileHeld(config,state,rules);
    }
    private void chapterGrants(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,JsonNode ch,String timing) {
        for(JsonNode g:ch.path("grants"))if(timing.equals(g.path("timing").asText()))grant(config,state,rules,"chapter:"+ch.path("index").asInt()+":"+timing+":"+g.path("itemId").asText(),g.path("itemId").asText(),g.path("quantity").asInt());
    }
    public void chapterSteps(TopicRouteEngine.State state,int delta) {
        if(delta<=0 || state.getThemeRuntime().isEmpty() || state.getThemeRuntime().has("result"))return;
        for(JsonNode p:state.getThemeRuntime().with("chapters"))if(!p.path("ended").asBoolean())((ObjectNode)p).put("steps",Math.min(999999,p.path("steps").asInt()+delta));
    }
    public void chapterTick(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,long now) {
        if(!mutable(config,state))return;
        for(JsonNode ch:config.path("chapters")) {
            JsonNode p=state.getThemeRuntime().path("chapters").path(String.valueOf(ch.path("index").asInt()));
            if(!p.isObject() || p.path("ended").asBoolean())continue;
            for(JsonNode egg:ch.path("eggs")) {
                long progress="TIME".equals(egg.path("trigger").asText())?Math.max(0,now-p.path("startedAt").asLong())/60000:p.path("steps").asInt();
                String key="egg:"+ch.path("index").asInt()+":"+egg.path("id").asText();
                if(progress<egg.path("threshold").asInt() || state.getThemeRuntime().with("sources").has(key))continue;
                state.getThemeRuntime().with("sources").put(key,true);
                int i=0;for(JsonNode r:egg.path("rewards"))grant(config,state,rules,key+":"+i++,r.path("itemId").asText(),r.path("quantity").asInt());
                ObjectNode record=state.getThemeRuntime().withArray("events").addObject();record.put("id",key);record.put("name",egg.path("name").asText());record.put("content",egg.path("content").asText());record.put("status","RECORDED");
            }
        }
    }
    public ObjectNode view(JsonNode config,TopicRouteEngine.State state) {
        ObjectNode view=JSON.createObjectNode();view.put("capacity",config.path("capacity").asInt());view.put("used",used(config,state));view.put("allowDiscard",!config.has("allowDiscard") || config.path("allowDiscard").asBoolean());
        if (state.getThemeRuntime().path("lastOutcome").isObject()) view.set("lastOutcome",state.getThemeRuntime().path("lastOutcome").deepCopy());
        if (state.getThemeRuntime().path("zeroHp").isObject()) view.set("zeroHp", GameplaySurvivalEngine.zeroView(config, state, System.currentTimeMillis()));
        view.put("hpName",config.path("hpName").asText(""));view.put("luckName",config.path("luckName").asText(""));view.put("actionName",config.path("actionName").asText(""));
        ArrayNode contents=view.putArray("contents");
        for(JsonNode item:catalog(config)) {int quantity=state.getThemeRuntime().path("inventory").path(item.path("id").asText()).asInt();if(quantity==0)continue;
            ObjectNode row=item.deepCopy();row.put("slotCost", "ITEM".equals(item.path("kind").asText()) ? item.path("slotCost").asInt(1) : 0);row.put("quantity",quantity);row.put("worn",item.path("wear").asBoolean());contents.add(row);}
        view.put("awaitingRescue",state.getThemeRuntime().path("awaitingRescue").asBoolean());view.put("rescues",state.getThemeRuntime().path("rescues").asInt());view.put("actions",number(state,"sys.actions"));view.put("actionLimit",config.path("actionLimit").asInt());
        view.set("journal",state.getThemeRuntime().path("journal").deepCopy());
        ObjectNode pending=state.getThemeRuntime().path("pending").isObject() ? state.getThemeRuntime().path("pending").deepCopy() : JSON.createObjectNode();
        pending.fields().forEachRemaining(entry->{JsonNode item=item(config,entry.getValue().path("itemId").asText());if(item!=null){ObjectNode row=(ObjectNode)entry.getValue();row.put("name",item.path("name").asText());int slots="ITEM".equals(item.path("kind").asText())?item.path("slotCost").asInt(1):0;row.put("slotCost",slots);row.put("slots",Math.multiplyExact(slots,row.path("quantity").asInt()));}});
        view.set("pending",pending);view.set("events",state.getThemeRuntime().path("events").deepCopy());
        if(state.getThemeRuntime().has("result"))view.set("result",state.getThemeRuntime().get("result").deepCopy());
        return view;
    }
    /** 分享绘制只取白名单字段；不返回私有日志、节点答案、条件、玩家标识或家位置。 */
    public ObjectNode card(JsonNode result) {
        ObjectNode card=JSON.createObjectNode();card.put("reason",result.path("reason").asText());card.put("success",result.path("success").asBoolean());card.put("endedAt",result.path("endedAt").asLong());
        JsonNode options=result.path("card");if(options.has("cover"))card.put("cover",options.path("cover").asText());
        JsonNode fields=options.path("fields");
        for(String field:Arrays.asList("scene","ending","hp","luck","actions","medals","items","story","rescue")) {
            if(fields.isArray()?!contains(fields,field):!"ending".equals(field))continue;
            if("scene".equals(field)){card.put("scene",result.path("sceneName").asText());}
            else if("ending".equals(field)){card.put("title",result.path("title").asText());card.put("summary",result.path("summary").asText());}
            else if("hp".equals(field)||"luck".equals(field)){card.put(field,result.path(field).asInt());card.put(field+"Name",result.path(field+"Name").asText());}
            else if("rescue".equals(field))card.put("rescues",result.path("rescues").asInt());
            else if("actions".equals(field))card.put("actions",result.path("actions").asInt());
            else if("story".equals(field)) {ArrayNode lines=card.putArray("epilogues");for(JsonNode row:result.path("story").path("epilogues")){ObjectNode line=lines.addObject();line.put("name",row.path("name").asText());line.put("line",row.path("line").asText());}card.set("exhibits",result.path("story").path("exhibits").deepCopy());}
            else {ArrayNode contents=card.putArray(field);for(JsonNode item:result.path("catalog")){boolean medal="MEDAL".equals(item.path("kind").asText());if(medal!= "medals".equals(field))continue;String id=item.path("id").asText();int qty=result.path(medal?"acquired":"inventory").path(id).asInt();if(qty>0){ObjectNode row=contents.addObject();row.put("name",item.path("name").asText());row.put("image",item.path("image").asText());row.put("quantity",qty);}}}
        }
        return card;
    }
    private ArrayNode catalog(JsonNode config) {ArrayNode all=JSON.createArrayNode();for(JsonNode item:config.path("items"))all.add(item);for(JsonNode medal:config.path("legacyMedals"))all.add(medal);return all;}
    // 路线可先标记 COMPLETED，可信终点发放后才生成 result；result 才是主题终局的冻结边界。
    private boolean mutable(JsonNode config,TopicRouteEngine.State state) {
        return config!=null && config.isObject() && !state.getThemeRuntime().isEmpty() && !state.getThemeRuntime().has("result");
    }
    private void requireUnfinished(TopicRouteEngine.State state) {
        if(state.getThemeRuntime().has("result"))throw new IllegalStateException("本局已结束");
    }
    public void legacyMedal(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,String id) {grant(config,state,rules,"legacy:"+id,id,1);}
    private boolean contains(JsonNode values,String text){for(JsonNode value:values)if(text.equals(value.asText()))return true;return false;}
    public int used(JsonNode config,TopicRouteEngine.State state) {long total=0;for(JsonNode item:catalog(config))if("ITEM".equals(item.path("kind").asText()))total+=(long)state.getThemeRuntime().path("inventory").path(item.path("id").asText()).asInt()*item.path("slotCost").asInt(1);return Math.toIntExact(total);}
    private JsonNode item(JsonNode config,String id) {for(JsonNode i:catalog(config))if(id.equals(i.path("id").asText()))return i;throw new IllegalArgumentException("物资不在本局目录中");}
    private void requireHeld(TopicRouteEngine.State state,String id){if(state.getThemeRuntime().path("inventory").path(id).asInt()<1)throw new IllegalStateException("没有可用物品");}
    private void requireStatEnabled(JsonNode config,JsonNode item,JourneyRules rules){if(item.has("statKey")){JourneyInitialState.numericAttribute(rules,stat(item));return;}if(!"ITEM".equals(item.path("kind").asText()) || !item.has("stat"))return;if(!(config.has("gameplayRuntime")?rules.hasRuntimeState():rules.isCheckEnabled()) || ("HP".equals(item.path("stat").asText())?!rules.getHp().isEnabled():!rules.getLuck().isEnabled()))throw new IllegalStateException("主题未开启物品对应属性");}
    private String stat(JsonNode item){return item.has("statKey")?item.path("statKey").asText():"HP".equals(item.path("stat").asText())?"sys.hp":"sys.luck";}
    private int number(TopicRouteEngine.State s,String k){Number n=s.getVariables().get(k);return n==null?0:n.intValue();}
    static int clamp(long value,int min,int max){return (int)Math.max(min,Math.min(max,value));}
    private int maximum(JourneyRules rules, String key) {return "sys.hp".equals(key)?rules.getHp().getMax():"sys.luck".equals(key)?rules.getLuck().getMax():JourneyInitialState.numericAttribute(rules,key).path("max").asInt();}
    /** 原HP/LUCK和自定义数值共用封顶数学；返回实际差量，不自动触发联动。 */
    public int changeStat(TopicRouteEngine.State state, JourneyRules rules, String key, int delta) {
        boolean custom = !"sys.hp".equals(key) && !"sys.luck".equals(key);
        int min = custom ? JourneyInitialState.numericAttribute(rules,key).path("min").asInt() : 0;
        if (!custom && (!rules.hasRuntimeState() || ("sys.hp".equals(key)?!rules.getHp().isEnabled():!rules.getLuck().isEnabled()))) throw new IllegalArgumentException("数值目标未启用");
        int previous = custom ? JourneyInitialState.numericValue(state,key) : number(state,key);
        int next = clamp((long)previous+delta,min,maximum(rules,key));
        state.getVariables().put(key,next);
        return next-previous;
    }
    /** 任务费用与整包奖励使用原库存算法；坏输入或中途效果失败不改变调用方State。 */
    public ObjectNode settle(JsonNode config, TopicRouteEngine.State state, JourneyRules rules,
                             String settlementKey, JsonNode costs, JsonNode grants) {
        if (config == null || !config.isObject() || state == null || settlementKey == null || settlementKey.trim().isEmpty() || settlementKey.length() > 160)
            throw new IllegalArgumentException("结算需要本局配置、状态和稳定业务槽");
        if (costs == null || !costs.isObject() || grants == null || !grants.isArray()) throw new IllegalArgumentException("结算费用/奖励格式不正确");
        ObjectNode request = JSON.createObjectNode(); request.set("costs", costs.deepCopy()); request.set("grants", grants.deepCopy());
        JsonNode prior = state.getThemeRuntime().path("settlements").path(settlementKey);
        if (!prior.isMissingNode()) {
            if (!prior.path("request").equals(request)) throw new IllegalStateException("结算槽已使用，不能更换费用或奖励");
            return ((ObjectNode)prior.path("receipt")).deepCopy();
        }
        Set<String> fields = ThemeRuntimeConfig.set("hpCost", "luckCost", "statCosts", "resourceCosts", "actionCost");
        costs.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) throw new IllegalArgumentException("结算费用不支持字段：" + field); });
        ThemeRuntimeConfig.statCosts(costs);
        Map<String,Integer> paid = settlementAmounts(config, costs.path("resourceCosts"), false);
        Map<String,Integer> rewards = settlementAmounts(config, grants, true);
        TopicRouteEngine.State next = state.copy();
        if (next.getThemeRuntime().isEmpty()) initialize(config, next);
        ObjectNode inventory = next.getThemeRuntime().with("inventory");
        for (Map.Entry<String,Integer> cost : paid.entrySet()) {
            if (inventory.path(cost.getKey()).asInt() < cost.getValue()) throw new IllegalStateException("材料不足：" + cost.getKey()+"，缺少"+(cost.getValue()-inventory.path(cost.getKey()).asInt()));
            int quantity = inventory.path(cost.getKey()).asInt()-cost.getValue();
            if (quantity == 0) inventory.remove(cost.getKey()); else inventory.put(cost.getKey(), quantity);
        }
        reconcileHeld(config,next,rules);
        long slots = used(config,next);
        for (Map.Entry<String,Integer> reward : rewards.entrySet()) {
            JsonNode row = item(config,reward.getKey()); requireStatEnabled(config,row,rules);
            if (row.has("statKey")) JourneyInitialState.numericValue(next,stat(row));
            int dropped=Math.min(reward.getValue(),next.getThemeRuntime().path("rewardSettlement").path("remaining").path(reward.getKey()).asInt());
            if ("ITEM".equals(row.path("kind").asText())) slots += (long)(reward.getValue()-dropped)*row.path("slotCost").asInt(1);
            if ((long)inventory.path(reward.getKey()).asInt()+reward.getValue() > Integer.MAX_VALUE
                    || (long)next.getThemeRuntime().path("acquired").path(reward.getKey()).asInt()+reward.getValue() > Integer.MAX_VALUE)
                throw new IllegalStateException("物资数量超过本局容量");
        }
        if (slots > config.path("capacity").asInt()) throw new IllegalStateException("整包奖励超过背包容量");
        int actions = Math.addExact(number(next,"sys.actions"), settlementInteger(costs,"actionCost"));
        if (config.has("actionLimit") && actions > config.path("actionLimit").asInt()) throw new IllegalStateException("行动次数不足，缺少"+(actions-config.path("actionLimit").asInt()));
        ObjectNode receipt = JSON.createObjectNode(); receipt.put("settlementKey",settlementKey);
        ObjectNode deltas = receipt.putObject("statDeltas");
        for (String key : Arrays.asList("sys.hp", "sys.luck")) {
            int cost = settlementInteger(costs, "sys.hp".equals(key)?"hpCost":"luckCost");
            if (cost == 0) continue;
            cost = reduceStatCost(config,next,key,cost);
            if (JourneyInitialState.numericValue(next,key) < cost) throw new IllegalStateException("数值费用不足：" + key+"，缺少"+(cost-JourneyInitialState.numericValue(next,key)));
            deltas.put(key,changeStat(next,rules,key,-cost));
        }
        for (JsonNode row : costs.path("statCosts")) {
            String key = row.path("statKey").asText(); int cost = reduceStatCost(config,next,key,row.path("amount").asInt());
            int min = JourneyInitialState.numericAttribute(rules,key).path("min").asInt();
            if ((long)JourneyInitialState.numericValue(next,key)-min < cost) throw new IllegalStateException("数值费用不足：" + key+"，缺少"+((long)cost-JourneyInitialState.numericValue(next,key)+min));
            deltas.put(key,changeStat(next,rules,key,-cost));
        }
        next.getVariables().put("sys.actions",actions);
        for (Map.Entry<String,Integer> cost : paid.entrySet()) journal(next,"CONSUME",cost.getKey(),cost.getValue());
        // 成本后的实际变化回写持有账本，奖励仍调用原接收路径。
        reconcileHeld(config,next,rules); project(config,next);
        for (Map.Entry<String,Integer> reward : rewards.entrySet()) grant(config,next,rules,
                "settlement:"+settlementKey+":"+reward.getKey(),reward.getKey(),reward.getValue());
        receipt.put("used",used(config,next)); receipt.put("actions",actions);
        receipt.set("resourceCosts",JSON.valueToTree(paid)); receipt.set("grants",JSON.valueToTree(rewards));
        ObjectNode stored = next.getThemeRuntime().with("settlements").putObject(settlementKey);
        stored.set("request",request); stored.set("receipt",receipt.deepCopy());
        state.setVariables(next.getVariables()); state.setTags(next.getTags()); state.setThemeRuntime(next.getThemeRuntime());
        return receipt;
    }
    public ObjectNode grantAll(JsonNode config, TopicRouteEngine.State state, JourneyRules rules, String key, JsonNode grants) {
        return settle(config,state,rules,key,JSON.createObjectNode(),grants);
    }
    public ObjectNode consumeAll(JsonNode config, TopicRouteEngine.State state, JourneyRules rules, String key, JsonNode resourceCosts) {
        ObjectNode costs=JSON.createObjectNode(); costs.set("resourceCosts",resourceCosts);
        return settle(config,state,rules,key,costs,JSON.createArrayNode());
    }
    private Map<String,Integer> settlementAmounts(JsonNode config, JsonNode rows, boolean reward) {
        Map<String,Integer> amounts=new LinkedHashMap<>();
        if (rows.isMissingNode()) return amounts;
        if (!rows.isArray() || rows.size()>200) throw new IllegalArgumentException("结算物资必须为不超过200项的数组");
        for (JsonNode row:rows) {
            if (!row.isObject() || !row.path("itemId").isTextual()) throw new IllegalArgumentException("结算物资引用不正确");
            row.fieldNames().forEachRemaining(field->{if (!ThemeRuntimeConfig.set("itemId","quantity").contains(field)) throw new IllegalArgumentException("物資结算不支持字段："+field);});
            String id=row.path("itemId").asText(); JsonNode item=item(config,id); int quantity=settlementInteger(row,"quantity");
            if (quantity<1 || amounts.put(id,quantity)!=null) throw new IllegalArgumentException("物资数量必须为正整数且引用不能重复");
            if (!reward && !"ITEM".equals(item.path("kind").asText())) throw new IllegalArgumentException("收藏勋章不能作为材料消费");
        }
        return amounts;
    }
    private int settlementInteger(JsonNode node,String field) {
        if (!node.has(field)) return 0;
        JsonNode value=node.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<0 || value.intValue()>999) throw new IllegalArgumentException(field+" 必须是0至999的整数");
        return value.intValue();
    }
    public int reduceStatCost(JsonNode config, TopicRouteEngine.State state, String key, int cost) {
        if (cost < 0) throw new IllegalArgumentException("数值费用不能为负");
        long reduction=0;
        for(JsonNode item:catalog(config))if("REDUCE".equals(item.path("mode").asText()) && key.equals(stat(item)))reduction+=(long)item.path("delta").asInt()*state.getThemeRuntime().path("inventory").path(item.path("id").asText()).asInt();
        return (int)Math.max(0,cost-reduction);
    }
    public ObjectNode view(JsonNode config, TopicRouteEngine.State state, JourneyRules rules) {
        ObjectNode view = view(config,state);
        if(!view.has("zeroHp") && rules.getHp().isEnabled() && state.getVariables().containsKey("sys.hp") && number(state,"sys.hp")==0 && config.path("gameplayRuntime").path("survival").has("zeroMessage")) {
            view.set("zeroHp",GameplaySurvivalEngine.zeroView(config,state,System.currentTimeMillis()));
        }
        view.set("attributes",JSON.valueToTree(JourneyInitialState.playerView(rules,state)));
        JsonNode audit = state.getThemeRuntime().path("statLinks").path("audit");
        view.set("statLinkEvents",audit.isArray() ? audit.deepCopy() : JSON.createArrayNode());
        JourneyInitialState.redactHidden(rules, view);
        return view;
    }
    private void project(JsonNode config,TopicRouteEngine.State state){for(JsonNode i:catalog(config)){String id=i.path("id").asText();state.getVariables().put("sys.held."+id,state.getThemeRuntime().path("inventory").path(id).asInt());state.getVariables().put("sys.acquired."+id,state.getThemeRuntime().path("acquired").path(id).asInt());}}
    private void journal(TopicRouteEngine.State s,String action,String id,int qty){ObjectNode r=JSON.createObjectNode();r.put("action",action);r.put("itemId",id);r.put("quantity",qty);s.getThemeRuntime().withArray("journal").add(r);}
}
