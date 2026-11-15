package com.chengyinhub.web.service.play;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.externalgame.ExternalGameNodeBridge;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.support.TopicAuthorGate;
import com.chengyinhub.common.constant.RewardPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bridges external game node bindings and signed play result callbacks into CmsTopicNode ownership,
 * PlayNodeCompletionApplication (XP, medals, topic completion), and standalone XP awards.
 */
@Component
public class SpringExternalGameNodeBridge implements ExternalGameNodeBridge {
    private static final int STANDALONE_PASS_XP = 10;

    private final ICmsTopicNodeService nodeService;
    private final TopicAuthorGate topicAuthorGate;
    private final PlayNodeCompletionApplication completionApp;
    private final IGrowthService growthService;

    public SpringExternalGameNodeBridge(
            ICmsTopicNodeService nodeService,
            TopicAuthorGate topicAuthorGate,
            PlayNodeCompletionApplication completionApp,
            @Autowired(required = false) IGrowthService growthService) {
        this.nodeService = nodeService;
        this.topicAuthorGate = topicAuthorGate;
        this.completionApp = completionApp;
        this.growthService = growthService;
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
    public NodePlayOutcome onGameResult(long memberId, String gameId, String sessionId,
                                        Long nodeId, Long topicId, Long activityId,
                                        Integer score, boolean passed) {
        if (nodeId != null) {
            if (!passed) {
                return new NodePlayOutcome(0, null, false);
            }
            CmsTopicNode node = nodeService.selectCmsTopicNodeById(nodeId);
            if (node == null || node.getTopicId() == null) {
                throw AppGapException.notFound("TOPIC_NODE_NOT_FOUND");
            }
            long actId = activityId != null && activityId > 0 ? activityId : 0L;
            Map<String, Object> result = completionApp.advanceProgress(
                    memberId, node, actId, null, score, "EXTGAME_SOLVED",
                    RewardPolicy.FULL, null, null, null, null, null);
            int xp = result.get("xp") instanceof Number n ? n.intValue() : 0;
            Integer medalRank = result.get("medalRank") instanceof Number m ? m.intValue() : null;
            return new NodePlayOutcome(xp, medalRank, true);
        }
        if (passed && growthService != null) {
            growthService.addExp(memberId, STANDALONE_PASS_XP, "外部游戏通关: " + gameId);
            return new NodePlayOutcome(STANDALONE_PASS_XP, null, false);
        }
        return new NodePlayOutcome(0, null, false);
    }
}
