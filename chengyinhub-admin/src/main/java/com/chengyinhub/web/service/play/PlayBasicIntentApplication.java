package com.chengyinhub.web.service.play;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;

import org.springframework.stereotype.Service;
import static com.chengyinhub.common.core.domain.AjaxResult.error;

/** Original basic-command identity, authorization, locking and atomic terminal receipts. */
@Service
public class PlayBasicIntentApplication {

    @Autowired
    private ICmsActivityService cmsActivityService;
    @Autowired
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessionRuntimeService;
    @Autowired
    private com.chengyinhub.business.mapper.GameSessionRuntimeMapper playCommandReceiptMapper;
    @Autowired
    private com.chengyinhub.business.mapper.TopicRouteSessionMapper playCommandRouteMapper;
    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private ICmsRegistrationService cmsRegistrationService;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper responseObjectMapper;

    private static class BasicIntentScope {
        Long activityId;
        Long topicId;
        Long nodeId;
        AjaxResult denied;
    }

    public BasicIntentScope basicIntentScope(Long memberId, String activityId, String topicId, String nodeId) {
        BasicIntentScope scope = new BasicIntentScope();
        if (memberId == null || memberId <= 0) { scope.denied = error("请先登录"); return scope; }
        try {
            scope.activityId = StringUtils.isBlank(activityId) ? 0L : Long.valueOf(activityId);
            scope.nodeId = Long.valueOf(nodeId);
            if (scope.activityId < 0 || scope.nodeId <= 0) throw new IllegalArgumentException();
            CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(scope.nodeId);
            if (node == null || node.getTopicId() == null) { scope.denied = error("节点不存在"); return scope; }
            scope.topicId = node.getTopicId();
            if (scope.activityId > 0) {
                CmsActivity activity = cmsActivityService.selectCmsActivityById(scope.activityId);
                if (activity == null || !scope.topicId.equals(activity.getTopicId())) { scope.denied = error("该节点不属于本次活动"); return scope; }
            } else if (!scope.topicId.equals(Long.valueOf(topicId))) {
                scope.denied = error("该节点不属于本主题"); return scope;
            }
        } catch (IllegalArgumentException e) { scope.denied = error("参数有误"); return scope; }
        // 读回执仍须证明原报名归属；普通免费报名沿用现有资格，探索票才以已付为前提。
        // 时效和实体版本仅在新写时校验，不能把普通免费报名误升级为付费要求。
        CmsRegistration query = new CmsRegistration(); query.setMemberId(memberId);
        query.setOwnerType(scope.activityId > 0 ? 2L : 1L);
        query.setOwnerId(scope.activityId > 0 ? scope.activityId : scope.topicId);
        query.setRegistrationStatus(2);
        List<CmsRegistration> registrations = cmsRegistrationService.selectCmsRegistrationList(query);
        if (registrations == null || registrations.stream().noneMatch(row -> row != null
                && memberId.equals(row.getMemberId()) && query.getOwnerType().equals(row.getOwnerType())
                && query.getOwnerId().equals(row.getOwnerId())
                && (!Integer.valueOf(com.chengyinhub.common.constant.PurchaseKind.EXPLORE_PASS).equals(row.getPurchaseKind())
                    || Integer.valueOf(2).equals(row.getPaymentStatus()))
                && Integer.valueOf(2).equals(row.getRegistrationStatus()))) scope.denied = error("无权读取本次游玩命令");
        return scope;
    }

    /** Compatibility GET entry, not a pure query: sharedRouteSnapshot can persist zero-HP transitions.
     * Its REQUIRED refresh participates here; match its writable/default-isolation rollback contract. */
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult basicActionContext(Long actorMemberId, String activityId, String topicId, String nodeId) {
        Long memberId = actorMemberId;
        BasicIntentScope scope = basicIntentScope(memberId, activityId, topicId, nodeId);
        if (scope.denied != null) return scope.denied;
        Map<String, Object> context = basicActionContext(memberId, scope);
        context.put("routeSessionId", String.valueOf(context.get("routeSessionId")));
        return AjaxResult.success(context);
    }

