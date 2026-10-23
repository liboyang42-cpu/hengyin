package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImRouteProjectionTest {
    private ImServiceImpl service;
    private ImMessageMapper messages;
    private CmsTopicMapper topics;
    private ImConversationMapper conversations;
    private ImConversationMemberMapper members;
    private ImConversation conversation;
    private ImMessage original;

    @BeforeEach
    void setup() {
        service = new ImServiceImpl();
        messages = mock(ImMessageMapper.class); topics = mock(CmsTopicMapper.class);
        conversations = mock(ImConversationMapper.class); members = mock(ImConversationMemberMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messages);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "conversationMapper", conversations);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "blockMapper", mock(ImBlockMapper.class));
        ReflectionTestUtils.setField(service, "umsMemberService", mock(IUmsMemberService.class));
        conversation = new ImConversation(); conversation.setId(100L);
        conversation.setType(ImConversation.TYPE_SINGLE); conversation.setStatus(0);
        conversation.setLastMsgType(ImMessage.MSG_CARD); conversation.setLastMsgText("[路线] 旧私密名");
        when(conversations.selectImConversationById(100L)).thenReturn(conversation);
        when(conversations.selectImConversationByIdForUpdate(100L)).thenReturn(conversation);
        ImConversationMember member = new ImConversationMember();
        member.setConversationId(100L); member.setMemberId(1L); member.setDeleted(0);
        when(members.selectByConvAndMember(eq(100L), anyLong())).thenReturn(member);
        when(members.selectByConvAndMemberForUpdate(eq(100L), anyLong())).thenReturn(member);
        when(members.selectMyConversationSummaries(anyLong())).thenAnswer(call -> {
            com.chengyinhub.business.mapper.ImConversationMemberMapper.ConversationSummary row =
                    new com.chengyinhub.business.mapper.ImConversationMemberMapper.ConversationSummary();
            org.springframework.beans.BeanUtils.copyProperties(member, row);
            row.setConversation(conversation);
            List<ImMessage> visible = messages.selectVisiblePage(100L, call.getArgument(0), null, 1);
            row.setLatest(visible == null || visible.isEmpty() ? null : visible.get(0));
            return Collections.singletonList(row);
        });
        when(members.selectByConversation(100L)).thenReturn(Collections.emptyList());
        original = new ImMessage(); original.setId(8L); original.setConversationId(100L);
        original.setSenderId(0L); original.setStatus(ImMessage.STATUS_NORMAL); original.setMsgType(ImMessage.MSG_CARD);
        original.setContent("[路线] 旧私密名"); original.setExtraJson("{\"cardType\":\"route\",\"topicId\":7,\"title\":\"旧私密名\"}");
        original.setRequestHash("original-digest");
        when(messages.selectVisiblePage(eq(100L), anyLong(), any(), anyInt())).thenReturn(Collections.singletonList(original));
    }

    private CmsTopic listed() {
        CmsTopic topic = new CmsTopic(); topic.setId(7L); topic.setName("当前公开名");
        topic.setStatus(1); topic.setUserStatus(1); topic.setDelFlag(0); topic.setClubMemberOnly(0);
        return topic;
    }
    @SuppressWarnings("unchecked")
    private ImMessage history(long viewer) {
        return ((List<ImMessage>) service.listMessages(viewer, 100L, null, 20).get("list")).get(0);
    }
    private void unavailable(long viewer) {
        ImMessage projected = history(viewer);
        assertEquals("路线已不可用", projected.getContent());
        assertEquals(Long.valueOf(7), JSON.parseObject(projected.getExtraJson()).getLong("topicId"));
        assertFalse(projected.getExtraJson().contains("旧私密名"));
        assertEquals("路线已不可用", service.listConversations(viewer).get(0).get("lastMsgText"));
        assertEquals("[路线] 旧私密名", original.getContent());
        assertEquals("original-digest", original.getRequestHash());
        assertEquals("[路线] 旧私密名", conversation.getLastMsgText());
    }
    @Test
    void historyAndSummaryUseCurrentViewerAndDoNotMutateOriginalRows() {
        when(topics.selectCmsTopicDetailById(7L, 1L)).thenReturn(listed());
        when(topics.selectCmsTopicDetailById(7L, 2L)).thenReturn(null);
        assertEquals("[路线] 当前公开名", history(1).getContent());
        assertEquals("[路线] 当前公开名", service.listConversations(1L).get(0).get("lastMsgText"));
        unavailable(2); assertNotSame(original, history(1));
        verify(messages, never()).updateStatus(anyLong(), anyInt());
    }
    @Test
    void missingDeletedUnlistedAndRejectedRoutesAreUnavailable() {
        unavailable(1);
        for (int state = 0; state < 3; state++) {
            CmsTopic topic = listed();
            if (state == 0) topic.setDelFlag(1);
            if (state == 1) topic.setUserStatus(0);
            if (state == 2) topic.setStatus(0);
            when(topics.selectCmsTopicDetailById(7L, 1L)).thenReturn(topic);
            unavailable(1);
        }
    }
    @Test
    void privateRouteRemainsHiddenEvenWhenDetailGateAllowsAuthorMemberOwnerOrBuyer() {
        CmsTopic privateTopic = listed(); privateTopic.setClubMemberOnly(1); privateTopic.setMemberId(1L);
        privateTopic.setClubId(22L);
        for (long viewer : new long[] { 1L, 2L, 3L, 4L }) {
            when(topics.selectCmsTopicDetailById(7L, viewer)).thenReturn(privateTopic);
            unavailable(viewer);
        }
    }
    @Test
    void privateRouteCannotLeakInjectedHistoricalExtraFields() {
        CmsTopic privateTopic = listed(); privateTopic.setClubMemberOnly(1);
        when(topics.selectCmsTopicDetailById(7L, 1L)).thenReturn(privateTopic);
        original.setExtraJson("{\"cardType\":\"route\",\"topicId\":7,\"name\":\"secret\",\"title\":\"secret\"}");
        unavailable(1);
        assertFalse(history(1).getExtraJson().contains("secret"));
        assertTrue(original.getExtraJson().contains("secret"));
    }
    @Test
    void malformedRouteJsonDoesNotLeakNameOrBreakList() {
        original.setExtraJson("{broken");
        assertEquals("路线已不可用", history(1).getContent());
        assertEquals("路线已不可用", service.listConversations(1L).get(0).get("lastMsgText"));
        assertEquals("{broken", original.getExtraJson());
    }
    @Test
    void blockedLatestRouteSummaryNeverEchoesConversationRowText() {
        when(topics.selectCmsTopicDetailById(7L, 1L)).thenReturn(listed());
        original.setStatus(ImMessage.STATUS_BLOCKED);
        assertEquals("路线已不可用", service.listConversations(1L).get(0).get("lastMsgText"));
        // 可见读模型在 SQL 层排除屏蔽消息；无可见消息时也不回落到会话行里的旧路线名。
        when(messages.selectVisiblePage(eq(100L), eq(1L), isNull(), eq(1))).thenReturn(Collections.emptyList());
        assertEquals("", service.listConversations(1L).get(0).get("lastMsgText"));
        verify(messages, never()).selectPage(anyLong(), any(), anyInt());
    }
    @Test
    void unrelatedCardsAndEmptyRouteSummaryKeepExistingShape() {
        original.setExtraJson("{\"cardType\":\"location\",\"name\":\"公园\"}"); original.setContent("[位置] 公园");
        assertEquals("[位置] 公园", history(1).getContent());
        when(messages.selectVisiblePage(eq(100L), anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());
        assertEquals("", service.listConversations(1L).get(0).get("lastMsgText"));
    }
    @Test
    void sendSuccessProjectsWithoutChangingInsertedContentOrDigest() {
        // F0412:发送按匿名口径校验公开可见;发送后回读按读者当前投影(此处模拟回读时已转仅成员可见)。
        CmsTopic privateTopic = listed(); privateTopic.setClubMemberOnly(1);
        when(topics.selectCmsTopicDetailById(eq(7L), isNull())).thenReturn(listed());
        when(topics.selectCmsTopicDetailById(7L, 1L)).thenReturn(privateTopic);
        when(messages.insertImMessage(any())).thenAnswer(inv -> { ((ImMessage) inv.getArgument(0)).setId(9L); return 1; });
        ImMessage result = service.send(1L, 100L, null, ImMessage.MSG_CARD, "send", "{\"cardType\":\"route\",\"topicId\":7}", "request0123456789");
        assertEquals("路线已不可用", result.getContent());
        org.mockito.ArgumentCaptor<ImMessage> inserted = org.mockito.ArgumentCaptor.forClass(ImMessage.class);
        verify(messages).insertImMessage(inserted.capture());
        assertEquals("[路线]", inserted.getValue().getContent(), "落库摘要不写主题名");
        assertEquals(inserted.getValue().getRequestHash(), result.getRequestHash());
        assertNotSame(inserted.getValue(), result);
    }
    @Test
    void idempotentReceiptUsesCurrentProjectionWithoutResending() {
        String extra = "{\"cardType\":\"route\",\"topicId\":7}";
        original.setRequestHash(CanonicalDigest.sha256Hex(JSON.toJSONString(Arrays.asList(ImMessage.MSG_CARD, "old", extra))));
        String digest = original.getRequestHash();
        when(messages.selectByClientMessageIdForUpdate(100L, 1L, "request0123456789")).thenReturn(original);
        ImMessage receipt = service.send(1L, 100L, null, ImMessage.MSG_CARD, "old", extra, "request0123456789");
        assertEquals("路线已不可用", receipt.getContent());
        assertEquals(digest, receipt.getRequestHash()); assertEquals(digest, original.getRequestHash());
        assertEquals(Long.valueOf(8), receipt.getId());
        verify(messages, never()).insertImMessage(any());
    }
}
