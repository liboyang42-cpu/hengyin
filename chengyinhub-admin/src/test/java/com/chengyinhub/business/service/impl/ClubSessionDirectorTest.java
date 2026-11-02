package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C-12(2026-11-02 拍板默认):执行俱乐部主理人能导演、结束**自己俱乐部的场次**;
 * 发布俱乐部规则不变;没有生效合作单、停用俱乐部、非主理人一律拒。
 */
class ClubSessionDirectorTest {
    private static final Long TOPIC = 990017L;
    private static final Long PUBLISHER_CLUB = 990001L;
    private static final Long EXECUTING_CLUB = 990004L;
    private static final Long EXECUTING_OWNER = 990005L;
    private static final Long PUBLISHER_OWNER = 990002L;

    private final ClubMapper clubMapper = mock(ClubMapper.class);
    private final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
    private final CoopOrderMapper coopOrderMapper = mock(CoopOrderMapper.class);
    private final ClubMemberServiceImpl service = new ClubMemberServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "coopOrderMapper", coopOrderMapper);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setClubId(PUBLISHER_CLUB);
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(clubMapper.selectClubById(PUBLISHER_CLUB)).thenReturn(club(PUBLISHER_CLUB, PUBLISHER_OWNER, 1L));
        when(clubMapper.selectClubById(EXECUTING_CLUB)).thenReturn(club(EXECUTING_CLUB, EXECUTING_OWNER, 1L));
        CoopOrder order = new CoopOrder();
        order.setToType("club");
        order.setToId(EXECUTING_CLUB);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Collections.singletonList(order));
    }

    private static Club club(Long id, Long owner, Long status) {
        Club club = new Club();
        club.setId(id);
        club.setMemberId(owner);
        club.setStatus(status);
        club.setDelFlag(0);
        return club;
    }

    @Test
    void executingClubOwnerDirectsOwnClubSession() {
        assertFalse(service.isTopicOwner(TOPIC, EXECUTING_CLUB, EXECUTING_OWNER), "旧判据:执行俱乐部被挡(走查 C-12)");
        assertTrue(service.isSessionDirector(TOPIC, EXECUTING_CLUB, EXECUTING_OWNER));
    }

    @Test
    void executingOwnerCannotDirectPublisherSessionsOrOthers() {
        assertFalse(service.isSessionDirector(TOPIC, PUBLISHER_CLUB, EXECUTING_OWNER), "不能借合作单导演发布俱乐部的场次");
        assertFalse(service.isSessionDirector(TOPIC, EXECUTING_CLUB, 1L), "非主理人");
        assertTrue(service.isSessionDirector(TOPIC, PUBLISHER_CLUB, PUBLISHER_OWNER), "发布俱乐部规则不变");
    }

    @Test
    void noEffectiveOrderOrInactiveClubRefused() {
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Collections.emptyList());
        assertFalse(service.isSessionDirector(TOPIC, EXECUTING_CLUB, EXECUTING_OWNER));
        CoopOrder order = new CoopOrder();
        order.setToType("club");
        order.setToId(EXECUTING_CLUB);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(Collections.singletonList(order));
        when(clubMapper.selectClubById(EXECUTING_CLUB)).thenReturn(club(EXECUTING_CLUB, EXECUTING_OWNER, 0L));
        assertFalse(service.isSessionDirector(TOPIC, EXECUTING_CLUB, EXECUTING_OWNER), "停用俱乐部");
    }
}
