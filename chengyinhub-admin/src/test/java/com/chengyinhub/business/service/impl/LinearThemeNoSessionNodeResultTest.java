package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 2026-11-01 VPS master 测试服实测:老线性主题(无旅程状态)不建局,但已发布内容快照让高级玩法走「主题局」分支,
 * 首站玩法交卷 recordNodeResult 恒抛 409「本局已更新，请刷新」,玩家卡在「进行中」。
 * 契约:无局且主题不需要旅程状态时与 recordMistake 同口径 no-op;需要旅程状态的主题无局照旧 409。
 */
class LinearThemeNoSessionNodeResultTest {
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSessionMapper mapper;
    private CmsTopic topic;

    @BeforeEach void setup() {
        mapper = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        service = new TopicRouteRuntimeServiceImpl(mapper, topics, nodes, templates, mock(PlayerNodeProgressMapper.class), null,
                registrations, null, mock(IShopDayProofService.class), () -> 1L, () -> 10, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        topic = new CmsTopic();
        topic.setId(10L);
        topic.setRouteMode("LINEAR");
        topic.setPublishedConfigVersion(1L); // 同 VPS 上的南京路:已发布版本号在、无冻结路线图(老线性主题)
        when(topics.selectCmsTopicById(10L)).thenReturn(topic);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(11L); node.setTopicId(10L); node.setStatus(1L); node.setMerchantMemberId(30L);
        when(nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(new ArrayList<>(Collections.singletonList(node)));
        when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(new ArrayList<>());
        CurrentRegistrationStubs.currentFromProbe(registrations);
    }

    @Test void plainLinearThemeWithoutSessionRecordsNothingInsteadOfConflict() {
        assertDoesNotThrow(() -> service.recordNodeResult(7L, 10L, 0L, 11L, "default", "advanced:1:1", true, false, 0, Collections.emptyList()));
        verify(mapper, never()).insertSession(any());
        verify(mapper, never()).insertAction(any());
        verify(mapper, never()).updateStateCas(any());
    }

    @Test void journeyThemeWithoutSessionStillConflicts() {
        topic.setJourneyRulesJson(GenericRouteRuntimeServiceTest.rules());
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.recordNodeResult(7L, 10L, 0L, 11L, "default", "advanced:1:1", true, false, 0, Collections.emptyList()));
        assertEquals(409, conflict.getCode().intValue());
    }
}
