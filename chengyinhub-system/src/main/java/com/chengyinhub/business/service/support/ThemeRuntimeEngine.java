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
        t.putObject("inventory"); t.putObject("acquired"); t.putObject("sources"); t.putObject("pending");
        t.putArray("events"); t.putArray("journal");
        state.getVariables().put("sys.actions", 0);
    }
    public void signal(JsonNode config, TopicRouteEngine.State state, JourneyRules rules,
                       String source, Long nodeId, String step, String option, int outcome, String sourceKey) {
        if (config == null || !config.isObject() || state.getThemeRuntime().isEmpty()) return;
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
        if (config == null || !config.isObject() || state.getThemeRuntime().isEmpty()) return;
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
        ObjectNode t = state.getThemeRuntime();
        if (t.with("sources").has(source)) return;
        JsonNode item = item(config,id);
        requireStatEnabled(item, rules);
        t.with("sources").put(source,true);
        ObjectNode pending = t.with("pending").putObject(source); pending.put("itemId",id); pending.put("quantity",qty);
        receive(config,state,rules,source,false);
    }
    public void receive(JsonNode config, TopicRouteEngine.State state, JourneyRules rules, String source, boolean required) {
        ObjectNode t = state.getThemeRuntime(); JsonNode pending = t.with("pending").get(source);
        if (pending == null) throw new IllegalStateException("没有待领取内容");
        String id = pending.path("itemId").asText(); int qty = pending.path("quantity").asInt(); JsonNode item = item(config,id);
        if ("ITEM".equals(item.path("kind").asText()) && used(config,state) + qty > config.path("capacity").asInt()) {
            if (required) throw new IllegalStateException("背包已满，请先使用或丢弃物品"); return;
        }
        requireStatEnabled(item,rules);
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
        JsonNode item = item(config,id); requireStatEnabled(item,rules);
        if (!"ITEM".equals(item.path("kind").asText()) || !item.has("stat") || item.path("wear").asBoolean()) throw new IllegalStateException("此内容不能点击使用");
        requireHeld(state,id); int previous = number(state,stat(item));
        change(state,rules,item,1);
        if (number(state,stat(item)) == previous) throw new IllegalStateException("当前数值下物品没有实际效果");
        remove(state,id); project(config,state); journal(state,"USE",id,1); events(config,state,rules);
    }
    public void discard(JsonNode config, TopicRouteEngine.State state, JourneyRules rules, String id) {
        if(config.has("allowDiscard") && !config.path("allowDiscard").asBoolean())throw new IllegalStateException("本主题不允许丢弃物品");
        JsonNode item = item(config,id);
        if (!"ITEM".equals(item.path("kind").asText())) throw new IllegalStateException("收藏勋章不可丢弃");
        requireHeld(state,id); remove(state,id);
        if (item.path("wear").asBoolean() && "HELD".equals(item.path("mode").asText())) reconcileHeld(config,state,rules);
        project(config,state); journal(state,"DISCARD",id,1); events(config,state,rules);
    }
    private void remove(TopicRouteEngine.State state,String id) { ObjectNode inventory=state.getThemeRuntime().with("inventory"); int qty=inventory.path(id).asInt()-1; if(qty==0)inventory.remove(id);else inventory.put(id,qty); }
    private void change(TopicRouteEngine.State state,JourneyRules rules,JsonNode item,int qty) {
        String key=stat(item); int max="sys.hp".equals(key)?rules.getHp().getMax():rules.getLuck().getMax();
        state.getVariables().put(key,clamp((long)number(state,key)+(long)item.path("delta").asInt()*qty,max));
    }
    /** 持续修正独立记账。原旅程动作的实际变化同步回基础值，不用反向增减来解除。 */
    public void reconcileHeld(JsonNode config,TopicRouteEngine.State state,JourneyRules rules) {
        if(config==null || !config.isObject() || state.getThemeRuntime().isEmpty())return;
        ObjectNode modifiers=state.getThemeRuntime().with("modifiers");
        for(String key:Arrays.asList("sys.hp","sys.luck")) {
            if(!rules.isCheckEnabled() || ("sys.hp".equals(key) ? !rules.getHp().isEnabled() : !rules.getLuck().isEnabled())) continue;
            int max="sys.hp".equals(key)?rules.getHp().getMax():rules.getLuck().getMax();
            ObjectNode ledger=modifiers.with(key); int current=number(state,key);
            long delta=0;
            for(JsonNode item:catalog(config)) if(item.path("wear").asBoolean() && "HELD".equals(item.path("mode").asText()) && key.equals(stat(item)))
                delta+=(long)item.path("delta").asInt()*state.getThemeRuntime().with("inventory").path(item.path("id").asText()).asInt();
            int applied=ledger.path("applied").asInt();
            int base=clamp((long)current-applied,max);
            // 库存未改变时保持原动作的实际结果；不能因重算上限而隐式回血。
            int effective=ledger.has("requested") && ledger.path("requested").asLong()==delta ? current : clamp(base+delta,max);
            ledger.put("base",base);ledger.put("effective",effective);ledger.put("applied",effective-base);ledger.put("requested",delta);state.getVariables().put(key,effective);
        }
    }
    public boolean conditions(JsonNode conditions,TopicRouteEngine.State state) {
        if(!conditions.isArray())return true;
        for(JsonNode c:conditions) {
            String op=c.path("op").asText();
            if("ITEM_HELD".equals(op)||"ITEM_ACQUIRED".equals(op)) {
                String field="ITEM_HELD".equals(op)?"inventory":"acquired";
                if(state.getThemeRuntime().path(field).path(c.path("itemId").asText()).asInt()<c.path("quantity").asInt())return false;
            } else { ArrayNode one=JSON.createArrayNode();one.add(c);if(!route.conditionsPass(one,state))return false; }
        }
        return true;
    }
    public void terminal(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,String reason,long now) {
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
        ObjectNode result=state.getThemeRuntime().putObject("result");
        result.put("reason",reason);result.put("endedAt",now);result.put("success",success);
        if(chosen!=null){result.put("endingId",chosen.path("id").asText());result.put("title",chosen.path("title").asText());result.put("summary",chosen.path("summary").asText());}
        else {result.put("title",completed?"本局完成":"本局结束");result.put("summary","");}
        result.put("sceneId",config.path("sceneId").asText());result.put("sceneName",config.path("sceneName").asText());
        result.put("hp",number(state,"sys.hp"));result.put("luck",number(state,"sys.luck"));result.put("actions",number(state,"sys.actions"));
        result.put("hpName",config.path("hpName").asText("生命值"));result.put("luckName",config.path("luckName").asText("幸运值"));
        result.set("inventory",state.getThemeRuntime().path("inventory").deepCopy());result.set("acquired",state.getThemeRuntime().path("acquired").deepCopy());
        result.set("catalog",catalog(config).deepCopy());result.set("card",config.path("card").deepCopy());result.put("endingCount",config.path("endings").size());result.put("rescues",state.getThemeRuntime().path("rescues").asInt());
        state.setStatus(success?"COMPLETED":"ENDED");state.setCurrentNodeId(null);state.setRecommendedNodeId(null);
    }
    public int reduceHpCost(JsonNode config,TopicRouteEngine.State state,int cost) {
        long reduction=0;
        for(JsonNode item:catalog(config))if("REDUCE".equals(item.path("mode").asText()))reduction+=(long)item.path("delta").asInt()*state.getThemeRuntime().path("inventory").path(item.path("id").asText()).asInt();
        return (int)Math.max(0,cost-reduction);
    }
    public void chapterStart(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,JsonNode ch,long now) {
        ObjectNode states=state.getThemeRuntime().with("chapters");String key=String.valueOf(ch.path("index").asInt());
        if(states.has(key))return;
        ObjectNode progress=states.putObject(key);progress.put("startedAt",now);progress.put("steps",0);progress.put("ended",false);
        chapterGrants(config,state,rules,ch,"START");
    }
    public void chapterEnd(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,JsonNode ch,long now) {
        chapterStart(config,state,rules,ch,now);
        ObjectNode progress=state.getThemeRuntime().with("chapters").with(String.valueOf(ch.path("index").asInt()));
        if(progress.path("ended").asBoolean())return;
        chapterTick(config,state,rules,now);
        progress.put("ended",true);
        if(rules.isCheckEnabled() && rules.getHp().isEnabled())state.getVariables().put("sys.hp",Math.max(0,number(state,"sys.hp")-reduceHpCost(config,state,ch.path("hpCost").asInt())));
        if(rules.isCheckEnabled() && rules.getLuck().isEnabled())state.getVariables().put("sys.luck",Math.max(0,number(state,"sys.luck")-ch.path("luckCost").asInt()));
        chapterGrants(config,state,rules,ch,"END");
        reconcileHeld(config,state,rules);
    }
    private void chapterGrants(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,JsonNode ch,String timing) {
        for(JsonNode g:ch.path("grants"))if(timing.equals(g.path("timing").asText()))grant(config,state,rules,"chapter:"+ch.path("index").asInt()+":"+timing+":"+g.path("itemId").asText(),g.path("itemId").asText(),g.path("quantity").asInt());
    }
    public void chapterSteps(TopicRouteEngine.State state,int delta) {
        if(delta<=0)return;
        for(JsonNode p:state.getThemeRuntime().with("chapters"))if(!p.path("ended").asBoolean())((ObjectNode)p).put("steps",Math.min(999999,p.path("steps").asInt()+delta));
    }
    public void chapterTick(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,long now) {
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
        view.put("hpName",config.path("hpName").asText("生命值"));view.put("luckName",config.path("luckName").asText("幸运值"));
        ArrayNode contents=view.putArray("contents");
        for(JsonNode item:catalog(config)) {int quantity=state.getThemeRuntime().path("inventory").path(item.path("id").asText()).asInt();if(quantity==0)continue;
            ObjectNode row=item.deepCopy();row.put("quantity",quantity);row.put("worn",item.path("wear").asBoolean());contents.add(row);}
        view.put("awaitingRescue",state.getThemeRuntime().path("awaitingRescue").asBoolean());view.put("rescues",state.getThemeRuntime().path("rescues").asInt());view.put("actions",number(state,"sys.actions"));view.put("actionLimit",config.path("actionLimit").asInt());
        view.set("journal",state.getThemeRuntime().path("journal").deepCopy());
        ObjectNode pending=state.getThemeRuntime().path("pending").isObject() ? state.getThemeRuntime().path("pending").deepCopy() : JSON.createObjectNode();
        pending.fields().forEachRemaining(entry->{JsonNode item=item(config,entry.getValue().path("itemId").asText());if(item!=null)((ObjectNode)entry.getValue()).put("name",item.path("name").asText());});
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
    public void legacyMedal(JsonNode config,TopicRouteEngine.State state,JourneyRules rules,String id) {grant(config,state,rules,"legacy:"+id,id,1);}
    private boolean contains(JsonNode values,String text){for(JsonNode value:values)if(text.equals(value.asText()))return true;return false;}
    private int used(JsonNode config,TopicRouteEngine.State state) {int total=0;for(JsonNode item:catalog(config))if("ITEM".equals(item.path("kind").asText()))total+=state.getThemeRuntime().path("inventory").path(item.path("id").asText()).asInt();return total;}
    private JsonNode item(JsonNode config,String id) {for(JsonNode i:catalog(config))if(id.equals(i.path("id").asText()))return i;throw new IllegalArgumentException("物资不在本局目录中");}
    private void requireHeld(TopicRouteEngine.State state,String id){if(state.getThemeRuntime().path("inventory").path(id).asInt()<1)throw new IllegalStateException("没有可用物品");}
    private void requireStatEnabled(JsonNode item,JourneyRules rules){if(!"ITEM".equals(item.path("kind").asText()) || !item.has("stat"))return;if(!rules.isCheckEnabled() || ("HP".equals(item.path("stat").asText())?!rules.getHp().isEnabled():!rules.getLuck().isEnabled()))throw new IllegalStateException("主题未开启物品对应属性");}
    private String stat(JsonNode item){return "HP".equals(item.path("stat").asText())?"sys.hp":"sys.luck";}
    private int number(TopicRouteEngine.State s,String k){Number n=s.getVariables().get(k);return n==null?0:n.intValue();}
    private int clamp(long value,int max){return (int)Math.max(0,Math.min(max,value));}
    private void project(JsonNode config,TopicRouteEngine.State state){for(JsonNode i:catalog(config)){String id=i.path("id").asText();state.getVariables().put("sys.held."+id,state.getThemeRuntime().path("inventory").path(id).asInt());state.getVariables().put("sys.acquired."+id,state.getThemeRuntime().path("acquired").path(id).asInt());}}
    private void journal(TopicRouteEngine.State s,String action,String id,int qty){ObjectNode r=JSON.createObjectNode();r.put("action",action);r.put("itemId",id);r.put("quantity",qty);s.getThemeRuntime().withArray("journal").add(r);}
}
