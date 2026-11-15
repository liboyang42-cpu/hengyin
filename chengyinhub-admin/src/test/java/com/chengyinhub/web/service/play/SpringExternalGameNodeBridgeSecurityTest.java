package com.chengyinhub.web.service.play;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.externalgame.ExternalGameNodeBridge.GameResult;
import com.chengyinhub.business.externalgame.ExternalGameNodeBridge.NodePlayOutcome;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.support.TopicAuthorGate;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** CRIT-S2 / HIGH-S1 regression: the external-game result bridge runs the play gates and never grants FULL rewards. */
class SpringExternalGameNodeBridgeSecurityTest {
    private static final long MEMBER = 9L;
    private ICmsTopicNodeService nodes;
    private PlayNodeCompletionApplication completion;
    private PlayNodeContext context;
    private TopicRouteSessionMapper routes;
    private IGrowthService growth;
    private SpringExternalGameNodeBridge bridge;
    private CmsTopicNode node;

    @BeforeEach
    void setUp() {
        nodes = mock(ICmsTopicNodeService.class);
        completion = mock(PlayNodeCompletionApplication.class);
        context = mock(PlayNodeContext.class);
        routes = mock(TopicRouteSessionMapper.class);
        growth = mock(IGrowthService.class);
        bridge = new SpringExternalGameNodeBridge(nodes, mock(TopicAuthorGate.class), completion, growth, context, routes);
        node = new CmsTopicNode();
        node.setId(501L);
        node.setTopicId(88L);
        when(nodes.selectCmsTopicNodeById(501L)).thenReturn(node);
    }

    private static GameResult nodeResult(boolean attested, Long activityId) {
        return new GameResult(MEMBER, "g.node", "EGS_1", 501L, 88L, activityId, 100, true, attested, false);
    }

    private PlayNodeContext.PlayCtx okCtx(long actId) {
        PlayNodeContext.PlayCtx ctx = new PlayNodeContext.PlayCtx();
        ctx.actId = actId;
        ctx.topicId = 88L;
        return ctx;
    }

    private TopicRouteSession run(int status) {
        TopicRouteSession s = new TopicRouteSession();
        s.setId(77L);
        s.setStatus(status);
        return s;
    }

    @Test
    void unattestedResultNeverCompletesNode() {
        NodePlayOutcome out = bridge.onGameResult(nodeResult(false, null));
        assertFalse(out.nodeCompleted());
        verifyNoInteractions(completion);
    }

    @Test
    void unregisteredOrInvalidActivityIsDenied() {
        PlayNodeContext.PlayCtx denied = new PlayNodeContext.PlayCtx();
        denied.error = AjaxResult.error("请先报名");
        when(context.resolveCtx(eq(MEMBER), eq("5"), eq("88"), same(node))).thenReturn(denied);
        AppGapException e = assertThrows(AppGapException.class, () -> bridge.onGameResult(nodeResult(true, 5L)));
        assertEquals(403, e.getStatus());
        verifyNoInteractions(completion);
    }

    @Test
    void routeOrLeadGateDenies() {
        when(context.resolveCtx(eq(MEMBER), isNull(), eq("88"), same(node))).thenReturn(okCtx(0L));
        when(context.routeAccessError(eq(MEMBER), eq(0L), eq(88L), same(node), isNull())).thenReturn("请先完成前一关");
        assertThrows(AppGapException.class, () -> bridge.onGameResult(nodeResult(true, null)));
        verifyNoInteractions(completion);
    }

    @Test
    void nodeOutsideActivePlayRunIsDenied() {
        when(context.resolveCtx(eq(MEMBER), isNull(), eq("88"), same(node))).thenReturn(okCtx(0L));
        when(routes.selectCurrentByOwner(0L, 88L, MEMBER)).thenReturn(run(TopicRouteSession.STATUS_COMPLETED));
        AppGapException e = assertThrows(AppGapException.class, () -> bridge.onGameResult(nodeResult(true, null)));
        assertEquals("NO_ACTIVE_RUN", e.getErrorCode());
        verifyNoInteractions(completion);
    }

    @Test
    void clientTopicMismatchIsDenied() {
        GameResult wrongTopic = new GameResult(MEMBER, "g.node", "EGS_1", 501L, 99L, null, 100, true, true, false);
        assertThrows(AppGapException.class, () -> bridge.onGameResult(wrongTopic));
        verifyNoInteractions(completion);
    }

    @Test
    void attestedResultInRunningRunCompletesWithXpBadgeOnly() {
        when(context.resolveCtx(eq(MEMBER), eq("5"), eq("88"), same(node))).thenReturn(okCtx(5L));
        when(routes.selectCurrentByOwner(5L, 88L, MEMBER)).thenReturn(run(TopicRouteSession.STATUS_RUNNING));
        when(completion.advanceProgress(anyLong(), any(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(Map.of("xp", 5));
        NodePlayOutcome out = bridge.onGameResult(nodeResult(true, 5L));
        assertTrue(out.nodeCompleted());
        verify(completion).advanceProgress(eq(MEMBER), same(node), eq(5L), isNull(), eq(100), eq("EXTGAME_SOLVED"),
                eq(RewardPolicy.XP_BADGE_ONLY), isNull(), isNull(), isNull(), isNull(), any(PlayOriginalRun.class));
        verify(completion, never()).advanceProgress(anyLong(), any(), anyLong(), any(), any(), any(),
                eq(RewardPolicy.FULL), any(), any(), any(), any(), any());
    }

    @Test
    void standaloneXpOnlyWhenServerCapsAllow() {
        bridge.onGameResult(new GameResult(MEMBER, "g.one", "EGS_2", null, null, null, 1, true, false, false));
        verifyNoInteractions(growth);
        NodePlayOutcome out = bridge.onGameResult(new GameResult(MEMBER, "g.one", "EGS_3", null, null, null, 1, true, false, true));
        assertEquals(SpringExternalGameNodeBridge.STANDALONE_PASS_XP, out.xpAwarded());
        verify(growth).addExp(eq(MEMBER), eq(SpringExternalGameNodeBridge.STANDALONE_PASS_XP), contains("EGS_3"));
    }
}
