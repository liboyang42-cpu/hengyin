package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Behavior tests only; real transaction/concurrency verification requires MySQL. */
class ImAdminSendIdempotencyTest {
    private ImServiceImpl service;
    private ImMessageMapper messages;
    private ImConversationMemberMapper members;
    private ImConversationMapper conversations;
    private final Map<String, ImMessage> stored = new HashMap<>();

    @BeforeEach void setup() {
        service = new ImServiceImpl();
        messages = mock(ImMessageMapper.class);
        members = mock(ImConversationMemberMapper.class);
        conversations = mock(ImConversationMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messages);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "conversationMapper", conversations);
        ImConversation c = new ImConversation(); c.setId(20L);
        when(conversations.selectByTypeAndBizKey(ImConversation.TYPE_MERCHANT, "cs_2")).thenReturn(c);
        when(conversations.selectImConversationByIdForUpdate(20L)).thenReturn(c);
        ImConversationMember member = new ImConversationMember(); member.setMemberId(2L); member.setDeleted(0);
        when(members.selectByConvAndMember(20L, 2L)).thenReturn(member);
        when(messages.selectByClientMessageIdForUpdate(eq(20L), eq(0L), anyString()))
                .thenAnswer(a -> stored.get(a.getArgument(2)));
        when(messages.insertImMessage(any())).thenAnswer(a -> {
            ImMessage m = a.getArgument(0); m.setId(100L + stored.size());
            stored.put(m.getClientMessageId(), m); return 1;
        });
    }
    @Test void retryReturnsOriginalWithoutAnySecondUnreadOrMessage() {
        Long id = service.sendFromAdmin(1L, 2L, 1, "hello", null, "request_0001");
        assertEquals(id, service.sendFromAdmin(1L, 2L, 1, "hello", null, "request_0001"));
        verify(messages, times(1)).insertImMessage(any());
        verify(members, times(1)).addUnread(20L, 2L, 1);
        verify(conversations, times(1)).updateLastMessage(eq(20L), eq(1), eq("hello"), any());
    }
    @Test void changedPayloadConflictsAndDifferentAdministratorHasOwnScope() {
        service.sendFromAdmin(1L, 2L, 1, "hello", null, "request_0001");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.sendFromAdmin(1L, 2L, 1, "changed", null, "request_0001"));
        assertEquals(409, e.getCode());
        verify(messages, times(1)).insertImMessage(any());
        service.sendFromAdmin(3L, 2L, 1, "hello", null, "request_0001");
        verify(messages, times(2)).insertImMessage(any());
    }
    @Test void invalidIdentityOrRequestHasNoDatabaseEffects() {
        assertThrows(ServiceException.class, () -> service.sendFromAdmin(null, 2L, 1, "hello", null, "request_0001"));
        assertThrows(ServiceException.class, () -> service.sendFromAdmin(1L, 2L, 1, "hello", null, null));
        verifyNoInteractions(messages, members, conversations);
    }
    @Test void receiptIsScopedAndNeverWritesOrLocks() {
        service.sendFromAdmin(1L, 2L, 1, "hello", null, "request_0001");
        when(messages.selectByClientMessageId(eq(20L), eq(0L), anyString()))
                .thenAnswer(a -> stored.get(a.getArgument(2)));
        clearInvocations(messages, members, conversations);
        Map<String, Object> found = service.adminSendReceipt(1L, 2L, "request_0001");
        assertEquals(true, found.get("found"));
        assertEquals("100", found.get("messageId"));
        assertEquals(false, service.adminSendReceipt(3L, 2L, "request_0001").get("found"));
        assertEquals(false, service.adminSendReceipt(1L, 4L, "request_0001").get("found"));
        verify(messages, never()).insertImMessage(any());
        verify(messages, never()).selectByClientMessageIdForUpdate(anyLong(), anyLong(), anyString());
        verify(conversations, never()).selectImConversationByIdForUpdate(anyLong());
        verify(conversations, never()).insertImConversation(any());
        verifyNoInteractions(members);
    }
    @Test void invalidReceiptIdentityRejectsBeforeDatabaseAccess() {
        assertThrows(ServiceException.class, () -> service.adminSendReceipt(null, 2L, "request_0001"));
        verifyNoInteractions(messages, members, conversations);
    }

}