    public Map<String, Object> basicActionContext(Long memberId, BasicIntentScope scope) {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        com.fasterxml.jackson.databind.JsonNode shared = gameSessionRuntimeService == null ? null
                : gameSessionRuntimeService.sharedRouteSnapshot(memberId, scope.topicId, scope.activityId, scope.nodeId);
        // Refresh can bump a different locking-read object. Read its current version afterwards;
        // a pre-refresh Java reference is not updated when MyBatis flushes its local cache.
        com.chengyinhub.business.domain.GameSession sharedSession = shared == null
                ? playCommandReceiptMapper.selectSessionByActivity(scope.activityId)
                : playCommandReceiptMapper.selectSessionByActivityForUpdate(scope.activityId);
        if (shared != null) {
            if (sharedSession == null || shared.path("runtimeInstance").asText().isEmpty()
                    || !shared.path("authorityEpoch").canConvertToInt()) throw new IllegalStateException("本场缺少动作权威身份");
            context.put("routeSessionId", sharedSession.getId()); context.put("expectedRouteVersion", sharedSession.getRevision());
            context.put("runtimeInstance", shared.path("runtimeInstance").asText()); context.put("authorityEpoch", shared.path("authorityEpoch").asInt());
            return context;
        }
        com.chengyinhub.business.domain.TopicRouteSession session = playCommandRouteMapper.selectCurrentByOwner(scope.activityId, scope.topicId, memberId);
        if (session == null) session = playCommandRouteMapper.selectByOwner(scope.activityId, scope.topicId, memberId, "default");
        context.put("routeSessionId", session == null ? 0L : session.getId());
        context.put("expectedRouteVersion", session == null ? 0 : session.getVersion());
        return context;
    }

    @Transactional(readOnly = true)
    public AjaxResult basicActionReceipt(Long actorMemberId, String activityId, String topicId, String nodeId, String routeActionId) {
        Long memberId = actorMemberId;
        if (routeActionId == null || !routeActionId.matches("[A-Za-z0-9:_-]{1,80}")) return error("动作标识无效");
        BasicIntentScope scope = basicIntentScope(memberId, activityId, topicId, nodeId);
        if (scope.denied != null) return scope.denied;
        com.chengyinhub.business.domain.GameCommandReceipt receipt = playCommandReceiptMapper.selectReceipt(scope.activityId, memberId, basicRequestId(routeActionId));
        if (receipt == null) return new AjaxResult(520, "原命令结果待确认，请稍后查询");
        if (!scope.nodeId.equals(receipt.getNodeId()) || !"PLAYER".equals(receipt.getActorPerspective())
                || receipt.getAction() == null || !receipt.getAction().startsWith("PLAY_BASIC_")) return error("无权读取该命令");
        return basicReplay(receipt);
    }

