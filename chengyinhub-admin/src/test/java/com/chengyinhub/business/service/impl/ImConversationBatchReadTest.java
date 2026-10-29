package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper.ConversationSummary;
import com.chengyinhub.business.mapper.ImConversationMemberMapper.ClubConversationSummary;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImConversationBatchReadTest
{
    private final ImConversationMemberMapper members = mock(ImConversationMemberMapper.class);
    private final ImConversationMapper conversations = mock(ImConversationMapper.class);
    private final ImMessageMapper messages = mock(ImMessageMapper.class);
    private final IUmsMemberService profiles = mock(IUmsMemberService.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);

    @Test
    void sixtyFourDirectConversationsUseOneReadWithoutPerRowLookups()
    {
        List<ConversationSummary> input = new ArrayList<>();
        for (int i = 1; i <= 64; i++)
        {
            ConversationSummary row = row(i, (long) i, "2026-10-23 10:00:00.000000");
            row.setCounterpartyId(1000L + i);
            UmsMember profile = new UmsMember();
            profile.setId(1000L + i);
            profile.setName("name-" + i);
            profile.setNickname(i % 2 == 0 ? "nickname-" + i : null);
            profile.setAvatar("avatar-" + i);
            row.setCounterpartyProfile(profile);
            input.add(row);
        }
        when(members.selectMyConversationSummaries(7L)).thenReturn(input);

        List<Map<String, Object>> result = service().listConversations(7L);

        assertEquals(64, result.size());
        for (int i = 0; i < 64; i++)
        {
            long id = 64L - i;
            Map<String, Object> output = result.get(i);
            assertEquals(id, output.get("conversationId"));
            assertEquals("message-" + id, output.get("lastMsgText"));
            assertEquals((int) id, output.get("unread"));
            Map<?, ?> cp = (Map<?, ?>) output.get("counterparty");
            assertEquals(1000L + id, cp.get("id"));
            assertEquals((id % 2 == 0 ? "nickname-" : "name-") + id, cp.get("nickname"));
            assertEquals("avatar-" + id, cp.get("avatar"));
        }
        verify(members).selectMyConversationSummaries(7L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(conversations, messages, profiles, topics);
    }

    @Test
    void visibleOrderThenMessageIdDetermineOrderAndEmptyRowsKeepInputOrder()
    {
        ConversationSummary early = row(1, 99L, "2026-10-23 09:00:00.000000");
        ConversationSummary later = row(2, 20L, "2026-10-23 10:00:00.000000");
        ConversationSummary tied = row(3, 21L, "2026-10-23 10:00:00.000000");
        ConversationSummary emptyA = row(4, null, null);
        ConversationSummary emptyB = row(5, null, null);
        emptyA.getConversation().setLastMsgText("stale secret must not be used");
        when(members.selectMyConversationSummaries(7L)).thenReturn(
                Arrays.asList(emptyB, early, later, emptyA, tied));

        List<Map<String, Object>> result = service().listConversations(7L);

        assertEquals(Arrays.asList(3L, 2L, 1L, 5L, 4L), result.stream()
                .map(row -> (Long) row.get("conversationId")).toList());
        assertEquals("", result.get(4).get("lastMsgText"));
        assertNull(result.get(4).get("lastMsgType"));
        assertNull(result.get(4).get("lastMsgAt"));
        assertEquals(0, result.get(4).get("muted"));
        assertEquals(Map.of("id", 0, "nickname", "", "avatar", ""), result.get(4).get("counterparty"));
    }

    @Test
    void missingProfilePreservesOtherIdentityAndNullProfileFieldsAreNotRewritten()
    {
        ConversationSummary missing = row(1, null, null);
        missing.setCounterpartyId(88L);
        ConversationSummary nullFields = row(2, null, null);
        nullFields.setCounterpartyId(99L);
        UmsMember profile = new UmsMember(); profile.setId(99L);
        nullFields.setCounterpartyProfile(profile);
        when(members.selectMyConversationSummaries(7L)).thenReturn(Arrays.asList(missing, nullFields));

        List<Map<String, Object>> result = service().listConversations(7L);

        assertEquals(Map.of("id", 88L, "nickname", "", "avatar", ""), result.get(0).get("counterparty"));
        Map<?, ?> cp = (Map<?, ?>) result.get(1).get("counterparty");
        assertEquals(99L, cp.get("id"));
        assertTrue(cp.containsKey("nickname")); assertNull(cp.get("nickname"));
        assertTrue(cp.containsKey("avatar")); assertNull(cp.get("avatar"));
    }

    @Test
    void routeSummaryStillUsesCurrentViewerGateAndNeverMutatesStoredMessage()
    {
        ConversationSummary summary = row(1, 10L, "2026-10-23 10:00:00.000000");
        ImMessage card = summary.getLatest();
        card.setMsgType(ImMessage.MSG_CARD);
        card.setContent("[路线] historical private name");
        card.setExtraJson("{\"cardType\":\"route\",\"topicId\":17}");
        when(members.selectMyConversationSummaries(7L)).thenReturn(Collections.singletonList(summary));
        CmsTopic privateTopic = new CmsTopic();
        privateTopic.setStatus(1); privateTopic.setUserStatus(1); privateTopic.setDelFlag(0);
        privateTopic.setClubMemberOnly(1); privateTopic.setName("private current name");
        when(topics.selectCmsTopicDetailById(17L, 7L)).thenReturn(privateTopic);

        assertEquals("路线已不可用", service().listConversations(7L).get(0).get("lastMsgText"));
        assertEquals("[路线] historical private name", card.getContent());
        verify(topics).selectCmsTopicDetailById(17L, 7L);
        verifyNoInteractions(conversations, messages, profiles);
    }

    @Test
    void groupSummaryRemainsSubjectToCurrentMembershipGuard()
    {
        ConversationSummary summary = row(1, 10L, "2026-10-23 10:00:00.000000");
        summary.getConversation().setType(ImConversation.TYPE_GROUP);
        summary.getConversation().setBizKey("club_42");
        summary.getLatest().setContent("group secret");
        when(members.selectMyConversationSummaries(7L)).thenReturn(Collections.singletonList(summary));
        ClubMapper clubs = mock(ClubMapper.class);
        ClubMemberMapper clubMembers = mock(ClubMemberMapper.class);
        Club club = new Club();
        club.setId(42L); club.setMemberId(9L); club.setStatus(1L); club.setDelFlag(0);
        club.setName("current club"); club.setMemberCount(8);
        ClubConversationSummary projected = new ClubConversationSummary();
        org.springframework.beans.BeanUtils.copyProperties(club, projected);
        projected.setViewerMember(false);
        when(members.selectClubConversationSummaries(7L, List.of(42L))).thenReturn(List.of(projected));
        ImServiceImpl service = service();
        ReflectionTestUtils.setField(service, "clubMapper", clubs);
        ReflectionTestUtils.setField(service, "clubMemberMapper", clubMembers);

        assertTrue(service.listConversations(7L).isEmpty(), "批量取得摘要不能替代实时群资格");
        projected.setViewerMember(true);
        List<Map<String, Object>> allowed = service.listConversations(7L);
        assertEquals(1, allowed.size());
        assertEquals("group secret", allowed.get(0).get("lastMsgText"));
        assertEquals("current club", ((Map<?, ?>) allowed.get(0).get("counterparty")).get("nickname"));
        verify(members, times(2)).selectClubConversationSummaries(7L, List.of(42L));
        verifyNoInteractions(clubs, clubMembers, conversations, messages, profiles, topics);
    }

    @Test
    void unreadUsesOnlyScopedAggregateAndPreservesIntegerReturnContract()
    {
        when(members.sumInboxUnread(7L)).thenReturn(12L, null, 2147483648L);
        ImServiceImpl service = service();
        assertEquals(12, service.unreadTotal(7L));
        assertEquals(0, service.unreadTotal(7L));
        assertEquals(Integer.MIN_VALUE, service.unreadTotal(7L));
        verify(members, times(3)).sumInboxUnread(7L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(conversations, messages, profiles, topics);
    }

    @Test
    void emptySummaryReturnsEmptyWithoutAnyOtherReads()
    {
        when(members.selectMyConversationSummaries(7L)).thenReturn(Collections.emptyList());
        assertTrue(service().listConversations(7L).isEmpty());
        verify(members).selectMyConversationSummaries(7L);
        verifyNoMoreInteractions(members);
        verifyNoInteractions(conversations, messages, profiles, topics);
    }

    @Test
    void sixtyFourClubGroupsUseOneScopedBatchAndKeepOwnerOrActiveMemberPredicate()
    {
        List<ConversationSummary> input = new ArrayList<>();
        List<ClubConversationSummary> clubs = new ArrayList<>();
        List<Long> ids = new ArrayList<>(), expected = new ArrayList<>();
        for (int i=1;i<=64;i++)
        {
            ConversationSummary row = row(i,(long)i,"2026-10-23 10:00:00.000000");
            row.getConversation().setType(ImConversation.TYPE_GROUP);row.getConversation().setBizKey("club_"+(1000+i));
            input.add(row);ids.add(1000L+i);
            ClubConversationSummary club = new ClubConversationSummary();club.setId(1000L+i);
            club.setMemberId(i%3==0?7L:99L);club.setViewerMember(i%3==1);
            club.setStatus(1L);club.setDelFlag(0);club.setName("club-"+i);club.setMemberCount(i);clubs.add(club);
            if(i%3!=2)expected.add(0,(long)i);
        }
        when(members.selectMyConversationSummaries(7L)).thenReturn(input);
        when(members.selectClubConversationSummaries(7L,ids)).thenReturn(clubs);
        var result=service().listConversations(7L);
        assertEquals(expected,result.stream().map(r->(Long)r.get("conversationId")).toList());
        for(var row:result) {
            long id=(Long)row.get("conversationId");var cp=(Map<?,?>)row.get("counterparty");
            assertEquals("message-"+id,row.get("lastMsgText"));assertEquals("club-"+id,cp.get("nickname"));
            assertEquals(1000L+id,cp.get("clubId"));assertEquals("",cp.get("avatar"));assertEquals((int)id,cp.get("memberCount"));
        }
        verify(members).selectMyConversationSummaries(7L);verify(members).selectClubConversationSummaries(7L,ids);
        verifyNoMoreInteractions(members);verifyNoInteractions(conversations,messages,profiles,topics);
    }

    @Test
    void clubBatchKeepsNumericBizKeyParsingAndDatabaseFailureNeverFallsBack()
    {
        List<ConversationSummary> input=new ArrayList<>();int id=0;
        for(String key:List.of("club_+0042","club_0042","club_bad","club_9223372036854775808")) {
            var row=row(++id,(long)id,"2026-10-23 10:00:00.000000");
            row.getConversation().setType(ImConversation.TYPE_GROUP);row.getConversation().setBizKey(key);input.add(row);
        }
        ClubConversationSummary club=new ClubConversationSummary();club.setId(42L);club.setMemberId(7L);club.setStatus(1L);club.setDelFlag(0);
        when(members.selectMyConversationSummaries(7L)).thenReturn(input);
        when(members.selectClubConversationSummaries(7L,List.of(42L))).thenReturn(List.of(club));
        var result=service().listConversations(7L);assertEquals(List.of(2L,1L),result.stream().map(r->r.get("conversationId")).toList());
        var cp=(Map<?,?>)result.get(0).get("counterparty");assertTrue(cp.containsKey("nickname"));assertNull(cp.get("nickname"));
        assertTrue(cp.containsKey("memberCount"));assertNull(cp.get("memberCount"));assertEquals("",cp.get("avatar"));
        doThrow(new IllegalStateException("controlled club storage failure")).when(members).selectClubConversationSummaries(7L,List.of(42L));
        assertThrows(IllegalStateException.class,()->service().listConversations(7L));
        verifyNoInteractions(conversations,messages,profiles,topics);
    }

    private ImServiceImpl service()
    {
        ImServiceImpl service = new ImServiceImpl();
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "conversationMapper", conversations);
        ReflectionTestUtils.setField(service, "messageMapper", messages);
        ReflectionTestUtils.setField(service, "umsMemberService", profiles);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        return service;
    }

    private ConversationSummary row(long id, Long messageId, String visibleAt)
    {
        ConversationSummary row = new ConversationSummary();
        row.setConversationId(id); row.setMemberId(7L); row.setUnreadCount((int) id);
        ImConversation conversation = new ImConversation();
        conversation.setId(id); conversation.setType(ImConversation.TYPE_SINGLE); conversation.setStatus(0);
        row.setConversation(conversation);
        if (messageId != null)
        {
            ImMessage latest = new ImMessage();
            latest.setId(messageId); latest.setConversationId(id); latest.setVisibleOrderAt(visibleAt);
            latest.setMsgType(ImMessage.MSG_TEXT); latest.setStatus(ImMessage.STATUS_NORMAL);
            latest.setContent("message-" + id);
            row.setLatest(latest);
        }
        return row;
    }
}
