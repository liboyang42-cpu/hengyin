package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** club-reach: a club opens with 闲聊 / 公告 / 活动; teams keep the single 综合 default; backfill is idempotent. */
class ClubChannelDefaultsTest
{
    static final long OWNER = 11, CLUB = 42, TEAM = 77;

    ChannelPlatformMapper mapper;
    ChannelPlatformService service;
    List<ChannelRow> inserted;

    @BeforeEach void setUp()
    {
        mapper = mock(ChannelPlatformMapper.class);
        AtomicLong ids = new AtomicLong(5000);
        inserted = new ArrayList<>();
        when(mapper.insertConversation(anyMap())).thenAnswer(i -> {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) i.getArgument(0);
            m.put("id", ids.incrementAndGet());
            return 1;
        });
        when(mapper.insertChannel(any())).thenAnswer(i -> { inserted.add(i.getArgument(0)); return 1; });
        when(mapper.selectChannel(anyLong())).thenAnswer(i -> {
            long id = i.getArgument(0);
            return inserted.stream().filter(r -> r.getId() == id).findFirst().orElse(null);
        });
        when(mapper.nextPosition(anyInt(), anyLong(), anyLong())).thenAnswer(i -> {
            long parent = i.getArgument(2);
            return (int) inserted.stream().filter(r -> r.getParentId() == parent).count();
        });
        service = new ChannelPlatformService(mapper, new ChannelGuildAccess(mapper));
    }

    @Test void clubDefaultsAreChatAnnouncementAndEvents()
    {
        when(mapper.selectGuildChannels(1, CLUB, true)).thenReturn(List.of());
        assertTrue(service.ensureClubDefaults(CLUB, OWNER));
        assertEquals(4, inserted.size(), "one category and three channels");
        ChannelRow category = inserted.get(0);
        assertEquals(0, category.getKind());
        ChannelRow chat = inserted.get(1), notice = inserted.get(2), events = inserted.get(3);
        assertEquals("闲聊", chat.getName());
        assertEquals(1, chat.getKind());
        assertEquals(1, chat.getIsDefault(), "the chat channel is the default (text, position 0)");
        assertEquals(0, chat.getPosition());
        assertEquals("公告", notice.getName());
        assertEquals(2, notice.getKind(), "announcement kind, read-only in the app for members");
        assertEquals(0, notice.getIsDefault());
        assertEquals("活动", events.getName());
        assertEquals(1, events.getKind());
        assertTrue(notice.getPosition() >= 1 && events.getPosition() > notice.getPosition());
        for (ChannelRow r : inserted.subList(1, 4))
        {
            assertEquals(category.getId(), r.getParentId());
            assertEquals(1, r.getGuildKind());
            assertEquals(CLUB, r.getGuildId());
        }
        verify(mapper, times(4)).ensureConversationMember(anyLong(), eq(OWNER));
    }

    @Test void clubWithAnyChannelRowIsLeftAlone()
    {
        ChannelRow existing = new ChannelRow();
        existing.setId(1L);
        when(mapper.selectGuildChannels(1, CLUB, true)).thenReturn(List.of(existing));
        assertFalse(service.ensureClubDefaults(CLUB, OWNER));
        verify(mapper, never()).insertChannel(any());
        assertFalse(service.ensureClubDefaults(0, OWNER));
        assertFalse(service.ensureClubDefaults(CLUB, 0));
    }

    @Test void teamKeepsSingleGeneralDefault()
    {
        service.provisionDefaults(OWNER, ChannelGuildAccess.GUILD_TEAM, TEAM);
        assertEquals(2, inserted.size());
        assertEquals("综合", inserted.get(1).getName());
        assertEquals(1, inserted.get(1).getIsDefault());
    }

    @Test void backfillSeedsOnlyClubsWithoutChannelsAndSurvivesOneFailure()
    {
        Map<String, Object> a = new HashMap<>(); a.put("id", 101L); a.put("ownerId", 1L);
        Map<String, Object> b = new HashMap<>(); b.put("id", 102L); b.put("ownerId", 2L);
        Map<String, Object> c = new HashMap<>(); c.put("id", 103L); c.put("ownerId", 3L);
        when(mapper.selectOpenClubsWithoutChannels(anyInt())).thenReturn(List.of(a, b, c));
        when(mapper.selectGuildChannels(eq(1), anyLong(), eq(true))).thenReturn(List.of());
        when(mapper.selectGuildChannels(1, 102L, true)).thenThrow(new IllegalStateException("db"));
        assertEquals(2, service.backfillClubDefaults(10_000));
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(mapper).selectOpenClubsWithoutChannels(limit.capture());
        assertEquals(ChannelPlatformService.BACKFILL_MAX_BATCH, limit.getValue(), "batch is capped");
        assertEquals(8, inserted.size());
    }
}
