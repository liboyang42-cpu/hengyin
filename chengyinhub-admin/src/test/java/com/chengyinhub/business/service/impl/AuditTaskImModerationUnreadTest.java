package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** N-04:举报审核屏蔽/恢复 IM 消息时,只在真实状态迁移后修正一次未读。 */
class AuditTaskImModerationUnreadTest {
    private ImMessageMapper messages;
    private ImConversationMemberMapper members;
    private AuditTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        messages = mock(ImMessageMapper.class);
        members = mock(ImConversationMemberMapper.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "imMessageMapper", messages);
        ReflectionTestUtils.setField(service, "imConversationMemberMapper", members);
    }

    @Test
    void blockDecrementsUnreadOnlyWhenTheMessageActuallyTransitions() {
        when(messages.transitionStatus(9L, ImMessage.STATUS_NORMAL, ImMessage.STATUS_BLOCKED)).thenReturn(1);
        ReflectionTestUtils.invokeMethod(service, "applyImModeration", 9L, true);
        verify(members).adjustUnreadForModeratedMessage(9L, -1);
        verify(messages, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void repeatedBlockKeepsStatusWriteButNeverDecrementsAgain() {
        when(messages.transitionStatus(9L, ImMessage.STATUS_NORMAL, ImMessage.STATUS_BLOCKED)).thenReturn(0);
        ReflectionTestUtils.invokeMethod(service, "applyImModeration", 9L, true);
        verify(members, never()).adjustUnreadForModeratedMessage(anyLong(), anyInt());
        verify(messages).updateStatus(9L, ImMessage.STATUS_BLOCKED);
    }

    @Test
    void restoreIncrementsUnreadBack() {
        when(messages.transitionStatus(9L, ImMessage.STATUS_BLOCKED, ImMessage.STATUS_NORMAL)).thenReturn(1);
        ReflectionTestUtils.invokeMethod(service, "applyImModeration", 9L, false);
        verify(members).adjustUnreadForModeratedMessage(9L, 1);
    }
}
