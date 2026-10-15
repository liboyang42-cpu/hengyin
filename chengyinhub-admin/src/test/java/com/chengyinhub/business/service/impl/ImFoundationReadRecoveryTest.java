package com.chengyinhub.business.service.impl;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImFoundationReadRecoveryTest {
    private ImServiceImpl service;
    private ImConversationMapper conversations;
    private ImConversationMemberMapper members;
    private ImMessageMapper messages;
    private ImConversationMember active;
    @BeforeEach void setup() {
        service = new ImServiceImpl(); conversations = mock(ImConversationMapper.class);
        members = mock(ImConversationMemberMapper.class); messages = mock(ImMessageMapper.class);
        ReflectionTestUtils.setField(service, "conversationMapper", conversations);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "messageMapper", messages);
        ReflectionTestUtils.setField(service, "blockMapper", mock(ImBlockMapper.class));
        ReflectionTestUtils.setField(service, "umsMemberService", mock(IUmsMemberService.class));
        ImConversation conversation = new ImConversation(); conversation.setId(100L);
        conversation.setType(ImConversation.TYPE_SINGLE); conversation.setStatus(0);
        active = new ImConversationMember(); active.setConversationId(100L); active.setMemberId(1L);
        active.setDeleted(0); active.setLastReadMsgId(80L);
        when(conversations.selectImConversationByIdForUpdate(100L)).thenReturn(conversation);
        when(conversations.selectImConversationById(100L)).thenReturn(conversation);
        when(members.selectByConvAndMemberForUpdate(100L, 1L)).thenReturn(active);
        when(members.selectByConvAndMember(100L, 1L)).thenReturn(active);
        when(members.selectByConversation(100L)).thenReturn(Collections.emptyList());
    }
    @Test void explicitReadToKeepsNewerMessagesUnreadAndPreservesLockOrder() {
        when(messages.selectLatestVisibleIdForUpdate(100L)).thenReturn(100L);
        when(messages.countUnreadAfter(100L, 1L, 90L)).thenReturn(2);
        service.markRead(1L, 100L, 90L);
        InOrder order = inOrder(conversations, members, messages);
        order.verify(conversations).selectImConversationByIdForUpdate(100L);
        order.verify(members).selectByConvAndMemberForUpdate(100L, 1L);
        order.verify(messages).selectLatestVisibleIdForUpdate(100L);
        order.verify(messages).countUnreadAfter(100L, 1L, 90L);
        order.verify(members).markReadTo(100L, 1L, 90L, 2);
        verify(members, never()).markRead(anyLong(), anyLong(), anyLong());
    }
    @Test void olderReadToCannotRegressAndForgedFutureIsClamped() {
        when(messages.selectLatestVisibleIdForUpdate(100L)).thenReturn(100L);
        service.markRead(1L, 100L, 60L); service.markRead(1L, 100L, 999L);
        verify(members).markReadTo(100L, 1L, 80L, 0);
        verify(members).markReadTo(100L, 1L, 100L, 0);
    }
    @Test void deletedMemberCannotReadReceiptOrMutateReadTo() {
        active.setDeleted(1);
        assertThrows(ServiceException.class, () -> service.readSentMessage(1L, 100L, "original"));
        assertThrows(ServiceException.class, () -> service.markRead(1L, 100L, 90L));
        verifyNoInteractions(messages);
    }
    @Test void receiptUsesOriginalSenderAndMissingKeepsUnknown() {
        assertNull(service.readSentMessage(1L, 100L, "original"));
        verify(messages).selectByClientMessageIdForUpdate(100L, 1L, "original");
        verify(messages, never()).insertImMessage(any());
        assertThrows(ServiceException.class, () -> service.readSentMessage(2L, 100L, "original"));
        verify(messages, never()).selectByClientMessageIdForUpdate(100L, 2L, "original");
    }
    @Test void incrementReadsEveryPageInAscendingOrderWithoutUsingLatestWindow() {
        ImMessage first = message(81L), second = message(82L);
        when(messages.selectAfter(100L, 80L, 2)).thenReturn(Arrays.asList(first, second));
        Map<String, Object> result = service.listMessages(1L, 100L, 0L, 2, 80L);
        List<?> list = (List<?>) result.get("list");
        assertEquals(81L, ((ImMessage) list.get(0)).getId());
        assertEquals(82L, result.get("nextAfter")); assertEquals(true, result.get("hasMore"));
        verify(messages, never()).selectPage(anyLong(), any(), anyInt());
    }
    @Test void newInvalidWatermarksFailBeforeDatabaseRead() {
        assertThrows(ServiceException.class, () -> service.listMessages(1L, 100L, 0L, 20, -1L));
        assertThrows(ServiceException.class, () -> service.markRead(1L, 100L, -1L));
        verifyNoInteractions(conversations, members, messages);
    }
    private ImMessage message(Long id) {
        ImMessage message = new ImMessage(); message.setId(id); message.setConversationId(100L);
        message.setMsgType(1); message.setSenderId(0L); message.setContent("notice"); return message;
    }
}
