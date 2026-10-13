package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.TopicWorkspaceDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.util.TopicEditWindow;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicLifecycleFailClosedPolicyTest {

    @Test
    void onlySellingIsNamedDirectSaleAllowState() {
        assertTrue(TopicLifecycleState.SELLING.isDirectSaleOpen());
        assertFalse(TopicLifecycleState.PENDING_REVIEW.isDirectSaleOpen());
        assertFalse(TopicLifecycleState.RUNNING.isDirectSaleOpen());
        assertThrows(IllegalArgumentException.class, () -> TopicLifecycleState.fromCode(null));
        assertThrows(IllegalArgumentException.class, () -> TopicLifecycleState.fromCode(99));
    }

    @Test
    void pendingReviewIsEditableNotSellingFrozen() {
        CmsTopic topic = topic(TopicLifecycleState.PENDING_REVIEW.getCode());
        topic.setStatus(0);

        assertEquals(TopicEditWindow.Scope.FULL,
                TopicEditWindow.resolve(topic, 0, new Date()));
    }

    @Test
    void pendingAndUnknownLifecycleAreNotReadyButNotTerminal() {
        for (Integer lifecycle : new Integer[]{TopicLifecycleState.PENDING_REVIEW.getCode(), 99, null}) {
            CmsTopic topic = topic(lifecycle);
            List<TopicWorkspaceDTO.RunBlocker> blockers = TopicRunReadinessPolicy.derive(
                    topic, false, false, false, false, false, false);

            assertTrue(blockers.contains(TopicWorkspaceDTO.RunBlocker.LIFECYCLE_NOT_READY));
            assertFalse(blockers.contains(TopicWorkspaceDTO.RunBlocker.LIFECYCLE_TERMINAL));
        }
    }

    @Test
    void everyKnownLifecycleHasAnExplicitRunReadinessClassification() {
        for (TopicLifecycleState state : TopicLifecycleState.values()) {
            List<TopicWorkspaceDTO.RunBlocker> blockers = TopicRunReadinessPolicy.derive(
                    topic(state.getCode()), false, false, false, false, false, false);
            if (state.isRunNotReady()) {
                assertEquals(java.util.Collections.singletonList(
                        TopicWorkspaceDTO.RunBlocker.LIFECYCLE_NOT_READY), blockers);
            } else if (state.isRunTerminal()) {
                assertEquals(java.util.Collections.singletonList(
                        TopicWorkspaceDTO.RunBlocker.LIFECYCLE_TERMINAL), blockers);
            } else {
                assertTrue(blockers.isEmpty(), state.name());
            }
        }
    }

    private CmsTopic topic(Integer lifecycle) {
        CmsTopic topic = new CmsTopic();
        topic.setLifecycle(lifecycle);
        topic.setDelFlag(0);
        return topic;
    }
}
