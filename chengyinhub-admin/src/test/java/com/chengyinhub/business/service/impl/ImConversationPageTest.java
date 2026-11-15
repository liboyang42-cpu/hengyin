package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper.ConversationSummary;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** R9 BE-17: the conversation list is keyset-paged and the legacy array route is capped. */
class ImConversationPageTest
{
    private final ImConversationMemberMapper members = mock(ImConversationMemberMapper.class);

    private ImServiceImpl service()
    {
        ImServiceImpl service = new ImServiceImpl();
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "conversationMapper", mock(ImConversationMapper.class));
        ReflectionTestUtils.setField(service, "messageMapper", mock(ImMessageMapper.class));
        ReflectionTestUtils.setField(service, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        return service;
    }

    private static ConversationSummary row(long id, Date lastMsgAt)
    {
        ConversationSummary row = new ConversationSummary();
        row.setConversationId(id); row.setMemberId(7L); row.setUnreadCount(0);
        ImConversation conversation = new ImConversation();
        conversation.setId(id); conversation.setType(ImConversation.TYPE_SINGLE); conversation.setStatus(0);
        conversation.setLastMsgAt(lastMsgAt);
        row.setConversation(conversation);
        ImMessage latest = new ImMessage();
        latest.setId(id * 10); latest.setConversationId(id); latest.setVisibleOrderAt("2026-11-15 10:00:00.000000");
        latest.setMsgType(ImMessage.MSG_TEXT); latest.setStatus(ImMessage.STATUS_NORMAL); latest.setContent("m" + id);
        row.setLatest(latest);
        return row;
    }

    @Test
    void firstPageAsksForOneExtraRowAndReturnsAnOpaqueCursor()
    {
        List<ConversationSummary> rows = new ArrayList<>();
        for (long id = 30; id >= 28; id--) rows.add(row(id, new Date(1_700_000_000_000L + id * 1000)));
        when(members.selectMyConversationSummaryPage(7L, null, null, 3)).thenReturn(rows);

        Map<String, Object> page = service().listConversationPage(7L, null, 2);

        assertEquals(2, ((List<?>) page.get("items")).size());
        assertEquals(Boolean.TRUE, page.get("hasMore"));
        assertEquals((1_700_000_000_000L + 29_000) + "_29", page.get("nextCursor"));
    }

    @Test
    void cursorIsPassedBackAsKeysetAndLastPageHasNoCursor()
    {
        Date at = new Date(1_700_000_029_000L);
        when(members.selectMyConversationSummaryPage(eq(7L), eq(at), eq(29L), eq(21))).thenReturn(List.of(row(28, null)));

        Map<String, Object> page = service().listConversationPage(7L, "1700000029000_29", null);

        assertEquals(1, ((List<?>) page.get("items")).size());
        assertEquals(Boolean.FALSE, page.get("hasMore"));
        assertNull(page.get("nextCursor"));
    }

    @Test
    void nullLastMessageTimeRoundTripsAndPageSizeIsCapped()
    {
        when(members.selectMyConversationSummaryPage(eq(7L), isNull(), eq(5L), eq(51))).thenReturn(List.of());
        Map<String, Object> page = service().listConversationPage(7L, "n_5", 500);
        assertEquals(Boolean.FALSE, page.get("hasMore"));
        verify(members).selectMyConversationSummaryPage(eq(7L), isNull(), eq(5L), eq(51));
    }

    @Test
    void malformedCursorIsABadRequestNotAServerError()
    {
        for (String bad : new String[] {"x", "1_", "abc_1", "1_-3", "1_2_3"})
        {
            ServiceException e = assertThrows(ServiceException.class, () -> service().listConversationPage(7L, bad, 20), bad);
            assertEquals(Integer.valueOf(400), e.getCode(), bad);
        }
        verifyNoInteractions(members);
    }

    @Test
    void legacyArrayRouteIsServedByTheCappedPageQuery()
    {
        ImConversationMemberMapper real = mock(ImConversationMemberMapper.class, CALLS_REAL_METHODS);
        doReturn(List.of()).when(real).selectMyConversationSummaryPage(anyLong(), any(), any(), anyInt());
        real.selectMyConversationSummaries(7L);
        verify(real).selectMyConversationSummaryPage(7L, null, null, ImConversationMemberMapper.LEGACY_LIST_CAP);
        assertEquals(200, ImConversationMemberMapper.LEGACY_LIST_CAP);
    }

    @SuppressWarnings("unused") private CmsTopic unused;
}