    public AjaxResult executeBasicIntent(Long actorMemberId, String kind, String activityId, String topicId, String nodeId,
            String actionId, Integer expectedVersion, Long registrationId, Long routeSessionId,
            String runtimeInstance, Integer authorityEpoch, List<?> input, java.util.function.Function<PlayOriginalRun, AjaxResult> execute) {
        Long memberId = actorMemberId;
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (actionId == null || !actionId.matches("[A-Za-z0-9:_-]{1,80}") || expectedVersion == null
                || expectedVersion < 0 || routeSessionId == null || routeSessionId < 0) return error("必须提供动作标识、期望版本和原运行时身份");
        BasicIntentScope scope = basicIntentScope(memberId, activityId, topicId, nodeId);
        if (scope.denied != null) return scope.denied;
        String hash = basicHash(basicJson(java.util.Arrays.asList(kind, scope.activityId, scope.topicId,
                scope.nodeId, expectedVersion, registrationId, routeSessionId, runtimeInstance, authorityEpoch, input)));
        String requestId = basicRequestId(actionId);
        com.chengyinhub.business.domain.GameCommandReceipt prior = playCommandReceiptMapper.selectReceiptForUpdate(scope.activityId, memberId, requestId);
        // 原回执先于实体当前版本、到达距离、答案、旧实例和旧 epoch 检查。
        if (prior != null) return basicReplay(prior, hash);
        // 锁住业务聚合后再次查同一身份：等待并发赢家期间，原回执可能已提交。
        playCommandReceiptMapper.selectSessionByActivityForUpdate(scope.activityId);
        // shared runtime 的 sessionId 属于 GameSession；个人局只锁请求指定的原局。
        com.chengyinhub.business.domain.TopicRouteSession originalSession = null;
        if (runtimeInstance == null && routeSessionId > 0) {
            originalSession = playCommandRouteMapper.selectAccessibleByIdForUpdate(routeSessionId, memberId);
            if (originalSession == null || !memberId.equals(originalSession.getMemberId())
                    || !scope.activityId.equals(originalSession.getActivityId())
                    || !scope.topicId.equals(originalSession.getTopicId())) {
                return new AjaxResult(409, "原局身份不匹配").put("intentNotExecuted", true);
            }
        }
        prior = playCommandReceiptMapper.selectReceiptForUpdate(scope.activityId, memberId, requestId);
        if (prior != null) return basicReplay(prior, hash);
        if (originalSession != null && !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(originalSession.getStatus())) {
            return new AjaxResult(409, "原局已结束").put("intentNotExecuted", true);
        }
        Map<String, Object> current = basicActionContext(memberId, scope);
        if (!java.util.Objects.equals(routeSessionId, current.get("routeSessionId"))
                || !java.util.Objects.equals(expectedVersion, current.get("expectedRouteVersion"))
                || !java.util.Objects.equals(runtimeInstance, current.get("runtimeInstance"))
                || !java.util.Objects.equals(authorityEpoch, current.get("authorityEpoch"))) {
            return new AjaxResult(409, "原动作状态已过期，确认后才能创建新意图").put("intentNotExecuted", true);
        }
        com.chengyinhub.business.domain.GameCommandReceipt claim = new com.chengyinhub.business.domain.GameCommandReceipt();
        claim.setActivityId(scope.activityId); claim.setNodeId(scope.nodeId); claim.setActorMemberId(memberId);
        claim.setActorPerspective("PLAYER"); claim.setRequestId(requestId); claim.setRequestHash(hash);
        claim.setAction("PLAY_BASIC_" + kind); claim.setOutcome("PENDING"); claim.setCreateTime(new Date()); claim.setUpdateTime(new Date());
        if (playCommandReceiptMapper.insertReceiptClaim(claim) != 1) {
            prior = playCommandReceiptMapper.selectReceiptForUpdate(scope.activityId, memberId, requestId);
            if (prior == null) throw new IllegalStateException("动作回执登记失败");
            return basicReplay(prior, hash);
        }
        AjaxResult response = execute.apply(new PlayOriginalRun(originalSession, routeSessionId, runtimeInstance, authorityEpoch));
        response.put("intentTerminal", true);
        claim.setOutcome(Integer.valueOf(200).equals(response.get("code")) ? "APPLIED" : "FAILED");
        // Persist the HTTP representation so dates keep the same format and timezone on replay.
        try { claim.setResultJson(responseObjectMapper.writeValueAsString(response)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("动作回执不可编码", e); }
        claim.setUpdateTime(new Date());
        if (playCommandReceiptMapper.completeReceipt(claim) != 1) throw new IllegalStateException("动作终态回执保存失败");
        return response;
    }

    public AjaxResult basicReplay(com.chengyinhub.business.domain.GameCommandReceipt receipt, String hash) {
        if (!hash.equals(receipt.getRequestHash()) || !"PLAYER".equals(receipt.getActorPerspective())
                || receipt.getAction() == null || !receipt.getAction().startsWith("PLAY_BASIC_")) return new AjaxResult(409, "动作标识已用于其他输入");
        return basicReplay(receipt);
    }

    public AjaxResult basicReplay(com.chengyinhub.business.domain.GameCommandReceipt receipt) {
        if (!java.util.Arrays.asList("APPLIED", "FAILED").contains(receipt.getOutcome())) return new AjaxResult(520, "原命令结果待确认，请稍后查询");
        try { return com.chengyinhub.common.utils.JsonUtils.getObjectMapper().readValue(receipt.getResultJson(), AjaxResult.class); }
        catch (java.io.IOException e) { throw new IllegalStateException("动作回执不可读", e); }
    }

    public String basicJson(Object value) {
        try { return com.chengyinhub.common.utils.JsonUtils.getObjectMapper().writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("动作回执不可编码", e); }
    }

    public String basicHash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public String basicRequestId(String actionId) { return "play:" + basicHash(actionId).substring(0, 48); }
}
