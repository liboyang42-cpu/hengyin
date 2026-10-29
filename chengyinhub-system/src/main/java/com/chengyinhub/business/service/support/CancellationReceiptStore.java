package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.vo.ClubEventCancellationResult;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One cancellation on its original activity/topic row. Call ONLY after current authorization
 * and scope locks. No SDK, lease expiry, TTL identity release or financial reconstruction. */
public final class CancellationReceiptStore {
    public enum Resource { ACTIVITY, TOPIC }

    public record Request(Resource resource, Long id, Long actor, Long owner, Long sourceOwner,
                          Long topic, Long club, String operation, String requestId, String reason,long epoch) {
        public Request(Resource resource,Long id,Long actor,Long owner,Long sourceOwner,Long topic,Long club,
                       String operation,String requestId,String reason) {
            this(resource,id,actor,owner,sourceOwner,topic,club,operation,requestId,reason,0L);
        }
        public Request {
            require(resource != null && positive(id) && positive(actor) && positive(owner)
                    && (sourceOwner == null || positive(sourceOwner))
                    && (topic == null || positive(topic)) && (club == null || positive(club)), "原取消范围不完整");
            require(epoch>=0 && (resource!=Resource.ACTIVITY || epoch==0),"原取消轮次不完整");
            require(Set.of("ACTIVITY_OWNER", "ACTIVITY_CLUB", "ACTIVITY_MERCHANT", "TOPIC_HOST",
                    "CLUB_OCCURRENCE", "CLUB_OWNER_WRAP", "AUTHORIZED_TOPIC_WRAP", "TOPIC_ACTIVITY", "CLUB_TOPIC_END")
                    .contains(operation), "原取消入口不明确");
            require(requestId != null && !requestId.trim().isEmpty() && requestId.trim().length() <= 80
                    && reason != null && !reason.trim().isEmpty(), "原取消请求身份不完整");
            requestId = requestId.trim(); reason = reason.trim();
        }
        private Map<String,Object> identity() {
            Map<String,Object> map = new LinkedHashMap<>();
            map.put("resource", resource.name()); map.put("id", id); map.put("actor", actor);
            map.put("owner", owner); map.put("sourceOwner", sourceOwner); map.put("topic", topic);
            map.put("club", club); map.put("operation", operation); map.put("requestId", requestId);
            map.put("reasonHash", CancellationReceiptStore.hash(reason));map.put("epoch",epoch);return map;
        }
        public String hash() { return CancellationReceiptStore.hash(json(identity())); }
    }

