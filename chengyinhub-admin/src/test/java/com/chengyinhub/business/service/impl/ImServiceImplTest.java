package com.chengyinhub.business.service.impl;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper.ConversationSummary;
import com.chengyinhub.business.mapper.ImConversationMemberMapper.ClubConversationSummary;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;
import com.chengyinhub.business.service.IUmsMemberService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IM 越权 / 拉黑 防线的特征测试(pin 住现有守卫)。
 * 每条都断言具体守卫并校验副作用被拦截 → 守卫被删则测试失败。
 */
class ImServiceImplTest {

    @Test
    void responseLossRetryReturnsOriginalButIntentionalSameTextRemainsNew() {
        ImServiceImpl service = service();
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(memberMapper.selectByConversation(100L)).thenReturn(Arrays.asList(member(1L), member(2L)));
        String key = "send_0123456789012345";
        java.util.concurrent.atomic.AtomicReference<ImMessage> stored = new java.util.concurrent.atomic.AtomicReference<>();
        when(messageMapper.insertImMessage(any())).thenAnswer(call -> {
            ImMessage msg = call.getArgument(0);
            msg.setId(stored.get() == null ? 61L : 62L);
            stored.set(msg);
            return 1;
        });
        when(messageMapper.selectByClientMessageIdForUpdate(100L, 1L, key))
                .thenAnswer(call -> stored.get());
        ImMessage first = service.send(1L, 100L, null, 1, "hi", null, key);
        ImMessage retry = service.send(1L, 100L, null, 1, "hi", null, key);
        assertEquals(first.getId(), retry.getId());
        verify(messageMapper).insertImMessage(any());
        verify(memberMapper).addUnread(100L, 2L, 1);
        verify(wxContentSecurityService).checkText(eq("hi"), any());

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.send(1L, 100L, null, 1, "different", null, key));
        ImMessage next = service.send(1L, 100L, null, 1, "hi", null, "send_0123456789012346");
        assertEquals(62L, next.getId());
        verify(memberMapper, org.mockito.Mockito.times(2)).addUnread(100L, 2L, 1);
    }

    @Test
    void retryCannotBypassCurrentMembershipOrReturnModeratedContent() {
        ImServiceImpl service = service();
        String key = "send_0123456789012345";
        assertThrows(RuntimeException.class, () -> service.send(1L, 100L, null, 1, "hi", null, key));
        verify(messageMapper, never()).selectByClientMessageIdForUpdate(anyLong(), anyLong(), any());

        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        ImMessage blocked = new ImMessage();
        blocked.setRequestHash(com.chengyinhub.business.service.support.CanonicalDigest.sha256Hex(
                com.alibaba.fastjson2.JSON.toJSONString(Arrays.asList(1, "hi", null))));
        blocked.setStatus(ImMessage.STATUS_BLOCKED);
        when(messageMapper.selectByClientMessageIdForUpdate(100L, 1L, key)).thenReturn(blocked);
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.send(1L, 100L, null, 1, "hi", null, key));
        verify(messageMapper, never()).insertImMessage(any());
        verify(memberMapper, never()).addUnread(anyLong(), anyLong(), anyInt());
    }

    @Test
    void messageJsonNeverExposesContentSecurityTraceId() throws Exception {
        ImMessage message = new ImMessage();
        message.setTraceId("trace-private-value");
        java.util.Map<String, Object> listResponse = new java.util.HashMap<>();
        listResponse.put("list", Collections.singletonList(message));
        String listJson = new ObjectMapper().writeValueAsString(listResponse);
        String sendJson = new ObjectMapper().writeValueAsString(message);

        assertFalse(listJson.contains("trace-private-value"));
        assertFalse(sendJson.contains("trace-private-value"));
        assertFalse(listJson.contains("traceId"));
        assertFalse(sendJson.contains("traceId"));
    }

    @Test
    void userGeneratedSystemBroadcastTextUsesTheAuthenticatedSendersContentSafetyIdentity() throws Exception {
        ImServiceImpl service = service();
        UmsMember director = new UmsMember();
        director.setWxOpenId("director-openid");
        when(umsMemberService.selectUmsMemberById(7L)).thenReturn(director);
        when(wxContentSecurityService.checkText("集合提醒", "director-openid")).thenReturn("内容违规");
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.checkUserGeneratedText(7L, "集合提醒"));

        assertEquals("内容违规", error.getMessage());
        verify(wxContentSecurityService).checkText("集合提醒", "director-openid");
        verify(messageMapper, never()).insertImMessage(any());
    }

    @Test
    void gameBroadcastDeliveryUsesNestedTransactionSoOuterCommandRollbackAlsoRollsBackIm() throws Exception {
        Method regular = ImServiceImpl.class.getMethod(
                "sendSystem", Long.class, Integer.class, String.class, String.class);
        Transactional regularTransaction = regular.getAnnotation(Transactional.class);
        Method isolated = ImServiceImpl.class.getMethod(
                "sendSystemInNestedTransaction", Long.class, Integer.class, String.class, String.class);
        Transactional isolatedTransaction = isolated.getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRED, regularTransaction.propagation());
        assertEquals(Propagation.NESTED, isolatedTransaction.propagation());
    }

    private ImConversationMapper conversationMapper;
    private ImConversationMemberMapper memberMapper;
    private ImMessageMapper messageMapper;
    private ImBlockMapper blockMapper;
    private IUmsMemberService umsMemberService;
    private IWxContentSecurityService wxContentSecurityService;
    private IWxContentCallbackInboxService wxContentCallbackInboxService;
    private CmsTopicMapper cmsTopicMapper;
    private ClubMapper clubMapper;
    private ClubMemberMapper clubMemberMapper;
    private PlayTeamMapper playTeamMapper;
    private IClubParticipationGuard clubParticipationGuard;
    private IAuditTaskService auditTaskService;

    private ImServiceImpl service() {
        conversationMapper = mock(ImConversationMapper.class);
        memberMapper = mock(ImConversationMemberMapper.class);
        org.mockito.Mockito.doAnswer(call -> memberMapper.selectByConvAndMember(call.getArgument(0), call.getArgument(1)))
                .when(memberMapper).selectByConvAndMemberForUpdate(anyLong(), anyLong());
        messageMapper = mock(ImMessageMapper.class);
        blockMapper = mock(ImBlockMapper.class);
        umsMemberService = mock(IUmsMemberService.class);
        wxContentSecurityService = mock(IWxContentSecurityService.class);
        wxContentCallbackInboxService = mock(IWxContentCallbackInboxService.class);
        cmsTopicMapper = mock(CmsTopicMapper.class);
        clubMapper = mock(ClubMapper.class);
        clubMemberMapper = mock(ClubMemberMapper.class);
        playTeamMapper = mock(PlayTeamMapper.class);
        clubParticipationGuard = mock(IClubParticipationGuard.class);
        auditTaskService = mock(IAuditTaskService.class);
        ImServiceImpl service = new ImServiceImpl();
        ReflectionTestUtils.setField(service, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "blockMapper", blockMapper);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(service, "wxContentSecurityService", wxContentSecurityService);
        ReflectionTestUtils.setField(service, "wxContentCallbackInboxService", wxContentCallbackInboxService);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(service, "clubMemberMapper", clubMemberMapper);
        ReflectionTestUtils.setField(service, "playTeamMapper", playTeamMapper);
        ReflectionTestUtils.setField(service, "clubParticipationGuard", clubParticipationGuard);
        ReflectionTestUtils.setField(service, "auditTaskService", auditTaskService);
        return service;
    }

    private ImConversationMember member(long memberId) {
        ImConversationMember m = new ImConversationMember();
        m.setConversationId(100L);
        m.setMemberId(memberId);
        return m;
    }

    // ===== 越权 =====

    @Test
    void staleClubConversationIdCannotBypassGovernanceBan() {
        ImServiceImpl service = service();
        when(conversationMapper.selectImConversationById(100L))
                .thenReturn(groupConv(100L, "club_42"));
        org.mockito.Mockito.doThrow(new com.chengyinhub.common.exception.ServiceException(
                        "你当前无法参与该俱乐部", 403))
                .when(clubParticipationGuard).requireAllowed(42L, 1L);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_TEXT, "hi", null));

        assertEquals("你当前无法参与该俱乐部", error.getMessage());
        verify(messageMapper, never()).insertImMessage(any());
    }

    @Test
    void nonMemberCannotSendIntoConversation() {
        ImServiceImpl service = service();
        // 我不是会话 100 的成员
        when(memberMapper.selectByConvAndMember(100L, 9L)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.send(9L, 100L, null, ImMessage.MSG_TEXT, "hi", null));

        assertEquals("无权在该会话发送", ex.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
    }

    @Test
    void memberCanSendTextIntoOwnConversation() {
        ImServiceImpl service = service();
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(memberMapper.selectByConversation(100L)).thenReturn(Arrays.asList(member(1L), member(2L)));
        when(blockMapper.existsBetween(anyLong(), anyLong())).thenReturn(0);
        when(wxContentSecurityService.checkText(eq("hi"), any())).thenReturn(null);

        ImMessage msg = service.send(1L, 100L, null, ImMessage.MSG_TEXT, "hi", null);

        assertEquals(1L, msg.getSenderId());
        assertEquals(2L, msg.getReceiverId());
        verify(messageMapper).insertImMessage(any(ImMessage.class));
    }

    @Test
    void userCannotSendToSystemConversation() {
        ImServiceImpl service = service();
        ImConversation system = new ImConversation();
        system.setId(100L);
        system.setType(ImConversation.TYPE_SYSTEM);
        system.setStatus(0);
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(conversationMapper.selectImConversationByIdForUpdate(100L)).thenReturn(system);

        com.chengyinhub.common.exception.ServiceException error = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_TEXT, "伪造系统消息", null));

        assertEquals("系统会话不允许用户发送消息", error.getMessage());
        verify(messageMapper, never()).insertImMessage(any());
        verify(conversationMapper, never()).updateLastMessage(anyLong(), anyInt(), any(), any());
        verify(memberMapper, never()).addUnread(anyLong(), anyLong(), anyInt());
    }

    @Test
    void internalSystemSendStillWritesSystemMessage() {
        ImServiceImpl service = service();
        ImConversation system = new ImConversation();
        system.setId(100L);
        system.setType(ImConversation.TYPE_SYSTEM);
        system.setStatus(0);
        when(conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_SYSTEM, "sys_2"))
                .thenReturn(system);

        service.sendSystem(2L, ImMessage.MSG_CARD, "系统通知", "{\"cardType\":\"notice\"}");

        ArgumentCaptor<ImMessage> message = forClass(ImMessage.class);
        verify(messageMapper).insertImMessage(message.capture());
        assertEquals(0L, message.getValue().getSenderId());
        assertEquals(2L, message.getValue().getReceiverId());
        assertEquals(100L, message.getValue().getConversationId());
        verify(conversationMapper).updateLastMessage(eq(100L), eq(ImMessage.MSG_CARD),
                eq("系统通知"), any());
        verify(memberMapper).addUnread(100L, 2L, 1);
    }

    @Test
    void closedGroupCannotAcceptLateMessageOrResurrectUnread() {
        ImServiceImpl service = service();
        ImConversation closed = groupConv(100L, "club_42");
        closed.setStatus(1);
        when(conversationMapper.selectImConversationByIdForUpdate(100L)).thenReturn(closed);
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_TEXT, "hi", null));

        assertEquals("群聊已结束", error.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
        verify(memberMapper, never()).addUnread(anyLong(), anyLong(), anyInt());
    }

    @Test
    void dissolvedClubCannotRaceCreationOfANewLiveGroup() {
        ImServiceImpl service = service();
        when(clubMapper.selectClubByIdForUpdate(42L)).thenReturn(null);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.getOrCreateClubGroup(42L, 1L));

        assertEquals("俱乐部已解散，群聊不可用", error.getMessage());
        verify(conversationMapper, never()).insertImConversation(any());
    }

    @Test
    void teamGroupUsesActiveTeamMembersAsAuthorizationTruth() {
        ImServiceImpl service = service();
        PlayTeam team = new PlayTeam();
        team.setId(31L);
        team.setStatus(PlayTeam.STATUS_RECRUITING);
        PlayTeamMember leader = new PlayTeamMember();
        leader.setTeamId(31L);
        leader.setMemberId(7L);
        leader.setRole(1);
        leader.setStatus(PlayTeamMember.STATUS_JOINED);
        PlayTeamMember member = new PlayTeamMember();
        member.setTeamId(31L);
        member.setMemberId(8L);
        member.setRole(0);
        member.setStatus(PlayTeamMember.STATUS_JOINED);
        ImConversation conversation = groupConv(100L, "team_31");
        conversation.setStatus(0);
        when(playTeamMapper.selectPlayTeamByIdForUpdate(31L)).thenReturn(team);
        when(playTeamMapper.selectActiveMember(31L, 8L)).thenReturn(member);
        when(playTeamMapper.selectActiveMembers(31L)).thenReturn(Arrays.asList(leader, member));
        when(conversationMapper.selectByTypeAndBizKey(ImConversation.TYPE_GROUP, "team_31"))
                .thenReturn(conversation);

        assertEquals(100L, service.getOrCreateTeamGroup(31L, 8L));
        verify(memberMapper).insertImConversationMember(org.mockito.ArgumentMatchers.argThat(
                value -> value.getConversationId().equals(100L) && value.getMemberId().equals(7L)
                        && Integer.valueOf(1).equals(value.getRole())));
        verify(memberMapper).insertImConversationMember(org.mockito.ArgumentMatchers.argThat(
                value -> value.getConversationId().equals(100L) && value.getMemberId().equals(8L)
                        && Integer.valueOf(0).equals(value.getRole())));
    }

    @Test
    void formerTeamMemberCannotSendThroughStaleImMembership() {
        ImServiceImpl service = service();
        ImConversation observed = groupConv(100L, "team_31");
        observed.setStatus(0);
        PlayTeam team = new PlayTeam();
        team.setId(31L);
        team.setStatus(PlayTeam.STATUS_RECRUITING);
        when(conversationMapper.selectImConversationById(100L)).thenReturn(observed);
        when(playTeamMapper.selectPlayTeamByIdForUpdate(31L)).thenReturn(team);
        when(playTeamMapper.selectActiveMember(31L, 8L)).thenReturn(null);
        when(memberMapper.selectByConvAndMember(100L, 8L)).thenReturn(member(8L));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.send(8L, 100L, null, ImMessage.MSG_TEXT, "hi", null));

        assertEquals("已退出队伍，不能继续发送群消息", error.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
    }

    @Test
    void formerClubMemberCannotSendThroughStaleImMembership() {
        ImServiceImpl service = service();
        ImConversation observed = groupConv(100L, "club_42");
        observed.setStatus(0);
        when(conversationMapper.selectImConversationById(100L)).thenReturn(observed);
        Club club = new Club();
        club.setId(42L);
        club.setMemberId(9L);
        club.setStatus(1L);
        when(clubMapper.selectClubByIdForUpdate(42L)).thenReturn(club);
        when(clubMemberMapper.selectByClubAndMember(42L, 1L)).thenReturn(null);
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_TEXT, "hi", null));

        assertEquals("已退出俱乐部，不能继续发送群消息", error.getMessage());
        assertEquals(403, ((com.chengyinhub.common.exception.ServiceException) error).getCode());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
    }

    @Test
    void formerClubMemberCannotReadThroughStaleImMembership() {
        ImServiceImpl service = service();
        ImConversation group = groupConv(100L, "club_42");
        group.setStatus(0);
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(conversationMapper.selectImConversationById(100L)).thenReturn(group);
        Club club = new Club();
        club.setId(42L);
        club.setMemberId(9L);
        when(clubMapper.selectClubById(42L)).thenReturn(club);
        when(clubMemberMapper.selectByClubAndMember(42L, 1L)).thenReturn(null);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.listMessages(1L, 100L, null, 20));

        assertEquals("已退出俱乐部，无权查看群消息", error.getMessage());
        verify(messageMapper, never()).selectVisiblePage(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void inactiveClubCannotAuthorizePollResultThroughStaleMembership() {
        ImServiceImpl service = service();
        ImConversation group = groupConv(100L, "club_42");
        group.setStatus(0);
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(conversationMapper.selectImConversationById(100L)).thenReturn(group);
        Club inactive = new Club();
        inactive.setId(42L);
        inactive.setMemberId(1L);
        inactive.setStatus(0L);
        inactive.setDelFlag(0);
        when(clubMapper.selectClubById(42L)).thenReturn(inactive);

        assertFalse(service.isActiveGroupMember(1L, 100L),
                "停用俱乐部不能通过 current-member 分支继续读取投票");
    }

    @Test
    void formerClubMemberCannotSeeStaleGroupConversationPreview() {
        ImServiceImpl service = service();
        ImConversation group = groupConv(100L, "club_42");
        group.setStatus(0);
        ImConversationMember stale = member(1L);
        stale.setUnreadCount(3);
        when(memberMapper.selectMyConversationSummaries(1L))
                .thenReturn(Collections.singletonList(summary(stale, group)));
        Club club = new Club();
        club.setId(42L);
        club.setMemberId(9L);
        ClubConversationSummary projected = new ClubConversationSummary();
        org.springframework.beans.BeanUtils.copyProperties(club, projected);projected.setViewerMember(false);
        when(memberMapper.selectClubConversationSummaries(1L, List.of(42L))).thenReturn(List.of(projected));

        List<Map<String, Object>> conversations = service.listConversations(1L);

        assertTrue(conversations.isEmpty(), "退会后的脏 IM 行不得继续泄露群名、最后消息或未读数");
    }

    @Test
    void unreadTotalUsesInboxAggregateWithoutLoadingConversationProjection() {
        ImServiceImpl service = service();
        when(memberMapper.sumInboxUnread(1L)).thenReturn(2L);

        assertEquals(2, service.unreadTotal(1L));
        verify(memberMapper).sumInboxUnread(1L);
        verify(memberMapper, never()).selectMyConversationSummaries(anyLong());
        verify(memberMapper, never()).sumUnread(anyLong());
        org.mockito.Mockito.verifyNoInteractions(messageMapper, conversationMapper, clubMapper, clubMemberMapper);
    }

    @Test
    void unreadTotalReturnsZeroForEmptyAggregateWithoutBroadSumFallback() {
        ImServiceImpl service = service();
        when(memberMapper.sumInboxUnread(1L)).thenReturn(null);

        assertEquals(0, service.unreadTotal(1L));
        verify(memberMapper).sumInboxUnread(1L);
        verify(memberMapper, never()).sumUnread(anyLong());
        verify(memberMapper, never()).selectMyConversations(anyLong());
    }

    @Test
    void imageMessageRegistersMysqlInboxAfterInsertAndFailsClosedWhenRegistrationFails() {
        ImServiceImpl service = service();
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(memberMapper.selectByConversation(100L)).thenReturn(Arrays.asList(member(1L), member(2L)));
        when(blockMapper.existsBetween(anyLong(), anyLong())).thenReturn(0);
        when(wxContentSecurityService.checkMediaAsync("https://o/private.jpg", null)).thenReturn("trace-im");
        when(messageMapper.insertImMessage(any(ImMessage.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ImMessage.class).setId(77L);
            return 1;
        });
        when(wxContentCallbackInboxService.register("trace-im",
                IWxContentSecurityService.BIZ_TYPE_IM_MESSAGE, 77L, "https://o/private.jpg"))
                .thenReturn(false);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_IMAGE,
                        "https://o/private.jpg", null));

        assertEquals("图片审核任务登记失败，请稍后重试", error.getMessage());
        verify(wxContentCallbackInboxService).register("trace-im",
                IWxContentSecurityService.BIZ_TYPE_IM_MESSAGE, 77L, "https://o/private.jpg");
    }

    @Test
    void imageMessageFailsClosedBeforeInsertWhenWechatSubmissionFails() {
        ImServiceImpl service = service();
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(memberMapper.selectByConversation(100L)).thenReturn(Arrays.asList(member(1L), member(2L)));
        when(blockMapper.existsBetween(anyLong(), anyLong())).thenReturn(0);
        when(wxContentSecurityService.isEnabled()).thenReturn(true);
        when(wxContentSecurityService.checkMediaAsync("https://o/private.jpg", null)).thenReturn(null);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_IMAGE,
                        "https://o/private.jpg", null));

        assertEquals("内容审核服务繁忙，请稍后重试", error.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
        verify(wxContentCallbackInboxService, never()).register(
                any(), anyInt(), anyLong(), any());
    }

    // ===== 拉黑 =====

    @Test
    void cannotSendToMemberWhoIsBlocked() {
        ImServiceImpl service = service();
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(memberMapper.selectByConversation(100L)).thenReturn(Arrays.asList(member(1L), member(2L)));
        // 平台未禁言我, 但我与对方 2 之间存在拉黑关系
        when(blockMapper.existsBetween(0L, 1L)).thenReturn(0);
        when(blockMapper.existsBetween(1L, 2L)).thenReturn(1);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_TEXT, "hi", null));

        assertEquals("对方暂不可联系", ex.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
    }

    @Test
    void platformMutedAccountCannotSend() {
        ImServiceImpl service = service();
        // 平台对我(memberId=1)全局禁言: existsBetween(0, me) > 0
        when(blockMapper.existsBetween(0L, 1L)).thenReturn(1);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_TEXT, "hi", null));

        assertEquals("账号已被限制发言", ex.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
    }

    @Test
    void cannotStartSingleWithBlockedTarget() {
        ImServiceImpl service = service();
        when(blockMapper.existsBetween(1L, 2L)).thenReturn(1);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.startSingle(1L, 2L));

        assertEquals("对方暂不可联系", ex.getMessage());
        verify(conversationMapper, never()).insertImConversation(any());
    }

    @Test
    void cannotBlockYourself() {
        ImServiceImpl service = service();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.block(1L, 1L));

        assertEquals("参数有误", ex.getMessage());
        verify(blockMapper, never()).insertImBlock(any(ImBlock.class));
    }

    @Test
    void blockPersistsRelation() {
        ImServiceImpl service = service();

        service.block(1L, 2L);

        ArgumentCaptor<ImBlock> captor = forClass(ImBlock.class);
        verify(blockMapper).insertImBlock(captor.capture());
        assertEquals(1L, captor.getValue().getMemberId());
        assertEquals(2L, captor.getValue().getBlockedMemberId());
    }

    @Test
    void unblockRemovesRelation() {
        ImServiceImpl service = service();

        service.unblock(1L, 2L);

        verify(blockMapper).deleteImBlock(1L, 2L);
    }

    // ===== 免打扰(muted) =====

    @Test
    void setMutedRejectsNonMember() {
        ImServiceImpl service = service();
        when(memberMapper.selectByConvAndMember(100L, 9L)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.setMuted(9L, 100L, true));

        assertEquals("无权操作该会话", ex.getMessage());
        verify(memberMapper, never()).updateMuted(anyLong(), anyLong(), any());
    }

    @Test
    void setMutedWritesOneForMember() {
        ImServiceImpl service = service();
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));

        service.setMuted(1L, 100L, true);

        verify(memberMapper).updateMuted(eq(100L), eq(1L), eq(Integer.valueOf(1)));
    }

    @Test
    void setMutedWritesZeroWhenUnmuting() {
        ImServiceImpl service = service();
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));

        service.setMuted(1L, 100L, false);

        verify(memberMapper).updateMuted(eq(100L), eq(1L), eq(Integer.valueOf(0)));
    }

    @Test
    void listConversationsExposesMutedFlag() {
        ImServiceImpl service = service();
        ImConversationMember m = member(1L);
        m.setMuted(1);
        m.setUnreadCount(3);
        ImConversation c = new ImConversation();
        c.setId(100L);
        c.setType(ImConversation.TYPE_SYSTEM);
        when(memberMapper.selectMyConversationSummaries(1L))
                .thenReturn(Collections.singletonList(summary(m, c)));

        java.util.List<java.util.Map<String, Object>> rows = service.listConversations(1L);

        assertEquals(1, rows.size());
        assertEquals(Integer.valueOf(1), rows.get(0).get("muted"));
    }

    // ===== 卡片封洞 =====

    private void stubSendableConversation() {
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(memberMapper.selectByConversation(100L)).thenReturn(Arrays.asList(member(1L), member(2L)));
        when(blockMapper.existsBetween(anyLong(), anyLong())).thenReturn(0);
    }

    @Test
    void userCannotSendGenericCard() {
        ImServiceImpl service = service();
        stubSendableConversation();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_CARD, "x",
                        "{\"title\":\"报名成功\",\"action\":\"/pages/evil/index\"}"));

        assertEquals("不支持的卡片类型", ex.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
    }

    @Test
    void routeCardKeepsOnlyTopicIdAndDropsForgedFields() {
        ImServiceImpl service = service();
        stubSendableConversation();
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setName("外滩夜行");
        topic.setStatus(1);
        topic.setUserStatus(1);
        when(cmsTopicMapper.selectCmsTopicDetailById(eq(7L), isNull())).thenReturn(topic);

        ArgumentCaptor<ImMessage> cap = forClass(ImMessage.class);
        service.send(1L, 100L, null, ImMessage.MSG_CARD, "伪造摘要",
                "{\"cardType\":\"route\",\"topicId\":7,\"name\":\"我是伪造的\",\"action\":\"/pages/evil/index\"}");
        verify(messageMapper).insertImMessage(cap.capture());

        String extra = cap.getValue().getExtraJson();
        assertTrue(extra.contains("\"topicId\":7"), "应保留 topicId");
        assertFalse(extra.contains("我是伪造的"), "客户端传的展示字段必须丢弃");
        assertFalse(extra.contains("action"), "action 是钓鱼载体,必须丢弃");
        assertEquals("[路线]", cap.getValue().getContent(), "落库摘要只标路线卡,不写主题名");
        verify(cmsTopicMapper).selectCmsTopicDetailById(7L, null);
    }

    @Test
    void routeCardRejectsUnlistedTopic() {
        ImServiceImpl service = service();
        stubSendableConversation();
        CmsTopic topic = new CmsTopic();
        topic.setId(7L);
        topic.setStatus(1);
        topic.setUserStatus(0); // 未上架
        when(cmsTopicMapper.selectCmsTopicDetailById(eq(7L), isNull())).thenReturn(topic);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_CARD, "x",
                        "{\"cardType\":\"route\",\"topicId\":7}"));

        assertEquals("该路线暂不可用", ex.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
    }

    @Test
    void routeCardRejectsDeletedUnreviewedOrMembersOnlyTopicWithGenericMessage() {
        ImServiceImpl service = service();
        stubSendableConversation();
        // 匿名口径查不到(已删 / 私密 / 不存在)与查到但未过审、仅成员可见,一律同一句泛称提示
        when(cmsTopicMapper.selectCmsTopicDetailById(eq(7L), isNull())).thenReturn(null);
        RuntimeException missing = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_CARD, "x",
                        "{\"cardType\":\"route\",\"topicId\":7}"));
        assertEquals("该路线暂不可用", missing.getMessage());

        CmsTopic unreviewed = new CmsTopic();
        unreviewed.setId(8L);
        unreviewed.setStatus(0);
        unreviewed.setUserStatus(1);
        when(cmsTopicMapper.selectCmsTopicDetailById(eq(8L), isNull())).thenReturn(unreviewed);
        RuntimeException pending = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_CARD, "x",
                        "{\"cardType\":\"route\",\"topicId\":8}"));
        assertEquals("该路线暂不可用", pending.getMessage());

        CmsTopic membersOnly = new CmsTopic();
        membersOnly.setId(9L);
        membersOnly.setStatus(1);
        membersOnly.setUserStatus(1);
        membersOnly.setClubMemberOnly(1);
        when(cmsTopicMapper.selectCmsTopicDetailById(eq(9L), isNull())).thenReturn(membersOnly);
        RuntimeException privateTopic = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_CARD, "x",
                        "{\"cardType\":\"route\",\"topicId\":9}"));
        assertEquals("该路线暂不可用", privateTopic.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
    }

    @Test
    void locationCardRunsTextSecurityCheck() {
        ImServiceImpl service = service();
        stubSendableConversation();
        // 这正是现状 type==3 绕过的那条:位置名/地址是用户可控文本,必须过内容安全
        when(wxContentSecurityService.checkText(any(), any())).thenReturn("内容含违规信息");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_CARD, "x",
                        "{\"cardType\":\"location\",\"name\":\"脏话\",\"address\":\"某路1号\",\"lat\":31.2,\"lng\":121.4}"));

        assertEquals("内容含违规信息", ex.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
        verify(wxContentSecurityService).checkText(eq("脏话 某路1号"), any());
    }

    @Test
    void locationCardRejectsOutOfRangeCoordinates() {
        ImServiceImpl service = service();
        stubSendableConversation();
        when(wxContentSecurityService.checkText(any(), any())).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_CARD, "x",
                        "{\"cardType\":\"location\",\"name\":\"某处\",\"lat\":999,\"lng\":121.4}"));

        assertEquals("位置坐标不合法", ex.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
    }

    @Test
    void locationCardRebuildsContentForPreview() {
        ImServiceImpl service = service();
        stubSendableConversation();
        when(wxContentSecurityService.checkText(any(), any())).thenReturn(null);

        ArgumentCaptor<ImMessage> cap = forClass(ImMessage.class);
        service.send(1L, 100L, null, ImMessage.MSG_CARD, "伪造摘要",
                "{\"cardType\":\"location\",\"name\":\"外滩源\",\"address\":\"中山东一路\",\"lat\":31.2,\"lng\":121.4}");
        verify(messageMapper).insertImMessage(cap.capture());

        assertEquals("[位置] 外滩源", cap.getValue().getContent());
        // 预览可用性(非安全):lastMsgText 应是重建值而非硬编码 "[消息]"
        verify(conversationMapper).updateLastMessage(eq(100L), eq(ImMessage.MSG_CARD),
                eq("[位置] 外滩源"), any());
    }

    @Test
    void userCannotSendUnknownMsgType() {
        ImServiceImpl service = service();
        stubSendableConversation();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.send(1L, 100L, null, 99, "x", null));

        assertEquals("不支持的消息类型", ex.getMessage());
        verify(messageMapper, never()).insertImMessage(any(ImMessage.class));
    }

    // ===== 群聊身份 =====

    private ImConversation groupConv(long id, String bizKey) {
        ImConversation c = new ImConversation();
        c.setId(id);
        c.setType(ImConversation.TYPE_GROUP);
        c.setBizKey(bizKey);
        return c;
    }

    @Test
    void groupConversationExposesRealClubIdentity() {
        ImServiceImpl service = service();
        when(memberMapper.selectMyConversationSummaries(1L)).thenReturn(Collections.singletonList(
                summary(member(1L), groupConv(100L, "club_42"))));

        Club club = new Club();
        club.setId(42L);
        club.setMemberId(1L);
        club.setName("城瘾夜跑团");
        club.setLogo("https://cdn/logo.png");
        club.setMemberCount(32);
        club.setStatus(1L);
        club.setDelFlag(0);
        ClubConversationSummary projected = new ClubConversationSummary();
        org.springframework.beans.BeanUtils.copyProperties(club, projected);
        when(memberMapper.selectClubConversationSummaries(1L, List.of(42L))).thenReturn(List.of(projected));

        java.util.List<java.util.Map<String, Object>> rows = service.listConversations(1L);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> cp = (java.util.Map<String, Object>) rows.get(0).get("counterparty");
        assertEquals("城瘾夜跑团", cp.get("nickname"));
        assertEquals("https://cdn/logo.png", cp.get("avatar"));
        assertEquals(Integer.valueOf(32), cp.get("memberCount"));
        assertEquals(42L, cp.get("clubId"));
    }

    @Test
    void groupConversationHiddenWhenClubDeleted() {
        ImServiceImpl service = service();
        when(memberMapper.selectMyConversationSummaries(1L)).thenReturn(Collections.singletonList(
                summary(member(1L), groupConv(100L, "club_42"))));
        when(memberMapper.selectClubConversationSummaries(1L, List.of(42L))).thenReturn(Collections.emptyList()); // 俱乐部已删

        java.util.List<java.util.Map<String, Object>> rows = service.listConversations(1L);

        assertTrue(rows.isEmpty(), "俱乐部已删时旧 IM 行不能继续暴露群消息预览");
    }

    @Test
    void groupConversationHiddenForMalformedBizKey() {
        ImServiceImpl service = service();
        when(memberMapper.selectMyConversationSummaries(1L)).thenReturn(Collections.singletonList(
                summary(member(1L), groupConv(100L, "club_abc"))));

        java.util.List<java.util.Map<String, Object>> rows = service.listConversations(1L);

        assertTrue(rows.isEmpty(), "无法解析归属的群聊必须 fail closed");
    }

    // ===== 3-22:私信业务异常必须是 ServiceException,控制器才会把原话给用户 =====

    @Test
    void privateChatBusinessRejectionsAreServiceExceptions() {
        ImServiceImpl service = service();
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(memberMapper.selectByConversation(100L)).thenReturn(Arrays.asList(member(1L), member(2L)));
        when(blockMapper.existsBetween(0L, 1L)).thenReturn(0);
        when(blockMapper.existsBetween(1L, 2L)).thenReturn(1);
        assertEquals("对方暂不可联系", assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_TEXT, "hi", null)).getMessage());

        when(blockMapper.existsBetween(1L, 2L)).thenReturn(0);
        when(wxContentSecurityService.checkText(eq("坏话"), any())).thenReturn("内容含违规信息");
        assertEquals("内容含违规信息", assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.send(1L, 100L, null, ImMessage.MSG_TEXT, "坏话", null)).getMessage());

        when(blockMapper.existsBetween(1L, 2L)).thenReturn(1);
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> service.startSingle(1L, 2L));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> service.block(1L, 1L));
        assertThrows(com.chengyinhub.common.exception.ServiceException.class, () -> service.report(1L, 404L, "x"));
    }

    // ===== RV-p3 F2:举报必须校验消息归属(会话成员 / 不是自己) =====

    private ImMessage message(long id, long conversationId, long senderId) {
        ImMessage m = new ImMessage();
        m.setId(id);
        m.setConversationId(conversationId);
        m.setSenderId(senderId);
        return m;
    }

    @Test
    void cannotReportAMessageFromAConversationImNotIn() {
        ImServiceImpl service = service();
        when(messageMapper.selectImMessageById(61L)).thenReturn(message(61L, 100L, 2L));
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(null);

        com.chengyinhub.common.exception.ServiceException ex =
                assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                        () -> service.report(1L, 61L, "垃圾广告"));

        assertEquals("无权举报该会话的消息", ex.getMessage());
        verify(auditTaskService, never()).reportContent(any(), anyLong(), anyLong(), any());
    }

    @Test
    void cannotReportMyOwnMessage() {
        ImServiceImpl service = service();
        when(messageMapper.selectImMessageById(61L)).thenReturn(message(61L, 100L, 1L));
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));

        com.chengyinhub.common.exception.ServiceException ex =
                assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                        () -> service.report(1L, 61L, "垃圾广告"));

        assertEquals("不能举报自己的消息", ex.getMessage());
        verify(auditTaskService, never()).reportContent(any(), anyLong(), anyLong(), any());
    }

    @Test
    void anotherMembersMessageInASharedConversationCanStillBeReported() {
        ImServiceImpl service = service();
        when(messageMapper.selectImMessageById(61L)).thenReturn(message(61L, 100L, 2L));
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));

        service.report(1L, 61L, "垃圾广告");

        verify(auditTaskService).reportContent("IM_MESSAGE", 61L, 1L, "垃圾广告");
    }

    @Test
    void singleChatMessagesCarryBlockStateSoTheInputCanBeDisabledOnReentry() {
        ImServiceImpl service = service();
        ImConversation single = new ImConversation();
        single.setId(100L);
        single.setType(ImConversation.TYPE_SINGLE);
        when(conversationMapper.selectImConversationById(100L)).thenReturn(single);
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(memberMapper.selectByConversation(100L)).thenReturn(Arrays.asList(member(1L), member(2L)));
        when(messageMapper.selectVisiblePage(anyLong(), anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());

        when(blockMapper.existsBetween(1L, 2L)).thenReturn(1);
        when(blockMapper.selectOne(1L, 2L)).thenReturn(new ImBlock());
        Map<String, Object> mine = service.listMessages(1L, 100L, null, 20);
        assertEquals(Boolean.TRUE, mine.get("blocked"));
        assertEquals(Boolean.TRUE, mine.get("blockedByMe"));

        when(blockMapper.selectOne(1L, 2L)).thenReturn(null);
        Map<String, Object> theirs = service.listMessages(1L, 100L, null, 20);
        assertEquals(Boolean.TRUE, theirs.get("blocked"));
        assertEquals(Boolean.FALSE, theirs.get("blockedByMe"));

        when(blockMapper.existsBetween(1L, 2L)).thenReturn(0);
        assertEquals(Boolean.FALSE, service.listMessages(1L, 100L, null, 20).get("blocked"));
    }
    private ImServiceImpl messagePaginationService() {
        ImServiceImpl service = service();
        ImConversation conversation = new ImConversation();
        conversation.setId(100L);
        conversation.setType(1);
        when(conversationMapper.selectImConversationById(100L)).thenReturn(conversation);
        when(memberMapper.selectByConvAndMember(100L, 1L)).thenReturn(member(1L));
        when(memberMapper.selectByConversation(100L)).thenReturn(Arrays.asList(member(1L), member(2L)));
        return service;
    }

    private List<ImMessage> descendingMessages(int count, long newestId) {
        List<ImMessage> rows = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(message(newestId - i, 100L, 0L));
        return rows;
    }

    @Test
    void messagePaginationExactlyThirtyDoesNotInventAnOlderPage() {
        ImServiceImpl service = messagePaginationService();
        when(messageMapper.selectVisiblePage(eq(100L), eq(1L), eq(0L), anyInt())).thenReturn(descendingMessages(30, 130L));
        Map<String, Object> result = service.listMessages(1L, 100L, 0L, 30);
        assertEquals(Boolean.FALSE, result.get("hasMore"));
        List<ImMessage> rows = (List<ImMessage>) result.get("list");
        assertEquals(30, rows.size());
        assertEquals(101L, rows.get(0).getId());
        assertEquals(130L, rows.get(29).getId());
        assertEquals(101L, result.get("nextCursor"));
        verify(messageMapper).selectVisiblePage(100L, 1L, 0L, 31);
    }

    @Test
    void messagePaginationThirtyOneKeepsProbeOutOfRowsAndCursor() {
        ImServiceImpl service = messagePaginationService();
        when(messageMapper.selectVisiblePage(eq(100L), eq(1L), eq(0L), anyInt())).thenReturn(descendingMessages(31, 130L));
        Map<String, Object> result = service.listMessages(1L, 100L, 0L, 30);
        assertEquals(Boolean.TRUE, result.get("hasMore"));
        List<ImMessage> rows = (List<ImMessage>) result.get("list");
        assertEquals(30, rows.size());
        assertEquals(101L, rows.get(0).getId());
        assertEquals(130L, rows.get(29).getId());
        assertEquals(101L, result.get("nextCursor"));
    }

    @Test
    void messagePaginationEmptyPageHasNoCursorOrMore() {
        ImServiceImpl service = messagePaginationService();
        when(messageMapper.selectVisiblePage(eq(100L), eq(1L), eq(0L), anyInt())).thenReturn(Collections.emptyList());
        Map<String, Object> result = service.listMessages(1L, 100L, 0L, 30);
        assertEquals(Boolean.FALSE, result.get("hasMore"));
        assertEquals(null, result.get("nextCursor"));
        assertTrue(((List<?>) result.get("list")).isEmpty());
    }

    @Test
    void messagePaginationNextPageUsesLastRetainedCursorWithoutRepeatingProbe() {
        ImServiceImpl service = messagePaginationService();
        when(messageMapper.selectVisiblePage(eq(100L), eq(1L), eq(0L), anyInt())).thenReturn(descendingMessages(31, 130L));
        when(messageMapper.selectVisiblePage(eq(100L), eq(1L), eq(101L), anyInt())).thenReturn(Collections.singletonList(message(100L, 100L, 0L)));
        Map<String, Object> first = service.listMessages(1L, 100L, 0L, 30);
        Map<String, Object> second = service.listMessages(1L, 100L, (Long) first.get("nextCursor"), 30);
        List<ImMessage> rows = (List<ImMessage>) second.get("list");
        assertEquals(1, rows.size());
        assertEquals(100L, rows.get(0).getId());
        assertEquals(Boolean.FALSE, second.get("hasMore"));
        assertEquals(100L, second.get("nextCursor"));
        verify(messageMapper).selectVisiblePage(100L, 1L, 101L, 31);
    }

    @Test
    void messagePaginationDeletedMessagesKeepExistingMapperVisibilityPredicate() throws Exception {
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream("mapper/business/ImMessageMapper.xml")) {
            assertTrue(input != null);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) >= 0) output.write(buffer, 0, length);
            String xml = new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            String selectPage = xml.substring(xml.indexOf("<select id=\"selectPage\""), xml.indexOf("</select>", xml.indexOf("<select id=\"selectPage\"")));
            assertTrue(selectPage.contains("status != 2"));
            assertTrue(selectPage.contains("id &lt; #{cursorId}"));
            assertTrue(selectPage.contains("order by id desc"));
        }
        ImServiceImpl service = messagePaginationService();
        when(messageMapper.selectVisiblePage(eq(100L), eq(1L), eq(101L), anyInt())).thenReturn(descendingMessages(29, 99L));
        Map<String, Object> result = service.listMessages(1L, 100L, 101L, 30);
        assertEquals(Boolean.FALSE, result.get("hasMore"));
        assertEquals(71L, result.get("nextCursor"));
        assertEquals(29, ((List<?>) result.get("list")).size());
    }

    private ConversationSummary summary(ImConversationMember member, ImConversation conversation) {
        ConversationSummary row = new ConversationSummary();
        org.springframework.beans.BeanUtils.copyProperties(member, row);
        row.setConversation(conversation);
        return row;
    }

}
