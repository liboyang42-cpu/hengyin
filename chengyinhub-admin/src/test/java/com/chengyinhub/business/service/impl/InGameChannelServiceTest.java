package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CityGameMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InGameChannelServiceTest
{
    private InGameChannelService service;
    private ImConversationMapper conversationMapper;
    private ImConversationMemberMapper memberMapper;
    private ImMessageMapper messageMapper;
    private CmsActivityMapper cmsActivityMapper;
    private PlayTeamMapper playTeamMapper;
    private CityGameMapper cityGameMapper;
    private IUmsMemberService umsMemberService;

    @BeforeEach
    void setUp()
    {
        service = new InGameChannelService();
        conversationMapper = mock(ImConversationMapper.class);
        memberMapper = mock(ImConversationMemberMapper.class);
        messageMapper = mock(ImMessageMapper.class);
        cmsActivityMapper = mock(CmsActivityMapper.class);
        playTeamMapper = mock(PlayTeamMapper.class);
        cityGameMapper = mock(CityGameMapper.class);
        umsMemberService = mock(IUmsMemberService.class);

        ReflectionTestUtils.setField(service, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", cmsActivityMapper);
        ReflectionTestUtils.setField(service, "playTeamMapper", playTeamMapper);
        ReflectionTestUtils.setField(service, "cityGameMapper", cityGameMapper);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
    }

    @Test
    void roamBizKeyIsDeterministicAndFitsColumnLimit()
    {
        assertEquals("roam_nearby", InGameChannelService.roamBizKey(null));
        assertEquals("roam_nearby", InGameChannelService.roamBizKey("  "));
        assertEquals("roam_xuhui", InGameChannelService.roamBizKey("Xuhui"));
        String cjk = InGameChannelService.roamBizKey("徐汇区");
        assertEquals(cjk, InGameChannelService.roamBizKey("徐汇区"));
        assertTrue(cjk.startsWith("roam_d_") && cjk.length() <= 64);
        assertTrue(InGameChannelService.isInGameBizKey(cjk));
        assertTrue(InGameChannelService.isInGameBizKey("activity_12"));
        assertTrue(InGameChannelService.isInGameBizKey("topic_9"));
        assertTrue(InGameChannelService.isInGameBizKey("city_team_abc"));
        assertFalse(InGameChannelService.isInGameBizKey("team_9"));
    }

    @Test
    void onRegistrationConfirmedAutoJoinsParticipantAndOrganizer()
    {
        CmsActivity activity = new CmsActivity();
        activity.setId(88L);
        activity.setName("周末城市定向");
        activity.setMemberId(1001L);
        when(cmsActivityMapper.selectCmsActivityById(88L)).thenReturn(activity);
        when(conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "activity_88")).thenReturn(null);
        doAnswer(inv -> {
            ImConversation c = inv.getArgument(0);
            c.setId(500L);
            return 1;
        }).when(conversationMapper).insertImConversation(any(ImConversation.class));

        CmsRegistration reg = new CmsRegistration();
        reg.setId(700L);
        reg.setOwnerType(2L);
        reg.setOwnerId(88L);
        reg.setMemberId(2002L);
        reg.setActivityTitle("周末城市定向");

        Long convId = service.onRegistrationConfirmed(reg);
        assertEquals(500L, convId);

        ArgumentCaptor<ImConversationMember> captor = ArgumentCaptor.forClass(ImConversationMember.class);
        verify(memberMapper, times(2)).insertImConversationMember(captor.capture());
        List<Long> joined = captor.getAllValues().stream().map(ImConversationMember::getMemberId).toList();
        assertTrue(joined.containsAll(List.of(2002L, 1001L)));
    }

    @Test
    void resolveActivityRejectsUnregisteredOutsider()
    {
        CmsActivity activity = new CmsActivity();
        activity.setId(88L);
        activity.setName("周末城市定向");
        activity.setMemberId(1001L);
        when(cmsActivityMapper.selectCmsActivityById(88L)).thenReturn(activity);
        when(playTeamMapper.hasPaidRegistration(999L, 2, 88L)).thenReturn(0);
        when(conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "activity_88")).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.resolveChannel(999L, "activity", 88L, null, null, null, null, null));
        assertEquals(403, ex.getCode());
    }

    @Test
    void postCityGameEventWritesSystemMessageAndIncrementsOtherTeammatesUnread()
    {
        String cityTeamId = "team-uuid-123";
        ImConversation conv = new ImConversation();
        conv.setId(610L);
        conv.setType(ImConversation.TYPE_GROUP);
        conv.setBizKey("city_team_" + cityTeamId);
        conv.setStatus(0);
        when(conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "city_team_" + cityTeamId))
                .thenReturn(conv);
        when(cityGameMapper.selectTeam(cityTeamId)).thenReturn(Map.of("teamId", cityTeamId, "name", "蓝鲸队"));
        when(cityGameMapper.selectTeamMembers(cityTeamId)).thenReturn(List.of(
                Map.of("memberId", 11L),
                Map.of("memberId", 12L)
        ));
        UmsMember actor = new UmsMember();
        actor.setId(11L);
        actor.setNickname("阿星");
        when(umsMemberService.selectUmsMemberById(11L)).thenReturn(actor);

        ImConversationMember m11 = new ImConversationMember();
        m11.setMemberId(11L);
        m11.setDeleted(0);
        ImConversationMember m12 = new ImConversationMember();
        m12.setMemberId(12L);
        m12.setDeleted(0);
        when(memberMapper.selectByConvAndMember(610L, 11L)).thenReturn(m11);
        when(memberMapper.selectByConvAndMember(610L, 12L)).thenReturn(m12);
        when(memberMapper.selectByConversation(610L)).thenReturn(List.of(m11, m12));
        doAnswer(inv -> {
            ImMessage msg = inv.getArgument(0);
            msg.setId(9001L);
            return 1;
        }).when(messageMapper).insertImMessage(any(ImMessage.class));

        service.postCityGameEvent(
                InGameChannelService.EVENT_TAKEOVER, 11L, "board-1", cityTeamId, "外滩灯塔", "挑战成功 6:2");

        ArgumentCaptor<ImMessage> msgCap = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).insertImMessage(msgCap.capture());
        assertEquals(0L, msgCap.getValue().getSenderId());
        assertTrue(msgCap.getValue().getContent().contains("[接管]"));
        assertTrue(msgCap.getValue().getContent().contains("外滩灯塔"));

        // SOC-13: one set-based unread update that skips the actor, no per-member loop.
        verify(memberMapper, times(1)).addUnreadForActiveMembersExcept(610L, 11L, 1);
        verify(memberMapper, never()).addUnreadForActiveMember(anyLong(), anyLong(), anyInt());
    }
}