    public record Snapshot(boolean done, String resultType, JSONObject result, JSONObject plan,long epoch) {
        public ActivityCancelResult activity() {
            require(done, "原取消尚未完成，请保留原请求查证");
            return new ActivityCancelResult(count(result,"refundedOrders"), count(result,"manualOrders"),
                    count(result,"closedPendingOrders"));
        }
        public ClubEventCancellationResult occurrence() {
            require(done && "CLUB_EVENT".equals(resultType), "原取消回执类型不符");
            ClubEventCancellationResult out = new ClubEventCancellationResult();
            out.setActivityId(number(result,"activityId"));
            out.setOccurrenceStatus(string(result,"occurrenceStatus"));
            out.setRefundStatus(string(result,"refundStatus"));
            out.setRefundedOrders(count(result,"refundedOrders")); out.setManualOrders(count(result,"manualOrders"));
            out.setClosedPendingOrders(count(result,"closedPendingOrders"));
            out.setRegisteredCampaignId(number(result,"registeredCampaignId"));
            out.setRegisteredRecipientCount(count(result,"registeredRecipientCount"));
            out.setRegisteredNotificationStatus(string(result,"registeredNotificationStatus"));
            out.setWaitlistCampaignId(number(result,"waitlistCampaignId"));
            out.setWaitlistRecipientCount(count(result,"waitlistRecipientCount"));
            out.setWaitlistNotificationStatus(string(result,"waitlistNotificationStatus")); return out;
        }
        public List<Long> activities() {
            require(plan!=null,"原结束主题计划缺失");
            Object ids=plan.get("activityIds"); require(ids instanceof List<?>,"原场次计划损坏");
            List<Long> out=new ArrayList<>();
            for(Object id:(List<?>)ids) {
                require(id instanceof Number,"原场次身份损坏");
                long value=new BigDecimal(id.toString()).longValueExact();
                require(value>0 && (out.isEmpty() || out.get(out.size()-1)<value),"原场次计划重复或乱序");
                out.add(value);
            }
            return List.copyOf(out);
        }
        public boolean stoppedSelling() {
            require(plan!=null && plan.get("stoppedSelling") instanceof Boolean,"原停售事实损坏");
            return (Boolean)plan.get("stoppedSelling");
        }
        public Map<String,Object> topicEnd(Long topicId) {
            require(done && "CLUB_TOPIC_END".equals(resultType),"原结束主题回执未完成");
            Map<String,Object> out=new LinkedHashMap<>();
            out.put("topicId",topicId);out.put("stoppedSelling",stoppedSelling());
            out.put("cancellationEpoch",epoch);
            out.put("cancellationState","DONE");
            out.put("sessionTotal",activities().size());out.put("cancelledSessions",activities().size());
            out.put("refundedOrders",count(result,"refundedOrders"));out.put("manualOrders",count(result,"manualOrders"));
            out.put("closedPendingOrders",count(result,"closedPendingOrders"));out.put("failedSessions",List.of());return out;
        }
    }

    private final CmsActivityMapper activities;
    private final CmsTopicMapper topics;
    public CancellationReceiptStore(CmsActivityMapper activities, CmsTopicMapper topics) {
        this.activities = Objects.requireNonNull(activities); this.topics = Objects.requireNonNull(topics);
    }
    private CancellationReceiptStore(CmsTopicMapper topics) {this.activities=null;this.topics=Objects.requireNonNull(topics);}
    public static CancellationReceiptStore forTopics(CmsTopicMapper topics) {return new CancellationReceiptStore(topics);}

    /** PENDING is not acceptance. Identical callers may resume the ORIGINAL observation/application;
     * every application rechecks this row, so the loser reads DONE without repeating its effects. */
    public Snapshot begin(Request request, boolean alreadyCancelled) {
        transaction(); String raw = read(request);
        if (raw == null) {
            require(!alreadyCancelled, "历史取消缺少原结果回执，请人工核对；不得重建退款");
            String pending = encode(request, null, null, null);
            require(write(request, pending, true) == 1, "原取消意图写入未确认，请保留原请求查证");
            raw = read(request);
        }
        return decode(raw, request);
    }

    public Snapshot current(Request request) { transaction(); return decode(read(request), request); }

    public record OwnedReceipt(Snapshot snapshot,String requestId,String reason) { }

    /** Read-only recovery after CURRENT scope/owner authorization and locks. Another actor's
     * request is never disclosed, and NULL history never becomes an inferred financial receipt. */
    public OwnedReceipt ownedActivity(Long id,Long actor,Long owner,Long sourceOwner,Long topic,Long club) {
        transaction();String raw=activities.selectCancellationReceiptForUpdate(id);
        if(raw==null) return null;
        require(raw.length()<=32768,"原取消回执损坏");
        final JSONObject record;
        try {record=JSON.parseObject(raw);} catch(RuntimeException bad) {throw conflict("原取消回执损坏");}
        require(record!=null && record.get("identity") instanceof JSONObject,"原取消身份损坏");
        JSONObject identity=record.getJSONObject("identity");
        if(!Objects.equals(actor,number(identity,"actor"))) return null;
        Request original=new Request(Resource.ACTIVITY,id,actor,owner,sourceOwner,topic,club,
                string(identity,"operation"),string(identity,"requestId"),string(record,"reason"));
        return new OwnedReceipt(decode(raw,original),original.requestId(),original.reason());
    }

