package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.route.TopicRouteState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicRoutePoliciesTest {

    @Test
    void playerReadModeUsesPublishedSnapshotAndIgnoresOppositeDraftMode() {
        CmsTopic publishedBranch = new CmsTopic();
        publishedBranch.setRouteMode(TopicRouteState.LINEAR);
        publishedBranch.setPublishedRouteGraphJson("{\"schemaVersion\":1}");
        assertTrue(TopicRouteModePolicy.isEffectiveBranchGraph(publishedBranch, null));

        CmsTopic publishedLinear = new CmsTopic();
        publishedLinear.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        publishedLinear.setPublishedRouteGraphJson(null);
        assertFalse(TopicRouteModePolicy.isEffectiveBranchGraph(publishedLinear, null));

        TopicRouteState frozenBranchSession = new TopicRouteState();
        frozenBranchSession.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        assertTrue(TopicRouteModePolicy.isEffectiveBranchGraph(publishedLinear, frozenBranchSession));
    }

    @Test
    void branchGraphAccessUsesAuthoritativeNodeStateInsteadOfSortOrder() {
        CmsTopicNode first = node(1L, 1L);
        CmsTopicNode branch = node(3L, 3L);
        TopicRouteState state = new TopicRouteState();
        state.setRouteMode("BRANCH_GRAPH");
        state.setNodeStates(new LinkedHashMap<Long, String>() {{ put(3L, "PLAYABLE"); }});

        NodeAccessPolicy policy = new NodeAccessPolicy();
        assertTrue(policy.evaluate("BRANCH_GRAPH", branch, Arrays.asList(first, branch),
                Collections.<Long>emptySet(), state).isAllowed());
        assertFalse(policy.evaluate("LINEAR", branch, Arrays.asList(first, branch),
                Collections.<Long>emptySet(), null).isAllowed());

        state.getNodeStates().put(3L, "COMPLETED");
        assertFalse(policy.evaluate("BRANCH_GRAPH", branch, Arrays.asList(first, branch),
                Collections.<Long>emptySet(), state).isAllowed());
        CmsTopicNode foreign = node(99L, 4L);
        state.getNodeStates().put(99L, "PLAYABLE");
        assertFalse(policy.evaluate("BRANCH_GRAPH", foreign, Arrays.asList(first, branch),
                Collections.<Long>emptySet(), state).isAllowed());
    }

    @Test
    void branchGraphCompletesAtRouteTerminalWithoutCompletingHiddenBranch() {
        TopicRouteState state = new TopicRouteState();
        state.setRouteMode("BRANCH_GRAPH");
        state.setStatus("COMPLETED");
        TopicCompletionPolicy policy = new TopicCompletionPolicy();
        assertTrue(policy.isCompleted("BRANCH_GRAPH", 5, 4, state));
        assertFalse(policy.isCompleted("LINEAR", 5, 4, state));
        assertTrue(policy.isCompleted("LINEAR", 5, 5, null));
    }

    private static CmsTopicNode node(Long id, Long sortId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id);
        node.setTopicId(10L);
        node.setSortId(sortId);
        return node;
    }
}
