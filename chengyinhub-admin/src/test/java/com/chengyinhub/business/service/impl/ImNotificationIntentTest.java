package com.chengyinhub.business.service.impl;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImNotificationIntentTest {
    private ImServiceImpl service;
    private ImNotificationIntentMapper intents;
    private ImMessageMapper messages;
    private ImConversationMapper conversations;
    private ImConversationMemberMapper members;
    @BeforeEach void setup() {
        service = new ImServiceImpl(); intents = mock(ImNotificationIntentMapper.class);
        messages = mock(ImMessageMapper.class); conversations = mock(ImConversationMapper.class);
        members = mock(ImConversationMemberMapper.class);
        ReflectionTestUtils.setField(service, "notificationIntentMapper", intents);
        ReflectionTestUtils.setField(service, "messageMapper", messages);
        ReflectionTestUtils.setField(service, "conversationMapper", conversations);
        ReflectionTestUtils.setField(service, "memberMapper", members);
    }
    @Test void duplicateOriginalIntentReturnsSameReceiptAndNeverWritesMessage() {
        Map<String,Object> row = pending();
        row.put("requestHash", CanonicalDigest.sha256Hex(JSON.toJSONString(Arrays.asList(1L, 3, "报名成功", "{}"))));
        when(intents.selectByKeyForUpdate("registration-signup:11")).thenReturn(row);
        assertEquals(8L, service.enqueue(1L, 3, "报名成功", "{}", "registration-signup:11"));
        assertEquals(8L, service.enqueue(1L, 3, "报名成功", "{}", "registration-signup:11"));
        verifyNoInteractions(messages, conversations, members);
    }
    @Test void sameKeyDifferentContentOrRecipientRejectsInsteadOfClaimingAccepted() {
        Map<String,Object> row = pending(); row.put("requestHash", "original-hash");
        when(intents.selectByKeyForUpdate("registration-signup:11")).thenReturn(row);
        assertThrows(ServiceException.class, () -> service.enqueue(2L, 3, "changed", "{}", "registration-signup:11"));
        verifyNoInteractions(messages);
    }
    @Test void deliveredIntentReturnsOriginalMessageWithoutNewSideEffects() {
        Map<String,Object> row = pending(); row.put("status", "DELIVERED"); row.put("messageId", 61L);
        when(intents.selectByIdForUpdate(8L)).thenReturn(row);
        assertEquals(61L, service.deliverSystemNotification(8L));
        verifyNoInteractions(messages, conversations, members);
    }
    @Test void pendingIntentWritesOriginalMessageKeyAndMarksReceiptInSameTransaction() {
        when(intents.selectByIdForUpdate(8L)).thenReturn(pending());
        ImConversation conversation = new ImConversation(); conversation.setId(100L);
        when(conversations.selectByTypeAndBizKey(ImConversation.TYPE_SYSTEM, "sys_1")).thenReturn(conversation);
        ImConversationMember member = new ImConversationMember(); member.setMemberId(1L); member.setDeleted(0);
        when(members.selectByConvAndMember(100L, 1L)).thenReturn(member);
        doAnswer(invocation -> { ((ImMessage) invocation.getArgument(0)).setId(61L); return 1; }).when(messages).insertImMessage(any());
        when(intents.delivered(8L, 61L)).thenReturn(1);
        assertEquals(61L, service.deliverSystemNotification(8L));
        verify(messages).insertImMessage(argThat(m -> "notification_8".equals(m.getClientMessageId())
                && m.getSenderId() == 0L && m.getReceiverId() == 1L));
        verify(members).addUnread(100L, 1L, 1);
        verify(intents).delivered(8L, 61L);
    }
    @Test void existingOriginalMessageIsReusedWithoutUnreadIncrement() {
        when(intents.selectByIdForUpdate(8L)).thenReturn(pending());
        ImConversation conversation = new ImConversation(); conversation.setId(100L);
        when(conversations.selectByTypeAndBizKey(ImConversation.TYPE_SYSTEM, "sys_1")).thenReturn(conversation);
        ImConversationMember member = new ImConversationMember(); member.setMemberId(1L); member.setDeleted(0);
        when(members.selectByConvAndMember(100L, 1L)).thenReturn(member);
        ImMessage original = new ImMessage(); original.setId(61L);
        when(messages.selectByClientMessageIdForUpdate(100L, 0L, "notification_8")).thenReturn(original);
        when(intents.delivered(8L, 61L)).thenReturn(1);
        assertEquals(61L, service.deliverSystemNotification(8L));
        verify(messages, never()).insertImMessage(any()); verify(members, never()).addUnread(anyLong(), anyLong(), anyInt());
    }
    @Test void missingReceiptTransitionThrowsAndKeepsTransactionalBoundary() throws Exception {
        when(intents.selectByIdForUpdate(8L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.deliverSystemNotification(8L));
        assertNotNull(ImServiceImpl.class.getMethod("deliverSystemNotification", Long.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
        assertNotNull(ImServiceImpl.class.getMethod("enqueue", Long.class, Integer.class, String.class, String.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }
    private Map<String,Object> pending() {
        Map<String,Object> row = new HashMap<>(); row.put("id", 8L); row.put("recipient", 1L);
        row.put("msgType", 3); row.put("content", "报名成功"); row.put("extraJson", "{}"); row.put("status", "PENDING"); return row;
    }
}
