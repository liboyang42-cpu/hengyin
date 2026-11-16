package com.chengyinhub.business.clubinvite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chengyinhub.business.clubinvite.mapper.ClubInviteMapper;
import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.channel.platform.ChannelGuildAccess;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.channel.platform.ChannelRow;

/** W7 SOC-15: invite links: who may make them, expiry, use limit, revoke, target channel, join policy kept. */
class ClubInviteServiceTest
{
    static final long OWNER = 1L, ADMIN = 2L, MEMBER = 3L, OUTSIDER = 4L;
    static final long CLUB = 42L, CHANNEL = 50L, INVITE = 9L;
    static final String CODE = "ABCDEFGH23";

    ClubInviteMapper mapper;
    ChannelPlatformMapper channels;
    ChannelGuildAccess access;
    ClubInviteService service;
    ChannelRow channel;
    Map<String, Object> stored;

    @BeforeEach
    void setUp()
    {
        mapper = mock(ClubInviteMapper.class);
        channels = mock(ChannelPlatformMapper.class);
        access = mock(ChannelGuildAccess.class);
        service = new ClubInviteService(mapper, channels, access, false);
        when(access.standing(ChannelGuildAccess.GUILD_CLUB, CLUB, OWNER)).thenReturn(GuildStanding.OWNER);
        when(access.standing(ChannelGuildAccess.GUILD_CLUB, CLUB, ADMIN)).thenReturn(GuildStanding.ADMIN);
        when(access.standing(ChannelGuildAccess.GUILD_CLUB, CLUB, MEMBER)).thenReturn(GuildStanding.MEMBER);
        when(access.standing(ChannelGuildAccess.GUILD_CLUB, CLUB, OUTSIDER)).thenReturn(GuildStanding.NONE);

        channel = new ChannelRow();
        channel.setId(CHANNEL);
        channel.setGuildKind(ChannelGuildAccess.GUILD_CLUB);
        channel.setGuildId(CLUB);
        channel.setKind(1);
        channel.setStatus(0);
        channel.setName("活动");
        when(channels.selectChannel(CHANNEL)).thenReturn(channel);
        when(access.effective(channel, ADMIN)).thenReturn(ChannelPermissionCalculator.ADMIN_BASE);
        when(access.effective(channel, MEMBER)).thenReturn(ChannelPermissionCalculator.MEMBER_BASE);

        doAnswer(inv -> {
            Map<String, Object> row = new HashMap<>(inv.getArgument(0));
            row.put("id", INVITE);
            row.put("uses", 0);
            row.put("createTime", new Date());
            stored = row;
            when(mapper.selectByCode(row.get("code").toString())).thenReturn(row);
            return 1;
        }).when(mapper).insertInvite(anyMap());
        when(mapper.selectClubCard(CLUB)).thenReturn(Map.of("id", CLUB, "name", "夜跑团", "status", 1, "delFlag", 0));
    }

    Map<String, Object> invite(Integer maxUses, int uses, Object expiresAt, Object revokedAt)
    {
        Map<String, Object> row = new HashMap<>();
        row.put("id", INVITE);
        row.put("clubId", CLUB);
        row.put("channelId", CHANNEL);
        row.put("code", CODE);
        row.put("creatorMemberId", MEMBER);
        row.put("maxUses", maxUses);
        row.put("uses", uses);
        row.put("expiresAt", expiresAt);
        row.put("revokedAt", revokedAt);
        when(mapper.selectByCode(CODE)).thenReturn(row);
        return row;
    }

    static String code(Runnable r)
    {
        return assertThrows(ChannelRejection.class, r::run).getErrorCode();
    }

    @Test
    void ownersAndAdminsMakeInvitesWithAnExpiryAndUseLimit()
    {
        long before = System.currentTimeMillis();
        Map<String, Object> v = service.create(ADMIN, Map.of("clubId", "42", "expiresInDays", 7, "maxUses", 10));
        assertEquals("VALID", v.get("state"));
        assertEquals(10, stored.get("maxUses"));
        long expires = ((Date) stored.get("expiresAt")).getTime();
        assertTrue(expires >= before + 7 * ClubInviteService.DAY_MS && expires <= System.currentTimeMillis() + 7 * ClubInviteService.DAY_MS);
        assertTrue(ClubInviteService.validCode(stored.get("code").toString()));
        assertEquals("/pages/club/detail/index?id=42&invite=" + stored.get("code"), v.get("path"));

        service.create(OWNER, Map.of("clubId", 42, "expiresInDays", 0, "maxUses", 0));
        assertNull(stored.get("expiresAt"), "never expires");
        assertNull(stored.get("maxUses"), "no use limit");
    }

    @Test
    void membersAndOutsidersMakeNoInvitesWhileTheOwnerHasNotDecided()
    {
        assertEquals("INVITE_FORBIDDEN", code(() -> service.create(MEMBER, Map.of("clubId", 42))));
        assertEquals("INVITE_FORBIDDEN", code(() -> service.create(OUTSIDER, Map.of("clubId", 42))));
        ClubInviteService open = new ClubInviteService(mapper, channels, access, true);
        assertEquals("VALID", open.create(MEMBER, Map.of("clubId", 42)).get("state"));
    }

    @Test
    void onlyOfferedChoicesAreAccepted()
    {
        assertEquals("INVITE_EXPIRY_INVALID", code(() -> service.create(OWNER, Map.of("clubId", 42, "expiresInDays", 30))));
        assertEquals("INVITE_USES_INVALID", code(() -> service.create(OWNER, Map.of("clubId", 42, "maxUses", 3))));
        assertEquals("INVITE_USES_INVALID", code(() -> service.create(OWNER, Map.of("clubId", 42, "maxUses", "x"))));
    }

