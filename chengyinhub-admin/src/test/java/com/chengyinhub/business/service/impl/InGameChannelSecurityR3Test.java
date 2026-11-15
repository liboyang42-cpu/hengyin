package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.mapper.RoamMapper;
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

/** Regression tests for HIGH-S3, HIGH-S9 and MED-S6 (in-game channel authorization and privacy). */
class InGameChannelSecurityR3Test
{
    private static final long OWNER = 1001L;
    private static final long PAID = 2002L;
    private static final long OUTSIDER = 9999L;
    private static final long TOPIC_ID = 77L;

    private InGameChannelService service;
    private ImConversationMapper conversationMapper;
    private ImConversationMemberMapper memberMapper;
    private ImMessageMapper messageMapper;
    private CmsTopicMapper topicMapper;
    private PlayTeamMapper playTeamMapper;
    private RoamMapper roamMapper;
    private IUmsMemberService members;

    @BeforeEach
    void setUp()
    {
        service = new InGameChannelService();
        conversationMapper = mock(ImConversationMapper.class);
        memberMapper = mock(ImConversationMemberMapper.class);
        messageMapper = mock(ImMessageMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        playTeamMapper = mock(PlayTeamMapper.class);
        roamMapper = mock(RoamMapper.class);
        members = mock(IUmsMemberService.class);
        ReflectionTestUtils.setField(service, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "playTeamMapper", playTeamMapper);
        ReflectionTestUtils.setField(service, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(service, "umsMemberService", members);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setName("付费场次");
        topic.setMemberId(OWNER);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
    }

    private ImConversation conv(long id, String bizKey)
    {
        ImConversation c = new ImConversation();
        c.setId(id);
        c.setType(ImConversation.TYPE_GROUP);
        c.setBizKey(bizKey);
        c.setStatus(0);
        c.setLastMsgText("私密聊天内容");
        return c;
    }

    @Test
    void highS3_outsiderCannotResolveTopicChannel()
    {
        when(playTeamMapper.hasPaidRegistration(OUTSIDER, 1, TOPIC_ID)).thenReturn(0);
        for (String scope : new String[] {"topic", "session"})
        {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.resolveChannel(OUTSIDER, scope, null, TOPIC_ID, null, null, null, null));
            assertEquals(403, ex.getCode());
        }
        verify(memberMapper, never()).insertImConversationMember(any());
        verify(conversationMapper, never()).insertImConversation(any());
    }

    @Test
    void highS3_paidParticipantAndOwnerMayResolveTopicChannel()
    {
        when(playTeamMapper.hasPaidRegistration(PAID, 1, TOPIC_ID)).thenReturn(1);
        when(conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "topic_" + TOPIC_ID))
                .thenReturn(conv(50L, "topic_" + TOPIC_ID));
        assertDoesNotThrow(() -> service.resolveChannel(PAID, "topic", null, TOPIC_ID, null, null, null, null));
        assertDoesNotThrow(() -> service.resolveChannel(OWNER, "topic", null, TOPIC_ID, null, null, null, null));
    }

    @Test
    void medS6_summaryForNonMemberHidesConversationIdAndLastMessage()
    {
        when(conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "topic_" + TOPIC_ID))
                .thenReturn(conv(50L, "topic_" + TOPIC_ID));
        when(memberMapper.selectByConvAndMember(50L, OUTSIDER)).thenReturn(null);

        Map<String, Object> out = service.getSummary(OUTSIDER, "topic", null, TOPIC_ID, null, null, null, null);
        assertEquals(Boolean.TRUE, out.get("exists"));
        assertEquals(0, out.get("unread"));
        assertFalse(out.containsKey("conversationId"));
        assertFalse(out.containsKey("lastMsgText"));
        assertFalse(out.containsKey("lastMsgAt"));
    }

    @Test
    void highS9_roamEventSkippedWithoutPresenceOptIn()
    {
        when(roamMapper.countPresenceVisibleSessions(PAID)).thenReturn(0);
        service.postRoamEvent(InGameChannelService.EVENT_CHECKIN, PAID, "徐汇区", "武康大楼", null);
        verifyNoInteractions(messageMapper);
        verify(memberMapper, never()).insertImConversationMember(any());
    }

    @Test
    void highS9_roamEventUsesMaskedLabelNeverRealNameAndDoesNotAutoJoinActor()
    {
        long actor = 123456L;
        UmsMember m = new UmsMember();
        m.setId(actor);
        m.setNickname(null);
        m.setName("张三");
        when(members.selectUmsMemberById(actor)).thenReturn(m);
        when(roamMapper.countPresenceVisibleSessions(actor)).thenReturn(1);
        when(conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "roam_nearby"))
                .thenReturn(conv(60L, "roam_nearby"));
        when(memberMapper.selectByConversation(60L)).thenReturn(List.of());
        doAnswer(inv -> { ((ImMessage) inv.getArgument(0)).setId(1L); return 1; })
                .when(messageMapper).insertImMessage(any(ImMessage.class));

        service.postRoamEvent(InGameChannelService.EVENT_CHECKIN, actor, null, "武康大楼", null);

        ArgumentCaptor<ImMessage> cap = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).insertImMessage(cap.capture());
        String text = cap.getValue().getContent();
        assertFalse(text.contains("张三"), text);
        assertFalse(text.contains(String.valueOf(actor)), text);
        assertTrue(text.contains("玩家1***6"), text);
        verify(memberMapper, never()).insertImConversationMember(any());
    }

    @Test
    void highS9_playEventLabelNeverFallsBackToRealName()
    {
        long actor = 55L;
        UmsMember m = new UmsMember();
        m.setId(actor);
        m.setName("李四");
        when(members.selectUmsMemberById(actor)).thenReturn(m);
        when(conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "topic_" + TOPIC_ID))
                .thenReturn(conv(70L, "topic_" + TOPIC_ID));
        when(memberMapper.selectByConversation(70L)).thenReturn(List.of());
        doAnswer(inv -> { ((ImMessage) inv.getArgument(0)).setId(2L); return 1; })
                .when(messageMapper).insertImMessage(any(ImMessage.class));
        ImConversationMember row = new ImConversationMember();
        row.setMemberId(actor);
        row.setDeleted(0);
        when(memberMapper.selectByConvAndMember(70L, actor)).thenReturn(row);

        service.postPlayEvent(InGameChannelService.EVENT_FINISH, actor, null, TOPIC_ID, "终点", null);

        ArgumentCaptor<ImMessage> cap = ArgumentCaptor.forClass(ImMessage.class);
        verify(messageMapper).insertImMessage(cap.capture());
        assertFalse(cap.getValue().getContent().contains("李四"));
    }
}
