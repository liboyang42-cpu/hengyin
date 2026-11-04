package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-13:带队路线局缺上一站商家接待时,写入口必须拿到普通业务拒绝(落终态回执、玩家看到原因),
 * 不能等 canPlay 在写事务里抛异常 —— 那样整笔回滚、没有回执,客户端只能报「提交结果待确认」。
 */
class PlayNodeAccessReceptionPrecheckTest {
    private static final long MEMBER = 7L, TOPIC = 300L, ACTIVITY = 500L, NODE = 401L;
    private static final String MISSING = "请先由商家扫描带队主理人的团码完成上一站接待";
    private IGameSessionRuntimeService games;
    private ITopicRouteRuntimeService routes;
    private PlayNodeAccessService access;

    @BeforeEach
    void setUp() {
        games = mock(IGameSessionRuntimeService.class);
        routes = mock(ITopicRouteRuntimeService.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic(); topic.setId(TOPIC); topic.setProductType(1);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        access = new PlayNodeAccessService(mock(ICmsActivityService.class), mock(CmsActivityMapper.class),
                mock(CmsRegistrationMapper.class), mock(ICmsTopicNodeService.class), mock(IPlayerNodeProgressService.class),
                topics, mock(IOmsTicketService.class), mock(ClubLeadMapper.class), games,
                mock(ICmsTopicChapterService.class), routes);
    }

    private static PlayNodeAccessService.NodeScope node() {
        return new PlayNodeAccessService.NodeScope(NODE, TOPIC, null, 2L, 1L, null);
    }

    @Test
    void missingReceptionIsReturnedAsReasonBeforeCanPlayCanThrow() {
        when(games.routeReceptionError(ACTIVITY, NODE)).thenReturn(MISSING);
        when(routes.canPlay(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException(MISSING));
        assertEquals(MISSING, access.routeAccessError(MEMBER, ACTIVITY, TOPIC, node(), "a-1"));
        verify(routes, never()).canPlay(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void otherwiseTheLockedCanPlayPathStillDecides() {
        when(games.routeReceptionError(ACTIVITY, NODE)).thenReturn(null);
        when(routes.canPlay(MEMBER, TOPIC, ACTIVITY, NODE, "a-1")).thenReturn(true);
        assertNull(access.routeAccessError(MEMBER, ACTIVITY, TOPIC, node(), "a-1"));
        when(routes.canPlay(MEMBER, TOPIC, ACTIVITY, NODE, "a-1")).thenReturn(false);
        assertEquals("该地点尚未解锁", access.routeAccessError(MEMBER, ACTIVITY, TOPIC, node(), "a-1"));
    }
}
