package com.chengyinhub.web.service.play;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.externalgame.ExternalGameNodeBridge;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.support.TopicAuthorGate;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.web.service.play.PlayNodeContext.PlayCtx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bridges external game node bindings and play result callbacks into CmsTopicNode ownership,
 * PlayNodeCompletionApplication (XP, medals, topic completion), and standalone XP awards.
 *
 * <p>Security (CRIT-S2): a node is only completed for an attested result, after the same play gates the other
 * completion commands run (context/registration/activity window, lead gate, route/unlock gates) and only inside the
 * member's running play run. The client-supplied topic/activity ids are never trusted on their own. Rewards are
 * capped to XP + badge, like other self-reported results (C1 sensor rule).
 */
@Component
public class SpringExternalGameNodeBridge implements ExternalGameNodeBridge {
    static final int STANDALONE_PASS_XP = 10;
    private static final NodePlayOutcome NONE = new NodePlayOutcome(0, null, false);

    private final ICmsTopicNodeService nodeService;
    private final TopicAuthorGate topicAuthorGate;
    private final PlayNodeCompletionApplication completionApp;
    private final IGrowthService growthService;
    private final PlayNodeContext playNodeContext;
    private final TopicRouteSessionMapper routeSessionMapper;

    public SpringExternalGameNodeBridge(
            ICmsTopicNodeService nodeService,
            TopicAuthorGate topicAuthorGate,
            PlayNodeCompletionApplication completionApp,
            @Autowired(required = false) IGrowthService growthService,
            PlayNodeContext playNodeContext,
            TopicRouteSessionMapper routeSessionMapper) {
        this.nodeService = nodeService;
        this.topicAuthorGate = topicAuthorGate;
        this.completionApp = completionApp;
        this.growthService = growthService;
        this.playNodeContext = playNodeContext;
        this.routeSessionMapper = routeSessionMapper;
    }

    @Override
    public NodeOwnerView requireOwnedNode(long creatorMemberId, long nodeId) {
        CmsTopicNode node = nodeService.selectCmsTopicNodeById(nodeId);
        if (node == null || node.getTopicId() == null) {
            throw AppGapException.notFound("TOPIC_NODE_NOT_FOUND");
        }
        CmsTopic topic = topicAuthorGate.topic(node.getTopicId());
        String denial = topicAuthorGate.denial(creatorMemberId, topic, null);
        if (denial != null) {
            throw new AppGapException(403, "FORBIDDEN", denial);
        }
        return new NodeOwnerView(node.getId(), topic.getId());
    }

    @Override
    public NodePlayOutcome onGameResult(GameResult r) {
        long memberId = r.memberId();
        if (r.nodeId() != null) {
            if (!r.passed() || !r.attested()) {
                return NONE;
            }
            CmsTopicNode node = nodeService.selectCmsTopicNodeById(r.nodeId());
            if (node == null || node.getTopicId() == null) {
                throw AppGapException.notFound("TOPIC_NODE_NOT_FOUND");
            }
            if (r.topicId() != null && !r.topicId().equals(node.getTopicId())) {
                throw new AppGapException(403, "PLAY_GATE_DENIED", "该节点不属于本主题");
            }
            String activityId = r.activityId() != null && r.activityId() > 0 ? String.valueOf(r.activityId()) : null;
            PlayCtx ctx = playNodeContext.resolveCtx(memberId, activityId, String.valueOf(node.getTopicId()), node);
            if (ctx == null || ctx.error != null) {
                throw new AppGapException(403, "PLAY_GATE_DENIED", ctx == null ? "无权推进该节点" : message(ctx.error));
            }
            gate(playNodeContext.leadGateError(ctx.actId, node));
            gate(playNodeContext.routeAccessError(memberId, ctx.actId, ctx.topicId, node, null));
            gate(playNodeContext.unlockGateError(memberId, ctx.actId, node));
            TopicRouteSession run = routeSessionMapper.selectCurrentByOwner(ctx.actId, ctx.topicId, memberId);
            if (run == null || !Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(run.getStatus())) {
                throw AppGapException.conflict("NO_ACTIVE_RUN", "该节点不在进行中的对局内");
            }
            Map<String, Object> result = completionApp.advanceProgress(
                    memberId, node, ctx.actId, null, r.score(), "EXTGAME_SOLVED",
                    RewardPolicy.XP_BADGE_ONLY, null, null, null, null,
                    new PlayOriginalRun(run, run.getId(), null, null));
            int xp = result.get("xp") instanceof Number n ? n.intValue() : 0;
            Integer medalRank = result.get("medalRank") instanceof Number m ? m.intValue() : null;
            return new NodePlayOutcome(xp, medalRank, true);
        }
        if (r.passed() && r.standaloneXpAllowed() && growthService != null) {
            growthService.addExp(memberId, STANDALONE_PASS_XP, "外部游戏通关: " + r.gameId() + " [" + r.sessionId() + "]");
            return new NodePlayOutcome(STANDALONE_PASS_XP, null, false);
        }
        return NONE;
    }

    private static void gate(String error) {
        if (error != null) {
            throw new AppGapException(403, "PLAY_GATE_DENIED", error);
        }
    }

    private static String message(com.chengyinhub.common.core.domain.AjaxResult error) {
        Object msg = error.get("msg");
        return msg == null ? "无权推进该节点" : String.valueOf(msg);
    }
}
