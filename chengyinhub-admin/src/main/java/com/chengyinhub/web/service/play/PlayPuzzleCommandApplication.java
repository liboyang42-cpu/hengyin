package com.chengyinhub.web.service.play;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.StringUtils;

import org.springframework.stereotype.Service;
import static com.chengyinhub.common.core.domain.AjaxResult.error;
import com.chengyinhub.web.service.play.PlayNodeContext.PlayCtx;

/** Puzzle commands; paid hints delegate to the existing PuzzlePlayService. */
@Service
public class PlayPuzzleCommandApplication {

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private com.chengyinhub.business.service.IPuzzlePlayService puzzlePlayService;
    @Autowired
    private PlayNodeCompletionApplication playNodeCompletionApplication;
    @Autowired
    private PlayNodeContext playNodeContext;
    @Autowired
    private PlayBasicIntentApplication playBasicIntentApplication;

    /** 查看答案以 0 解谜分辅助完成；仅适用于城市定向文字/单选谜题。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult revealPuzzle(Long actorMemberId, String activityId, String nodeId, String topicId,
            String routeActionId, Integer expectedRouteVersion, Long routeSessionId, String runtimeInstance, Integer authorityEpoch)
    {
        return playBasicIntentApplication.executeBasicIntent(actorMemberId, "REVEAL", activityId, topicId, nodeId,
                routeActionId, expectedRouteVersion, null, routeSessionId, runtimeInstance, authorityEpoch,
                java.util.Collections.emptyList(), originalRun -> revealPuzzleOnce(actorMemberId, activityId, nodeId, topicId, routeActionId, expectedRouteVersion, originalRun));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult revealPuzzle(Long actorMemberId, String activityId, String nodeId, String topicId,
            String routeActionId, Integer expectedRouteVersion)
    {
        return error("必须提供原运行时身份");
    }

    private AjaxResult revealPuzzleOnce(Long actorMemberId, String activityId, String nodeId, String topicId,
            String routeActionId, Integer expectedRouteVersion, PlayOriginalRun originalRun)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(nodeId) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))) {
            return error("参数有误");
        }
        Long nId;
        try { nId = Long.valueOf(nodeId); }
        catch (Exception e) { return error("参数有误"); }

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nId);
        if (node == null) return error("节点不存在");
        PlayCtx ctx = playNodeContext.resolveCtx(memberId, activityId, topicId, node);
        if (ctx.error != null) return ctx.error;
        if (playNodeContext.resolveMode(ctx.actId, ctx.topicId) != PlayMode.LINEAR_ORIENTEERING) {
            return error("该玩法不能查看答案辅助完成");
        }
        String leadErr = playNodeContext.leadGateError(ctx.actId, node);
        if (leadErr != null) return error(leadErr);
        String routeErr = playNodeContext.routeAccessError(memberId, ctx.actId, ctx.topicId, node, routeActionId);
        if (routeErr != null) return error(routeErr);

        // R18-FREEZE:谜题判定/揭示配置按开局快照,老局快照没有该键时回退实时模板
        com.fasterxml.jackson.databind.JsonNode frozenAnswer = playNodeContext.frozenAnswer(memberId, ctx.topicId, ctx.actId, node.getId(), originalRun);
        CmsMemberTemplate tpl = frozenAnswer == null ? puzzlePlayService.selectPuzzleTemplate(node)
                : playNodeContext.frozenTemplateView(node, frozenAnswer);
        if (tpl != null && !Integer.valueOf(1).equals(tpl.getValidationMethod())
                && !Integer.valueOf(3).equals(tpl.getValidationMethod())) {
            tpl = null; // 与 selectPuzzleTemplate 同口径:冻结成非文字/选项玩法 = 不是谜题
        }
        if (tpl == null) return error("该节点未配置文字或选项谜题");
        String reveal = puzzlePlayService.revealText(tpl);
        if (StringUtils.isBlank(reveal)) return error("该谜题未配置答案解析");

        try {
            puzzlePlayService.recordReveal(memberId, ctx.topicId, node.getId(), ctx.actId, originalRun.progressInstance());
        } catch (IllegalStateException e) {
            return error(e.getMessage());
        }
        Map<String, Object> data = playNodeCompletionApplication.advanceProgress(memberId, node, ctx.actId, null,
                PuzzleScorePolicy.revealedScore(), PuzzleScorePolicy.COMPLETION_REVEALED,
                RewardPolicy.FULL, routeActionId, expectedRouteVersion, null, null, originalRun);
        data.put("answerReveal", reveal);
        if (ctx.nightWarning) data.put("nightWarning", true);
        return AjaxResult.success("已查看答案,本题解谜分为 0", data);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult revealPuzzle(Long actorMemberId, String activityId, String nodeId, String topicId) {
        return revealPuzzle(actorMemberId, activityId, nodeId, topicId, null, null);
    }

    /**
     * 城市定向谜题逐级提示。提示免费，但服务端先记录提示等级，再返回该级正文；
     * 解谜分据这份服务端事实计算，不能由客户端自报。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public AjaxResult puzzleHint(Long actorMemberId, String activityId, String nodeId, String topicId, Integer level)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        if (StringUtils.isBlank(nodeId) || (StringUtils.isBlank(activityId) && StringUtils.isBlank(topicId))
                || level == null || level < 1 || level > 2) return error("参数有误");
        Long nId;
        try { nId = Long.valueOf(nodeId); }
        catch (Exception e) { return error("参数有误"); }

        CmsTopicNode node = cmsTopicNodeService.selectCmsTopicNodeById(nId);
        if (node == null) return error("节点不存在");
        PlayCtx ctx = playNodeContext.resolveCtx(memberId, activityId, topicId, node);
        if (ctx.error != null) return ctx.error;
        if (playNodeContext.resolveMode(ctx.actId, ctx.topicId) != PlayMode.LINEAR_ORIENTEERING) {
            return error("该玩法暂不支持解谜分提示");
        }
        String leadErr = playNodeContext.leadGateError(ctx.actId, node);
        if (leadErr != null) return error(leadErr);
        String routeErr = playNodeContext.routeAccessError(memberId, ctx.actId, ctx.topicId, node, null);
        if (routeErr != null) return error(routeErr);

        com.chengyinhub.business.domain.vo.PuzzleHintResult hint;
        try {
            hint = puzzlePlayService.requestHint(memberId, ctx.topicId, node, ctx.actId, level);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", hint.getLevel());
        data.put("hint", hint.getHint());
        data.put("hints", hint.getHints());
        data.put("hintCount", hint.getHintCount());
        data.put("scoreCap", hint.getScoreCap());
        return AjaxResult.success(data);
    }

    /**
     * 积分解锁本节点游玩提示。
     * 收费口径:cms_points_setting(type=1, event_type=14) 分值>0 才收费;
     * 解锁记录=消费流水本身 (memberId, eventType=14, eventId=nodeId)。
     */
    public AjaxResult unlockHint(Long actorMemberId, Long nodeId, String activityId, String topicId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        var result = puzzlePlayService.unlockNodeHint(
                new com.chengyinhub.business.domain.dto.NodeHintUnlockCommand(memberId, nodeId, activityId, topicId));
        if (result.failure() != null) return new AjaxResult(result.failure().originalAjaxCode(), result.failure().message());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hint1", result.hints().hint1()); data.put("hint2", result.hints().hint2()); data.put("cost", result.hints().cost());
        return AjaxResult.success(data);
    }

    /** 兼容入口:旧调用方(含存量单测)只传 nodeId —— 走上面那支的「节点 topicId 兜底」,
     *  仍然要持该主题通行证才会返回提示正文,不是免检通道。 */
    public AjaxResult unlockHint(Long actorMemberId, Long nodeId)
    {
        return unlockHint(actorMemberId, nodeId, null, null);
    }

    /** 解锁提示的积分价:读积分规则(type=1, event_type=14),无规则/0=免费(功能关) */
    public int hintUnlockCost()
    {
        return puzzlePlayService.hintUnlockCost();
    }
}
