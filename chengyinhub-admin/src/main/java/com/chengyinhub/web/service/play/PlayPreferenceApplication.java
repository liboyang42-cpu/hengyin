package com.chengyinhub.web.service.play;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.PreferenceSubmitRequest;
import com.chengyinhub.business.domain.dto.PreferenceTagCorrectionRequest;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerTopicTagService;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.web.service.PreferencePlayService;

import org.springframework.stereotype.Service;
import static com.chengyinhub.common.core.domain.AjaxResult.error;
import com.chengyinhub.web.service.play.PlayNodeContext.PlayCtx;

/** Preference admission, submission and player-owned tag orchestration. */
@Service
public class PlayPreferenceApplication {

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;
    @Autowired
    private IPlayerTopicTagService playerTopicTagService;
    @Autowired
    private PreferencePlayService preferencePlayService;
    @Autowired
    private IShopDayProofService shopDayProofService;
    @Autowired
    private PlayNodeCompletionApplication playNodeCompletionApplication;
    @Autowired
    private PlayNodeContext playNodeContext;
    @Autowired
    private PlayBasicIntentApplication playBasicIntentApplication;

    private static final class PreferencePlayCtx {
        private CmsTopicNode node;
        private CmsMemberTemplate template;
        private PlayCtx playCtx;
        private AjaxResult error;
    }

    /** Compatibility entry: original lead/unlock/canPlay ordering can initialize a route.
     * Submission and this GET share the command admission rules; this is not a pure query port. */
    public AjaxResult preference(Long actorMemberId, Long nodeId, String activityId, String topicId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        PreferencePlayCtx pctx = resolvePreferencePlayCtx(memberId, nodeId, activityId, topicId, null);
        if (pctx.error != null) return pctx.error;
        try {
            return AjaxResult.success(preferencePlayService.load(
                    memberId, pctx.playCtx.topicId, nodeId, pctx.template));
        } catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult submitPreference(Long actorMemberId, Long nodeId, PreferenceSubmitRequest request)
    {
        if (request == null) return error("参数有误");
        return playBasicIntentApplication.executeBasicIntent(actorMemberId, "PREFERENCE", request.getActivityId(),
                request.getTopicId(), nodeId == null ? null : nodeId.toString(), request.getRouteActionId(),
                request.getExpectedRouteVersion(), null, request.getRouteSessionId(), request.getRuntimeInstance(),
                request.getAuthorityEpoch(), java.util.Arrays.asList(request.getChoices(), request.getReuseTagCode()),
                originalRun -> submitPreferenceOnce(actorMemberId, nodeId, request, originalRun));
    }

    private AjaxResult submitPreferenceOnce(Long actorMemberId, Long nodeId, PreferenceSubmitRequest request,
            PlayOriginalRun originalRun)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        if (request == null) return error("参数有误");
        PreferencePlayCtx pctx = resolvePreferencePlayCtx(memberId, nodeId,
                request.getActivityId(), request.getTopicId(), request.getRouteActionId(), originalRun);
        if (pctx.error != null) return pctx.error;
        // 规则16 下架闸 + 已完成幂等豁免(与 photo 同口径,§7-2):未完成的新提交仍被拦
        if (playNodeContext.isDelistedNode(pctx.node)
                && !playNodeContext.hasCompletedNodeFact(memberId, request.getActivityId(), pctx.node)) {
            return error("该站已下架，暂时无法完成");
        }
        try {
            Map<String, Object> data = preferencePlayService.submit(memberId, pctx.playCtx.topicId,
                    pctx.playCtx.actId, nodeId, pctx.template, request, originalRun.progressInstance(),
                    outcomeCode -> playNodeCompletionApplication.advanceProgress(memberId, pctx.node, pctx.playCtx.actId,
                            null, null, null, RewardPolicy.FULL, request.getRouteActionId(),
                            request.getExpectedRouteVersion(), outcomeCode, null, originalRun));
            return AjaxResult.success(Boolean.TRUE.equals(data.get("needsTiebreak"))
                    ? "请选择一道取舍题" : "偏好结果已生成", data);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    public AjaxResult confirmPreferenceTag(Long actorMemberId, Long tagId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        try { return AjaxResult.success(playerTopicTagService.confirm(memberId, tagId)); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    public AjaxResult revokePreferenceTag(Long actorMemberId, Long tagId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        try { return AjaxResult.success(playerTopicTagService.revoke(memberId, tagId)); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    public AjaxResult correctPreferenceTag(Long actorMemberId, Long tagId,
                                           PreferenceTagCorrectionRequest request)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        if (request == null) return error("参数有误");
        try { return AjaxResult.success(preferencePlayService.correctPending(memberId, tagId, request.getTagValue())); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    public AjaxResult newLifeOperatingSystem(Long actorMemberId, Long topicId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        try { return AjaxResult.success(preferencePlayService.operatingSystem(memberId, topicId)); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    public PreferencePlayCtx resolvePreferencePlayCtx(Long memberId, Long nodeId, String activityId, String topicId,
                                                        String routeActionId) {
        return resolvePreferencePlayCtx(memberId, nodeId, activityId, topicId, routeActionId, null);
    }

    private PreferencePlayCtx resolvePreferencePlayCtx(Long memberId, Long nodeId, String activityId, String topicId,
            String routeActionId, PlayOriginalRun originalRun) {
        PreferencePlayCtx out = new PreferencePlayCtx();
        if (nodeId == null || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) {
            out.error = error("参数有误");
            return out;
        }
        out.node = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
        if (out.node == null) { out.error = error("节点不存在"); return out; }
        out.playCtx = playNodeContext.resolveCtx(memberId, activityId, topicId, out.node);
        if (out.playCtx.error != null) { out.error = out.playCtx.error; return out; }
        String leadErr = playNodeContext.leadGateError(out.playCtx.actId, out.node);
        if (leadErr != null) { out.error = error(leadErr); return out; }
        String unlockErr = playNodeContext.unlockGateError(memberId, out.playCtx.actId, out.node);
        if (unlockErr == null) {
            unlockErr = playNodeContext.routeAccessError(memberId, out.playCtx.actId, out.playCtx.topicId, out.node, routeActionId);
        }
        if (unlockErr != null) { out.error = error(unlockErr); return out; }
        int mode = playNodeContext.resolveMode(out.playCtx.actId, out.playCtx.topicId);
        if (mode == PlayMode.FREE_COLLECT
                && !playNodeContext.isPureOnlineNode(out.node)
                && !shopDayProofService.hasArrived(memberId, out.playCtx.actId, out.node.getId())) {
            out.error = error("先扫店内静态码进店，再开始任务");
            return out;
        }
        // R18-FREEZE:偏好题判定配置(preferenceJson)按开局快照,老局快照没有该键时回退实时模板
        com.fasterxml.jackson.databind.JsonNode frozenAnswer =
                playNodeContext.frozenAnswer(memberId, out.playCtx.topicId, out.playCtx.actId, out.node.getId(), originalRun);
        if (out.node.getTemplateId() == null && !playNodeContext.hasFrozenContent(frozenAnswer)) {
            out.error = error("该节点未配置偏好题"); return out;
        }
        out.template = frozenAnswer == null
                ? cmsMemberTemplateService.selectCmsMemberTemplateById(out.node.getTemplateId())
                : playNodeContext.frozenTemplateView(out.node, frozenAnswer);
        if (out.template == null || !Integer.valueOf(ValidationMethod.PREFERENCE_GROUP)
                .equals(out.template.getValidationMethod())) {
            out.error = error("该节点未配置偏好题");
            return out;
        }
        return out;
    }
}
