package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.route.TopicRouteState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TopicRouteModePolicyTest {
    @Test void emptyAndLegacySnapshotsKeepTheirOriginalModes() {
        assertEquals(TopicRouteState.LINEAR,TopicRouteModePolicy.publishedMode((String)null));
        assertEquals(TopicRouteState.LINEAR,TopicRouteModePolicy.publishedMode(" "));
        assertEquals(TopicRouteState.BRANCH_GRAPH,TopicRouteModePolicy.publishedMode("{}"));
        assertEquals(TopicRouteState.BRANCH_GRAPH,TopicRouteModePolicy.publishedMode("{\"routeMode\":\"LINEAR\"}"));
    }
    @Test void fullPublishedLinearIsUsedByAllReadersEvenWhenDraftChangesMode() {
        CmsTopic topic=new CmsTopic();topic.setRouteMode("BRANCH_GRAPH");topic.setPublishedRouteGraphJson("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"LINEAR\"}");
        assertEquals(TopicRouteState.LINEAR,TopicRouteModePolicy.publishedMode(topic));
        assertFalse(TopicRouteModePolicy.isEffectiveBranchGraph(topic,null));
    }
    @Test void fullBranchRemainsBranchAndInvalidLegacyTextDoesNotChangeSemantics() {
        assertEquals(TopicRouteState.BRANCH_GRAPH,TopicRouteModePolicy.publishedMode("{\"runtimeSnapshotVersion\":1,\"routeMode\":\"BRANCH_GRAPH\"}"));
        assertEquals(TopicRouteState.BRANCH_GRAPH,TopicRouteModePolicy.publishedMode("legacy"));
    }
}
