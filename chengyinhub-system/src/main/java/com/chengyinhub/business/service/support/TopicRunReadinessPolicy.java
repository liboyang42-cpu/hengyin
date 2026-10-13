package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicWorkspaceDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;

import java.util.ArrayList;
import java.util.List;

/**
 * 主题工作台和主题列表共用的可开跑判断。
 *
 * <p>公开池不在输入中：它只表示可否公开申请，不能证明已有承接或自办。
 */
public final class TopicRunReadinessPolicy {

    private TopicRunReadinessPolicy() {
    }

    public static List<TopicWorkspaceDTO.RunBlocker> derive(CmsTopic topic,
                                                             boolean contentStructureMissing,
                                                             boolean ticketConfigurationMissing,
                                                             boolean selfPlayPassConfigurationMissing,
                                                             boolean requiredSupplyMissing,
                                                             boolean clubInvitePending,
                                                             boolean clubFulfillmentUnconfirmed) {
        List<TopicWorkspaceDTO.RunBlocker> blockers = new ArrayList<TopicWorkspaceDTO.RunBlocker>();
        Integer lifecycle = topic == null ? null : topic.getLifecycle();
        TopicLifecycleState lifecycleState = null;
        try {
            lifecycleState = TopicLifecycleState.fromCode(lifecycle);
        } catch (IllegalArgumentException ignored) {
            // 未知/缺失只能观测为未就绪，不能按数值猜成终态。
        }
        if (lifecycleState == null || lifecycleState.isRunNotReady()) {
            blockers.add(TopicWorkspaceDTO.RunBlocker.LIFECYCLE_NOT_READY);
        } else if (lifecycleState.isRunTerminal()) {
            blockers.add(TopicWorkspaceDTO.RunBlocker.LIFECYCLE_TERMINAL);
        }
        if (contentStructureMissing) {
            blockers.add(TopicWorkspaceDTO.RunBlocker.CONTENT_STRUCTURE_MISSING);
        }
        if (ticketConfigurationMissing) {
            blockers.add(TopicWorkspaceDTO.RunBlocker.TICKET_CONFIGURATION_MISSING);
        }
        if (selfPlayPassConfigurationMissing) {
            blockers.add(TopicWorkspaceDTO.RunBlocker.SELF_PLAY_PASS_CONFIGURATION_MISSING);
        }
        if (requiredSupplyMissing) {
            blockers.add(TopicWorkspaceDTO.RunBlocker.REQUIRED_CHAPTER_SUPPLY_MISSING);
        }
        if (clubInvitePending) {
            blockers.add(TopicWorkspaceDTO.RunBlocker.CLUB_INVITE_PENDING);
        }
        if (clubFulfillmentUnconfirmed) {
            blockers.add(TopicWorkspaceDTO.RunBlocker.CLUB_FULFILLMENT_UNCONFIRMED);
        }
        return blockers;
    }

    public static TopicWorkspaceDTO.RunReadinessState classify(
            List<TopicWorkspaceDTO.RunBlocker> blockers) {
        if (blockers == null || blockers.isEmpty()) {
            return TopicWorkspaceDTO.RunReadinessState.READY;
        }
        if (blockers.size() == 1
                && blockers.contains(TopicWorkspaceDTO.RunBlocker.LIFECYCLE_NOT_READY)) {
            return TopicWorkspaceDTO.RunReadinessState.AT_RISK;
        }
        return TopicWorkspaceDTO.RunReadinessState.BLOCKED;
    }
}