    public long currentTopicEpoch(Long id) {
        transaction();Long epoch=topics.selectCancellationEpochForUpdate(id);
        require(epoch!=null && epoch>=0,"原主题取消轮次无法确认");return epoch;
    }
    public long requestEpoch(Long id,Long supplied) {
        long current=currentTopicEpoch(id);
        require(supplied!=null || current==0,"请刷新确认原主题取消轮次，不得将旧正文用于新一轮");
        long selected=supplied==null?0:supplied;
        require(selected>=0 && selected<=current,"原主题取消轮次不匹配");return selected;
    }

    /** Listing owns the topic lock. Advance only after an absent or fully validated DONE receipt.
     * This never clears/overwrites history and never releases PENDING through time or lease expiry. */
    public void requireRelistable(Long id) {
        long epoch=currentTopicEpoch(id);require(epoch<Long.MAX_VALUE,"原主题取消轮次已耗尽，请人工核对");
        String raw=topics.selectCancellationReceiptForUpdate(id,epoch);
        if(raw==null) return;
        require(raw.length()<=32768,"原取消回执损坏，不得推进售卖轮次");
        final JSONObject record;
        try {record=JSON.parseObject(raw);} catch(RuntimeException bad) {throw conflict("原取消回执无法确认，不得推进售卖轮次");}
        require(record!=null && record.get("identity") instanceof JSONObject,"原取消身份无法确认");
        JSONObject identity=record.getJSONObject("identity");
        Request original=new Request(Resource.TOPIC,id,number(identity,"actor"),number(identity,"owner"),
                optionalNumber(identity,"sourceOwner"),id,optionalNumber(identity,"club"),string(identity,"operation"),
                string(identity,"requestId"),string(record,"reason"),epoch);
        require(decode(raw,original).done(),"原取消尚未确认，不得上架或换轮次");
        require(epoch<Long.MAX_VALUE,"原主题取消轮次已耗尽，请人工核对");
    }

    /** Original per-session plan survives partial commits. No financial results are inferred here. */
    public Snapshot beginTopicEnd(Request request,List<Long> ids,boolean stoppedSelling) {
        transaction(); require(Set.of("CLUB_TOPIC_END","TOPIC_HOST").contains(request.operation()),"原主题取消入口不符");
        String raw=read(request);
        if(raw==null) {
            require(stoppedSelling || request.epoch()>0,"旧停售主题缺少原取消回执，请人工核对");
            Map<String,Object> plan=new LinkedHashMap<>();plan.put("activityIds",List.copyOf(ids));
            plan.put("stoppedSelling",stoppedSelling);
            String pending=encode(request,null,null,plan);
            // Validate the entire original plan before its first durable write.
            decode(pending,request);
            require(write(request,pending,true)==1,"原结束主题计划写入未确认");raw=read(request);
        }
        return decode(raw,request);
    }

    public Snapshot completeTopicEnd(Request request,ActivityCancelResult counts) {
        return finish(request,"CLUB_TOPIC_END",counts(counts));
    }

    public ActivityCancelResult complete(Request request, ActivityCancelResult result) {
        require(result != null, "原取消结果缺失");
        Map<String,Object> payload = counts(result);
        return finish(request,"ACTIVITY",payload).activity();
    }

    public ClubEventCancellationResult complete(Request request, ClubEventCancellationResult result) {
        require(result != null, "原取消结果缺失");
        Map<String,Object> payload = counts(new ActivityCancelResult(result.getRefundedOrders(),
                result.getManualOrders(),result.getClosedPendingOrders()));
        payload.put("activityId",result.getActivityId()); payload.put("occurrenceStatus",result.getOccurrenceStatus());
        payload.put("refundStatus",result.getRefundStatus()); payload.put("registeredCampaignId",result.getRegisteredCampaignId());
        payload.put("registeredRecipientCount",result.getRegisteredRecipientCount());
        payload.put("registeredNotificationStatus",result.getRegisteredNotificationStatus());
        payload.put("waitlistCampaignId",result.getWaitlistCampaignId());
        payload.put("waitlistRecipientCount",result.getWaitlistRecipientCount());
        payload.put("waitlistNotificationStatus",result.getWaitlistNotificationStatus());
        return finish(request,"CLUB_EVENT",payload).occurrence();
    }

