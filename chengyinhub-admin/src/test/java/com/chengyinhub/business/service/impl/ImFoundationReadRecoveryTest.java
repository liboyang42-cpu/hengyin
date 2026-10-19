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
        when(conversations.selectImConversationByIdForShare(100L)).thenReturn(conversation);
        when(conversations.selectImConversationById(100L)).thenReturn(conversation);
        when(members.selectByConvAndMemberForUpdate(100L, 1L)).thenReturn(active);
        when(members.selectByConvAndMember(100L, 1L)).thenReturn(active);
        when(members.selectByConversation(100L)).thenReturn(Collections.emptyList());
        when(members.markReadVisible(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(1);
        when(messages.selectLatestViewerVisibleForShare(100L, 1L)).thenReturn(message(100L));
        when(messages.selectViewerVisibleByIdForShare(100L, 1L, 80L)).thenReturn(message(80L));
    }
    @Test void explicitReadToKeepsNewerMessagesUnreadAndPreservesLockOrder() {
        when(messages.selectViewerVisibleByIdForShare(100L, 1L, 90L)).thenReturn(message(90L));
        service.markRead(1L, 100L, 90L);
        InOrder order = inOrder(conversations, members, messages);
        order.verify(conversations).selectImConversationByIdForShare(100L);
        order.verify(messages).selectLatestViewerVisibleForShare(100L, 1L);
        order.verify(members).selectByConvAndMemberForUpdate(100L, 1L);
        order.verify(members).markReadVisible(100L, 1L, 90L, visibleOrder(90L));
        verify(members, never()).markRead(anyLong(), anyLong(), anyLong());
    }
    @Test void olderReadToCannotRegressAndForgedFutureIsClampedOrIgnored() {
        when(messages.selectViewerVisibleByIdForShare(100L, 1L, 60L)).thenReturn(message(60L));
        when(messages.selectViewerVisibleByIdForShare(100L, 1L, 999L)).thenReturn(message(999L));
        service.markRead(1L, 100L, 60L); service.markRead(1L, 100L, 998L); service.markRead(1L, 100L, 999L);
        // 60 早于已存 80；998 对当前读者不可见(待审/伪造)，均不前进也不回退。
        verify(members, times(2)).markReadVisible(100L, 1L, 80L, visibleOrder(80L));
        verify(members).markReadVisible(100L, 1L, 100L, visibleOrder(100L));
    }
    @Test void deletedMemberCannotReadReceiptOrMutateReadTo() {
        active.setDeleted(1);
        assertThrows(ServiceException.class, () -> service.readSentMessage(1L, 100L, "original"));
        assertThrows(ServiceException.class, () -> service.markRead(1L, 100L, 90L));
        // 锁序防死锁：最新可见消息在成员锁之前以共享锁读取；拒绝时仍不得写任何消息/已读事实。
        verify(messages, never()).insertImMessage(any());
        verify(members, never()).markReadVisible(anyLong(), anyLong(), anyLong(), anyString());
    }
    @Test void receiptUsesOriginalSenderAndMissingKeepsUnknown() {
        assertNull(service.readSentMessage(1L, 100L, "original"));
        verify(messages).selectByClientMessageIdForUpdate(100L, 1L, "original");
        verify(messages, never()).insertImMessage(any());
        assertThrows(ServiceException.class, () -> service.readSentMessage(2L, 100L, "original"));
        verify(messages, never()).selectByClientMessageIdForUpdate(100L, 2L, "original");
    }
    @Test void incrementReadsEveryPageInAscendingOrderWithoutUsingLatestWindow() {
        ImMessage first = message(81L), second = message(82L), third = message(83L);
        when(messages.selectVisibleAfter(100L, 1L, 80L, 3)).thenReturn(Arrays.asList(first, second, third));
        Map<String, Object> result = service.listMessages(1L, 100L, 0L, 2, 80L);
        List<?> list = (List<?>) result.get("list");
        assertEquals(2, list.size());
        assertEquals(81L, ((ImMessage) list.get(0)).getId());
        assertEquals(82L, result.get("nextAfter")); assertEquals(true, result.get("hasMore"));
        verify(messages, never()).selectPage(anyLong(), any(), anyInt());
        verify(messages, never()).selectVisiblePage(anyLong(), anyLong(), any(), anyInt());
    }
    @Test void newInvalidWatermarksFailBeforeDatabaseRead() {
        assertThrows(ServiceException.class, () -> service.listMessages(1L, 100L, 0L, 20, -1L));
        assertThrows(ServiceException.class, () -> service.markRead(1L, 100L, -1L));
        verifyNoInteractions(conversations, members, messages);
    }
    private ImMessage message(Long id) {
        ImMessage message = new ImMessage(); message.setId(id); message.setConversationId(100L);
        message.setMsgType(1); message.setSenderId(0L); message.setContent("notice");
        message.setVisibleOrderAt(visibleOrder(id)); return message;
    }
    private String visibleOrder(Long id) { return String.format("2026-10-13 10:00:00.%06d", id); }
}
