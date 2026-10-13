package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImLegacyReadLockTest {
    private ImServiceImpl service;
    private ImConversationMapper conversations;
    private ImConversationMemberMapper members;
    private ImMessageMapper messages;
    private ImConversation conversation;
    private ImConversationMember active;

    @BeforeEach
    void setup() {
        service = new ImServiceImpl();
        conversations = mock(ImConversationMapper.class);
        members = mock(ImConversationMemberMapper.class);
        messages = mock(ImMessageMapper.class);
        ReflectionTestUtils.setField(service, "conversationMapper", conversations);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "messageMapper", messages);
        ReflectionTestUtils.setField(service, "blockMapper", mock(ImBlockMapper.class));
        conversation = new ImConversation();
        conversation.setId(100L); conversation.setType(ImConversation.TYPE_SINGLE); conversation.setStatus(0);
        active = new ImConversationMember();
        active.setConversationId(100L); active.setMemberId(1L); active.setDeleted(0); active.setLastReadMsgId(80L);
        when(conversations.selectImConversationById(100L)).thenReturn(conversation);
        when(members.selectByConvAndMember(100L, 1L)).thenReturn(active);
        when(conversations.selectImConversationByIdForUpdate(100L)).thenReturn(conversation);
        when(members.selectByConvAndMemberForUpdate(100L, 1L)).thenReturn(active);
    }

    @Test
    void legacyReadLocksCurrentRowsAndDoesNotRegressForMissingOrOlderVisibleMessages() {
        for (Long latest : new Long[] { null, 60L, 90L }) {
            clearInvocations(conversations, members, messages);
            when(messages.selectLatestVisibleIdForUpdate(100L)).thenReturn(latest);
            service.markRead(1L, 100L);
            InOrder order = inOrder(conversations, members, messages);
            order.verify(conversations).selectImConversationByIdForUpdate(100L);
            order.verify(members).selectByConvAndMemberForUpdate(100L, 1L);
            order.verify(messages).selectLatestVisibleIdForUpdate(100L);
            order.verify(members).markRead(100L, 1L, latest != null && latest > 80L ? latest : 80L);
            verify(messages, never()).selectPage(anyLong(), any(), anyInt());
        }
    }

    @Test
    void deletedMembershipRejectsBeforeMessageReadOrMutation() {
        active.setDeleted(1);
        ServiceException error = assertThrows(ServiceException.class, () -> service.markRead(1L, 100L));
        assertEquals(Integer.valueOf(403), error.getCode());
        verifyNoInteractions(messages);
        verify(members, never()).markRead(anyLong(), anyLong(), anyLong());
    }

    @Test
    void closedGroupRejectsEvenWhenBusinessFixtureStillAppearsActive() {
        conversation.setType(ImConversation.TYPE_GROUP); conversation.setBizKey("club_5"); conversation.setStatus(1);
        Club club = new Club(); club.setId(5L); club.setMemberId(1L); club.setStatus(1L); club.setDelFlag(0);
        ClubMapper clubs = mock(ClubMapper.class);
        when(clubs.selectClubById(5L)).thenReturn(club);
        ReflectionTestUtils.setField(service, "clubMapper", clubs);
        ServiceException error = assertThrows(ServiceException.class, () -> service.markRead(1L, 100L));
        assertEquals(Integer.valueOf(409), error.getCode());
        assertEquals("IM_CONVERSATION_CLOSED", error.getDetailMessage());
        verifyNoInteractions(messages);
    }

    @Test
    void missingMembershipKeepsLegacyNoopAndReadHasTransactionBoundary() throws Exception {
        when(members.selectByConvAndMemberForUpdate(100L, 1L)).thenReturn(null);
        when(members.selectByConvAndMember(100L, 1L)).thenReturn(null);
        assertDoesNotThrow(() -> service.markRead(1L, 100L));
        verifyNoInteractions(messages);
        verify(members, never()).markRead(anyLong(), anyLong(), anyLong());
        assertNotNull(ImServiceImpl.class.getMethod("markRead", Long.class, Long.class).getAnnotation(Transactional.class));
    }

    @Test
    void sendRejectsCurrentDepartureAfterEarlierActiveMembershipRead() {
        when(conversations.selectImConversationById(100L)).thenReturn(conversation);
        ImConversationMember old = new ImConversationMember(); old.setMemberId(1L); old.setDeleted(0);
        when(members.selectByConvAndMember(100L, 1L)).thenReturn(old);
        active.setDeleted(1);
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.send(1L, 100L, null, 1, "late send", null));
        assertEquals(Integer.valueOf(403), error.getCode());
        InOrder order = inOrder(conversations, members);
        order.verify(conversations).selectImConversationByIdForUpdate(100L);
        order.verify(members).selectByConvAndMemberForUpdate(100L, 1L);
        verifyNoInteractions(messages);
    }

    @Test
    void systemAndAdminTakeConversationLockBeforeMembershipAndMessage() {
        for (boolean admin : new boolean[] { false, true }) {
            clearInvocations(conversations, members, messages);
            when(conversations.selectByTypeAndBizKey(anyInt(), anyString())).thenReturn(conversation);
            when(members.selectByConvAndMember(100L, 1L)).thenReturn(active);
            if (admin) service.sendFromAdmin(1L, 1, "notice", null);
            else service.sendSystem(1L, 1, "notice", null);
            InOrder order = inOrder(conversations, members, messages);
            order.verify(conversations).selectImConversationByIdForUpdate(100L);
            order.verify(members).selectByConvAndMember(100L, 1L);
            order.verify(messages).insertImMessage(any());
        }
    }
}