    private Snapshot finish(Request request, String type, Map<String,Object> payload) {
        transaction(); Snapshot original=current(request);
        require(!original.done(), "原取消已完成，不得重复应用经济效果");
        String completed = encode(request,type,payload,original.plan());
        // The result is committed by the SAME transaction as its domain effects. A lost write receipt rolls all back.
        require(write(request,completed,false) == 1, "原取消结果写入未确认，必须回滚原业务事务");
        return decode(read(request),request);
    }

    private String read(Request request) {
        return request.resource()==Resource.ACTIVITY ? activities.selectCancellationReceiptForUpdate(request.id())
                : topics.selectCancellationReceiptForUpdate(request.id(),request.epoch());
    }
    private int write(Request request, String json, boolean first) {
        return request.resource()==Resource.ACTIVITY
                ? activities.compareAndSetCancellationReceipt(request.id(),request.hash(),json,first)
                : topics.compareAndSetCancellationReceipt(request.id(),request.epoch(),request.hash(),json,first);
    }
    private static String encode(Request request,String type,Map<String,Object> result,Map<String,Object> plan) {
        Map<String,Object> record=new LinkedHashMap<>(); record.put("version",1);
        record.put("identity",request.identity()); record.put("requestHash",request.hash());
        record.put("reason",request.reason());
        record.put("state",result==null?"PENDING":"DONE"); record.put("resultType",type);
        record.put("result",result); record.put("resultHash",result==null?null:hash(json(result)));
        record.put("plan",plan);record.put("planHash",plan==null?null:hash(json(plan)));
        return json(record);
    }
    private static Snapshot decode(String raw,Request request) {
        require(raw!=null && raw.length()<=32768,"原取消回执缺失或损坏，请人工核对");
        final JSONObject record;
        try { record=JSON.parseObject(raw); }
        catch (RuntimeException invalid) { throw conflict("原取消回执损坏，请人工核对"); }
        require(record!=null && record.keySet().equals(Set.of("version","identity","requestHash","state",
                "resultType","result","resultHash","plan","planHash","reason")) && number(record,"version")==1L
                && request.reason().equals(record.get("reason")) && request.hash().equals(record.get("requestHash")),"原请求号已用于不同取消语义或范围已变化");
        Object value=record.get("identity"); require(value instanceof JSONObject,"原取消身份损坏");
        JSONObject identity=(JSONObject)value; Map<String,Object> expected=request.identity();
        require(identity.keySet().equals(expected.keySet()),"原取消身份损坏");
        expected.forEach((key,wanted)-> {
            Object actual=identity.get(key);
            require(wanted instanceof Number ? actual instanceof Number && number(identity,key).equals(((Number)wanted).longValue())
                    : Objects.equals(wanted,actual),"原取消请求身份或当前授权范围不符");
        });
        JSONObject plan=null;
        if(Set.of("CLUB_TOPIC_END","TOPIC_HOST").contains(request.operation())) {
            require(record.get("plan") instanceof JSONObject,"原结束主题计划缺失");
            JSONObject rawPlan=(JSONObject)record.get("plan");
            require(rawPlan.keySet().equals(Set.of("activityIds","stoppedSelling")),"原结束主题计划损坏");
            Snapshot checked=new Snapshot(false,null,null,rawPlan,request.epoch());
            Map<String,Object> normalizedPlan=new LinkedHashMap<>();
            normalizedPlan.put("activityIds",checked.activities());normalizedPlan.put("stoppedSelling",checked.stoppedSelling());
            require(hash(json(normalizedPlan)).equals(record.get("planHash")),"原结束主题计划校验失败");
            plan=new JSONObject(normalizedPlan);
        } else require(record.get("plan")==null && record.get("planHash")==null,"原取消计划类型不符");
        if ("PENDING".equals(record.get("state"))) {
            require(record.get("resultType")==null && record.get("result")==null && record.get("resultHash")==null,
                    "原取消处理中，结果尚未确认"); return new Snapshot(false,null,null,plan,request.epoch());
        }
        require("DONE".equals(record.get("state")) && record.get("result") instanceof JSONObject,
                "原取消状态无法确认，请保留原身份");
        String type=string(record,"resultType"); JSONObject payload=(JSONObject)record.get("result");
        Map<String,Object> normalized=counts(new ActivityCancelResult(count(payload,"refundedOrders"),
                count(payload,"manualOrders"),count(payload,"closedPendingOrders")));
        if ("CLUB_EVENT".equals(type)) {
            require(request.id().equals(number(payload,"activityId")) && "CANCELLED".equals(payload.get("occurrenceStatus"))
                    && Set.of("REFUND_REQUESTED","ACCEPTED_WITH_MANUAL_REVIEW","NOT_REQUIRED").contains(payload.get("refundStatus")),
                    "原取消结果范围或状态损坏");
            normalized.put("activityId",number(payload,"activityId")); normalized.put("occurrenceStatus",string(payload,"occurrenceStatus"));
            normalized.put("refundStatus",string(payload,"refundStatus"));
            for (String prefix:new String[]{"registered","waitlist"}) {
                Long campaign=number(payload,prefix+"CampaignId"); require(positive(campaign),"原取消通知回执缺失");
                normalized.put(prefix+"CampaignId",campaign); normalized.put(prefix+"RecipientCount",count(payload,prefix+"RecipientCount"));
                String status=string(payload,prefix+"NotificationStatus");
                require(Set.of("NOT_REQUIRED","COMPLETED","PARTIAL_FAILED","QUEUED").contains(status),"原取消通知状态损坏");
                normalized.put(prefix+"NotificationStatus",status);
            }
        } else require("ACTIVITY".equals(type) || ("CLUB_TOPIC_END".equals(type) && plan!=null),"原取消结果类型损坏");
        require(payload.keySet().equals(normalized.keySet())
                && hash(json(normalized)).equals(record.get("resultHash")),"原取消结果校验失败");
        return new Snapshot(true,type,new JSONObject(normalized),plan,request.epoch());
    }
    private static Map<String,Object> counts(ActivityCancelResult result) {
        require(result.getRefundedOrders()>=0 && result.getManualOrders()>=0 && result.getClosedPendingOrders()>=0,"原取消计数损坏");
        Map<String,Object> map=new LinkedHashMap<>(); map.put("refundedOrders",result.getRefundedOrders());
        map.put("manualOrders",result.getManualOrders()); map.put("closedPendingOrders",result.getClosedPendingOrders()); return map;
    }
    private static Long number(JSONObject object,String key) {
        Object value=object.get(key); require(value instanceof Number,"原取消数值事实损坏");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException invalid) { throw conflict("原取消数值事实损坏"); }
    }
    private static Long optionalNumber(JSONObject object,String key) {return object.get(key)==null?null:number(object,key);}
    private static int count(JSONObject object,String key) {
        long value=number(object,key); require(value>=0 && value<=Integer.MAX_VALUE,"原取消计数损坏"); return (int)value;
    }
    private static String string(JSONObject object,String key) {
        Object value=object.get(key); require(value instanceof String,"原取消文本事实损坏"); return (String)value;
    }
    private static boolean positive(Long value) { return value!=null && value>0; }
    private static void transaction() { require(TransactionSynchronizationManager.isActualTransactionActive(),"原取消回执必须加入业务事务"); }
    private static void require(boolean condition,String message) { if(!condition) throw conflict(message); }
    private static ServiceException conflict(String message) { return new ServiceException(message,409); }
    private static String json(Object value) { return JSON.toJSONString(value,JSONWriter.Feature.WriteNulls); }
    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
