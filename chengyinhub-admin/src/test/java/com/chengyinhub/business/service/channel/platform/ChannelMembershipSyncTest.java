package com.chengyinhub.business.service.channel.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** W7 x-channel-reach (SOC-2): guild members get conversation rows for every channel they can see. */
class ChannelMembershipSyncTest
{
    static final long OWNER = 11, MEMBER = 22, STRANGER = 33, CLUB = 42;

    ChannelPlatformMapper mapper;
    ChannelMembershipSync sync;

    @BeforeEach void setUp()
    {
        mapper = mock(ChannelPlatformMapper.class);
        when(mapper.selectClubGuild(CLUB)).thenReturn(Map.of("id", CLUB, "ownerId", OWNER, "status", 1, "delFlag", 0));
        when(mapper.countClubMember(CLUB, MEMBER)).thenReturn(1);
        when(mapper.selectMemberRoles(anyInt(), anyLong(), anyLong())).thenReturn(List.of());
        when(mapper.selectOverwrites(anyLong())).thenReturn(List.of());
        sync = new ChannelMembershipSync(mapper, new ChannelGuildAccess(mapper));
    }

    static ChannelRow row(long id, int kind, int status)
    {
        ChannelRow r = ChannelPlatformServiceTest.row(id, 0, kind, "c" + id);
        r.setStatus(status);
        return r;
    }

    static Map<String, Object> privateOverwrite(long channelId)
    {
        Map<String, Object> o = new HashMap<>();
        o.put("channelId", channelId); o.put("targetType", 1); o.put("targetId", 0L); o.put("allowBits", 0L);
        o.put("denyBits", ChannelCapability.VIEW_CHANNEL.mask());
        return o;
    }

    static Map<String, Object> member(long id) { return Map.of("memberId", id); }

    @Test void joiningEnrolsVisibleLiveChannelsOnly()
    {
        ChannelRow category = row(100, 0, 0), chat = row(101, 1, 0), notice = row(102, 2, 0),
                staff = row(103, 1, 0), archived = row(104, 1, 1);
        when(mapper.selectGuildChannels(1, CLUB, false)).thenReturn(List.of(category, chat, notice, staff, archived));
        when(mapper.selectOverwrites(103L)).thenReturn(List.of(privateOverwrite(103)));
        when(mapper.insertIgnoreMemberChannels(eq(MEMBER), anyList())).thenReturn(2);

        assertEquals(2, sync.enrollMember(1, CLUB, MEMBER));
        verify(mapper).insertIgnoreMemberChannels(MEMBER, List.of(101L, 102L));
    }

    @Test void strangerIsNeverEnrolled()
    {
        assertEquals(0, sync.enrollMember(1, CLUB, STRANGER));
        verify(mapper, never()).insertIgnoreMemberChannels(anyLong(), anyList());
    }

    @Test void newChannelEnrolsEveryMemberInPagesReadUpToTheLatestMessage()
    {
        ChannelRow chat = row(101, 1, 0);
        List<Map<String, Object>> first = new ArrayList<>();
        for (long m = 1; m <= ChannelMembershipSync.MEMBER_PAGE; m++) first.add(member(m));
        when(mapper.selectGuildMembers(1, CLUB, 0L, ChannelMembershipSync.MEMBER_PAGE)).thenReturn(first);
        when(mapper.selectGuildMembers(1, CLUB, (long) ChannelMembershipSync.MEMBER_PAGE, ChannelMembershipSync.MEMBER_PAGE))
                .thenReturn(List.of(member(9001)));
        when(mapper.selectLatestMessageId(101L)).thenReturn(777L);
        when(mapper.insertIgnoreMembers(eq(101L), anyList(), eq(777L))).thenAnswer(i -> ((List<?>) i.getArgument(1)).size());

        assertEquals(ChannelMembershipSync.MEMBER_PAGE + 1, sync.enrollChannel(chat));
        verify(mapper, times(2)).insertIgnoreMembers(eq(101L), anyList(), eq(777L));
        verify(mapper).insertIgnoreMembers(101L, List.of(9001L), 777L);
    }

    @Test void privateChannelChecksEachMember()
    {
        ChannelRow staff = row(103, 1, 0);
        when(mapper.selectOverwrites(103L)).thenReturn(List.of(privateOverwrite(103)));
        when(mapper.selectGuildMembers(1, CLUB, 0L, ChannelMembershipSync.MEMBER_PAGE)).thenReturn(List.of(member(OWNER), member(MEMBER)));
        when(mapper.insertIgnoreMembers(anyLong(), anyList(), anyLong())).thenReturn(1);

        sync.enrollChannel(staff);
        verify(mapper).insertIgnoreMembers(103L, List.of(OWNER), 0L); // the owner bypasses; the member is denied VIEW
    }

    @Test void categoriesAndArchivedChannelsAreSkipped()
    {
        assertEquals(0, sync.enrollChannel(row(100, 0, 0)));
        assertEquals(0, sync.enrollChannel(row(104, 1, 1)));
        verifyNoMoreInteractionsOnInserts();
    }

    private void verifyNoMoreInteractionsOnInserts()
    {
        verify(mapper, never()).insertIgnoreMembers(anyLong(), anyList(), anyLong());
        verify(mapper, never()).selectGuildMembers(anyInt(), anyLong(), anyLong(), anyInt());
    }

    @Test void leavingDropsTheGuildRowsAndNeverThrows()
    {
        sync.memberLeft(1, CLUB, MEMBER);
        verify(mapper).deleteGuildMemberships(1, CLUB, MEMBER);
        when(mapper.deleteGuildMemberships(anyInt(), anyLong(), anyLong())).thenThrow(new RuntimeException("db"));
        assertDoesNotThrow(() -> sync.memberLeft(1, CLUB, MEMBER));
    }

    @Test void joinHookOutsideATransactionRunsNowAndSwallowsFailures()
    {
        when(mapper.selectGuildChannels(1, CLUB, false)).thenThrow(new RuntimeException("db"));
        assertDoesNotThrow(() -> sync.memberJoinedAfterCommit(1, CLUB, MEMBER));
        verify(mapper).selectGuildChannels(1, CLUB, false);
    }

    @Test void backfillWalksEveryLiveChannelByKeysetAndSkipsFailures()
    {
        ChannelRow a = row(101, 1, 0), b = row(102, 1, 0);
        when(mapper.selectLiveChannelsAfter(0L, 2)).thenReturn(List.of(a, b));
        when(mapper.selectLiveChannelsAfter(102L, 2)).thenReturn(List.of(row(103, 1, 0)));
        when(mapper.selectGuildMembers(eq(1), eq(CLUB), eq(0L), anyInt())).thenReturn(List.of(member(MEMBER)));
        when(mapper.selectLatestMessageId(101L)).thenThrow(new RuntimeException("db"));
        when(mapper.insertIgnoreMembers(anyLong(), anyList(), anyLong())).thenReturn(1);

        assertEquals(2, sync.backfill(2));
        verify(mapper).selectLiveChannelsAfter(102L, 2);
        verify(mapper, never()).insertIgnoreMembers(eq(101L), anyList(), anyLong());
    }
}