    @Test
    void aChannelTargetMustBeALiveChannelOfTheClubTheCreatorManages()
    {
        assertEquals(String.valueOf(CHANNEL), service.create(ADMIN, Map.of("clubId", 42, "channelId", "50")).get("channel_id"));
        ClubInviteService open = new ClubInviteService(mapper, channels, access, true);
        assertEquals("INVITE_FORBIDDEN", code(() -> open.create(MEMBER, Map.of("clubId", 42, "channelId", 50))));
        channel.setGuildId(77L);
        assertEquals("INVITE_CHANNEL_INVALID", code(() -> service.create(ADMIN, Map.of("clubId", 42, "channelId", 50))));
        channel.setGuildId(CLUB);
        channel.setStatus(1);
        assertEquals("INVITE_CHANNEL_INVALID", code(() -> service.create(ADMIN, Map.of("clubId", 42, "channelId", 50))));
    }

    @Test
    void stateReadsRevokedExpiredFullAndValid()
    {
        Date now = new Date();
        assertEquals("VALID", ClubInviteService.state(invite(null, 99, null, null), now));
        assertEquals("FULL", ClubInviteService.state(invite(10, 10, null, null), now));
        assertEquals("EXPIRED", ClubInviteService.state(invite(null, 0, new Date(now.getTime() - 1), null), now));
        assertEquals("EXPIRED", ClubInviteService.state(invite(null, 0, LocalDateTime.now().minusMinutes(1), null), now),
                "a LocalDateTime from the driver reads the same");
        assertEquals("VALID", ClubInviteService.state(invite(null, 0, LocalDateTime.now().plusDays(1), null), now));
        assertEquals("REVOKED", ClubInviteService.state(invite(null, 0, null, new Date()), now));
    }

    @Test
    void acceptTakesOneUseAndRefusesExpiredFullOrRevoked()
    {
        invite(1, 0, null, null);
        when(mapper.takeUse(eq(INVITE), any())).thenReturn(1);
        ClubInviteService.Ticket t = service.take(OUTSIDER, CODE);
        assertFalse(t.alreadyMember());
        assertEquals(CHANNEL, t.channelId());
        verify(mapper).takeUse(eq(INVITE), any());

        invite(1, 1, null, null);
        assertEquals("INVITE_FULL", code(() -> service.take(OUTSIDER, CODE)));
        invite(null, 0, new Date(System.currentTimeMillis() - 1000), null);
        assertEquals("INVITE_EXPIRED", code(() -> service.take(OUTSIDER, CODE)));
        invite(null, 0, null, new Date());
        assertEquals("INVITE_REVOKED", code(() -> service.take(OUTSIDER, CODE)));
        assertEquals("INVITE_NOT_FOUND", code(() -> service.take(OUTSIDER, "abc")));
    }

    @Test
    void losingTheRaceForTheLastUseIsRefusedAsUsedUp()
    {
        Map<String, Object> row = invite(1, 0, null, null);
        when(mapper.takeUse(eq(INVITE), any())).thenAnswer(inv -> {
            row.put("uses", 1);
            return 0;
        });
        assertEquals("INVITE_FULL", code(() -> service.take(OUTSIDER, CODE)));
    }

    @Test
    void aMemberAlreadyInTheClubTakesNoUseAndAFailedJoinGivesItBack()
    {
        invite(1, 0, null, null);
        ClubInviteService.Ticket member = service.take(MEMBER, CODE);
        assertTrue(member.alreadyMember());
        verify(mapper, never()).takeUse(anyLong(), any());
        service.giveBack(member);
        verify(mapper, never()).returnUse(anyLong());

        service.giveBack(new ClubInviteService.Ticket(INVITE, CLUB, null, false));
        verify(mapper).returnUse(INVITE);
    }

    @Test
    void landingShowsTheClubTheChannelAndWhetherTheInviteWorks()
    {
        invite(10, 3, null, null);
        Map<String, Object> v = service.landing(OUTSIDER, CODE);
        assertEquals("夜跑团", v.get("club_name"));
        assertEquals("活动", v.get("channel_name"));
        assertEquals("VALID", v.get("state"));
        assertEquals(Boolean.FALSE, v.get("already_member"));
        assertFalse(v.containsKey("creator_member_id"), "the landing does not show who made the link");
        channel.setStatus(1);
        assertNull(service.landing(OUTSIDER, CODE).get("channel_id"), "an archived target falls back to the club");
    }

    @Test
    void creatorsAndManagersRevokeOthersCannot()
    {
        invite(null, 0, null, null);
        service.revoke(MEMBER, CODE);
        service.revoke(ADMIN, CODE);
        verify(mapper, org.mockito.Mockito.times(2)).revoke(INVITE);
        invite(null, 0, null, null).put("creatorMemberId", OWNER);
        assertEquals("INVITE_FORBIDDEN", code(() -> service.revoke(MEMBER, CODE)));
    }

    @Test
    void membersSeeOnlyTheirOwnInvitesManagersSeeAll()
    {
        Map<String, Object> mine = invite(null, 0, null, null);
        Map<String, Object> theirs = new HashMap<>(mine);
        theirs.put("creatorMemberId", ADMIN);
        when(mapper.selectByClub(eq(CLUB), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(mine, theirs));
        assertEquals(1, service.list(MEMBER, CLUB).size());
        assertEquals(2, service.list(ADMIN, CLUB).size());
        assertEquals("GUILD_FORBIDDEN", code(() -> service.list(OUTSIDER, CLUB)));
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).selectByClub(eq(CLUB), limit.capture());
        assertEquals(ClubInviteService.LIST_LIMIT, limit.getValue());
    }
}
