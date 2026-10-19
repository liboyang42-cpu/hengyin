package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.RoamHangoutMapper;
import com.chengyinhub.business.service.impl.ImServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiImReadAuthorizationTest {

    @Test
    void messagesEntryRejectsFormerMemberBeforeReadingMessageRows() {
        ImConversationMemberMapper conversationMemberMapper = mock(ImConversationMemberMapper.class);
        ImConversationMapper conversationMapper = mock(ImConversationMapper.class);
        ClubMapper clubMapper = mock(ClubMapper.class);
        ClubMemberMapper clubMemberMapper = mock(ClubMemberMapper.class);
        ImMessageMapper messageMapper = mock(ImMessageMapper.class);
        ImServiceImpl service = new ImServiceImpl();
        ReflectionTestUtils.setField(service, "memberMapper", conversationMemberMapper);
        ReflectionTestUtils.setField(service, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(service, "clubMemberMapper", clubMemberMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);

        ImConversationMember staleMembership = new ImConversationMember();
        staleMembership.setConversationId(100L);
        staleMembership.setMemberId(1L);
        when(conversationMemberMapper.selectByConvAndMember(100L, 1L)).thenReturn(staleMembership);
        ImConversation group = new ImConversation();
        group.setId(100L);
        group.setType(ImConversation.TYPE_GROUP);
        group.setBizKey("club_42");
        group.setStatus(0);
        when(conversationMapper.selectImConversationById(100L)).thenReturn(group);
        Club club = new Club();
        club.setId(42L);
        club.setMemberId(9L);
        club.setStatus(1L);
        club.setDelFlag(0);
        when(clubMapper.selectClubById(42L)).thenReturn(club);
        when(clubMemberMapper.selectByClubAndMember(42L, 1L)).thenReturn(null);

        ApiImController controller = spy(new ApiImController());
        doReturn(1L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "imService", service);

        AjaxResult result = controller.messages("100", null, "20");

        assertEquals("操作失败，请稍后重试", result.get(AjaxResult.MSG_TAG));
        verify(clubMemberMapper).selectByClubAndMember(42L, 1L);
        verify(messageMapper, never()).selectVisiblePage(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void historicalHangoutMemberGetsTerminalClosedStateWithoutReadingHistory() {
        ImConversationMemberMapper conversationMemberMapper = mock(ImConversationMemberMapper.class);
        ImConversationMapper conversationMapper = mock(ImConversationMapper.class);
        ImMessageMapper messageMapper = mock(ImMessageMapper.class);
        ImServiceImpl service = new ImServiceImpl();
        ReflectionTestUtils.setField(service, "memberMapper", conversationMemberMapper);
        ReflectionTestUtils.setField(service, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ImConversationMember historicalMembership = new ImConversationMember();
        historicalMembership.setConversationId(100L);
        historicalMembership.setMemberId(1L);
        historicalMembership.setDeleted(1);
        when(conversationMemberMapper.selectByConvAndMember(100L, 1L)).thenReturn(historicalMembership);
        ImConversation closedHangout = new ImConversation();
        closedHangout.setId(100L);
        closedHangout.setType(ImConversation.TYPE_GROUP);
        closedHangout.setBizKey("hangout_5");
        closedHangout.setStatus(1);
        when(conversationMapper.selectImConversationById(100L)).thenReturn(closedHangout);
        ApiImController controller = spy(new ApiImController());
        doReturn(1L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "imService", service);

        AjaxResult result = controller.messages("100", null, "20");

        assertEquals(409, result.get(AjaxResult.CODE_TAG));
        assertEquals("HANGOUT_CLOSED", result.get("errorCode"));
        assertEquals("/subpackageB/pages/im/list/index", result.get("returnPath"));
        verify(messageMapper, never()).selectVisiblePage(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void activeHangoutMemberCanStillReadMessages() {
        ImConversationMemberMapper conversationMemberMapper = mock(ImConversationMemberMapper.class);
        ImConversationMapper conversationMapper = mock(ImConversationMapper.class);
        ImMessageMapper messageMapper = mock(ImMessageMapper.class);
        RoamHangoutMapper hangoutMapper = mock(RoamHangoutMapper.class);
        ImServiceImpl service = new ImServiceImpl();
        ReflectionTestUtils.setField(service, "memberMapper", conversationMemberMapper);
        ReflectionTestUtils.setField(service, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "roamHangoutMapper", hangoutMapper);
        ImConversationMember membership = new ImConversationMember();
        membership.setConversationId(100L);
        membership.setMemberId(1L);
        membership.setDeleted(0);
        when(conversationMemberMapper.selectByConvAndMember(100L, 1L)).thenReturn(membership);
        ImConversation activeHangout = new ImConversation();
        activeHangout.setId(100L);
        activeHangout.setType(ImConversation.TYPE_GROUP);
        activeHangout.setBizKey("hangout_5");
        activeHangout.setStatus(0);
        when(conversationMapper.selectImConversationById(100L)).thenReturn(activeHangout);
        when(hangoutMapper.selectStatusById(5L)).thenReturn(0);
        when(messageMapper.selectVisiblePage(100L, 1L, 0L, 21)).thenReturn(java.util.Collections.emptyList());
        ApiImController controller = spy(new ApiImController());
        doReturn(1L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "imService", service);

        AjaxResult result = controller.messages("100", "0", "20");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(messageMapper).selectVisiblePage(100L, 1L, 0L, 21);
    }

    @Test
    void knownMachineTerminalsSurviveAllMessageReadModesSendAndReadEndpoints() {
        for (String code : new String[] { "HANGOUT_CLOSED", "IM_CONVERSATION_CLOSED" }) {
            com.chengyinhub.business.service.IImService service = mock(com.chengyinhub.business.service.IImService.class);
            com.chengyinhub.common.exception.ServiceException error =
                    new com.chengyinhub.common.exception.ServiceException("terminal", 409).setDetailMessage(code);
            when(service.listMessages(1L, 100L, 0L, 20)).thenThrow(error);
            when(service.listMessagesAfter(1L, 100L, 3L, null, 20)).thenThrow(error);
            when(service.findOwnMessage(1L, 100L, "send_0123456789012345")).thenThrow(error);
            when(service.send(1L, 100L, null, 1, "hello", null)).thenThrow(error);
            org.mockito.Mockito.doThrow(error).when(service).markRead(1L, 100L, 61L);
            org.mockito.Mockito.doThrow(error).when(service).markRead(1L, 100L);
            ApiImController controller = spy(new ApiImController());
            doReturn(1L).when(controller).getAppUserId();
            ReflectionTestUtils.setField(controller, "imService", service);
            for (AjaxResult result : new AjaxResult[] {
                    controller.messages("100", null, null, null, "20", null),
                    controller.messages("100", null, "3", null, "20", null),
                    controller.messages("100", null, null, null, "20", "send_0123456789012345"),
                    controller.send("100", null, "1", "hello", null),
                    controller.read("100", "61"), controller.read("100") }) {
                assertEquals(409, result.get(AjaxResult.CODE_TAG));
                assertEquals(code, result.get("errorCode"));
                assertEquals("/subpackageB/pages/im/list/index", result.get("returnPath"));
            }
        }
    }

    @Test
    void unknownDetailCannotForgeAChatTerminalAndUnexpectedReadFailuresStayGeneric() {
        com.chengyinhub.business.service.IImService service = mock(com.chengyinhub.business.service.IImService.class);
        when(service.listMessages(1L, 100L, 0L, 20)).thenThrow(
                new com.chengyinhub.common.exception.ServiceException("business", 409).setDetailMessage("UNTRUSTED"));
        ApiImController controller = spy(new ApiImController());
        doReturn(1L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "imService", service);
        AjaxResult result = controller.messages("100", null, null, null, "20", null);
        assertEquals(null, result.get("errorCode"));
        org.mockito.Mockito.doThrow(new IllegalStateException("internal-only")).when(service).markRead(1L, 100L, 61L);
        assertEquals("操作失败，请稍后重试", controller.read("100", "61").get(AjaxResult.MSG_TAG));
    }
}
