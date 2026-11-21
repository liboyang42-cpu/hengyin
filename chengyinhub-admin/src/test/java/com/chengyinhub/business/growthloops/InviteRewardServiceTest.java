package com.chengyinhub.business.growthloops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.growthloops.InviteRewardPolicy.Outcome;
import com.chengyinhub.business.mapper.growthloops.GrowthLoopsMapper;
import com.chengyinhub.business.push.MemberNotice;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPushFanoutService;
import com.chengyinhub.system.service.ISysConfigService;

class InviteRewardServiceTest
{
    private GrowthLoopsMapper mapper;
    private IPlayerProgressionService progression;
    private IPushFanoutService push;
    private InviteCityCoinHook hook;
    private final Map<String, String> config = new HashMap<>();
    private InviteRewardService service;
    private final Date now = new Date(1_763_000_000_000L);

    @BeforeEach
    void setUp()
    {
        mapper = mock(GrowthLoopsMapper.class);
        progression = mock(IPlayerProgressionService.class);
        push = mock(IPushFanoutService.class);
        hook = mock(InviteCityCoinHook.class);
        ISysConfigService cfg = mock(ISysConfigService.class);
        when(cfg.selectConfigByKey(anyString())).thenAnswer(i -> config.get(i.getArgument(0)));
        service = new InviteRewardService(mapper, new GrowthLoopsSettings(cfg), progression, push, List.of(hook));
        when(mapper.selectInvitee(20L)).thenReturn(member("7", "+86 138-0000-1111"));
        when(mapper.selectInvitee(7L)).thenReturn(member(null, "13900002222"));
        when(mapper.selectSessionTrust(20L, 5L)).thenReturn(Map.of("clean", 4L, "flagged", 0L));
        when(mapper.insertInviteReward(anyLong(), anyLong(), anyString(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.selectInviteeDeviceTokens(20L)).thenReturn(List.of("ABCDEF01"));
        when(mapper.countGrantedForInviter(7L)).thenReturn(1);
        PlayerBadge badge = new PlayerBadge();
        badge.setBadgeName("带朋友上路");
        when(progression.onInviteRewarded(7L, 1)).thenReturn(List.of(badge));
    }

    private static Map<String, Object> member(String inviter, String phone)
    {
        Map<String, Object> m = new HashMap<>();
        m.put("inviteUserId", inviter);
        m.put("phone", phone);
        m.put("status", 1);
        m.put("delFlag", 0);
        return m;
    }

    @Test
    void grantsBadgeCallsCityCoinHookAndTellsInviter()
    {
        InviteRewardPolicy.Decision d = service.evaluate(20L, 5L, 800, 0, now);
        assertEquals(Outcome.GRANTED, d.outcome());
        verify(mapper).insertInviteReward(eq(20L), eq(7L), eq("GRANTED"), isNull(), eq(5L),
                eq(InviteRewardPolicy.phoneHash("13800001111")), eq(now));
        verify(mapper).insertInviteDevice(InviteRewardPolicy.deviceHash("abcdef01"), 20L, now);
        verify(progression).onInviteRewarded(7L, 1);
        verify(hook).grantInviteReward(7L, 20L);
        ArgumentCaptor<MemberNotice> notice = ArgumentCaptor.forClass(MemberNotice.class);
        verify(push).notifyMemberAfterCommit(eq(7L), notice.capture());
        assertTrue(notice.getValue().message(false).body().contains("带朋友上路"));
    }

    @Test
    void notInvitedOrSelfInvitedOrAlreadyDoneIsIgnored()
    {
        when(mapper.selectInvitee(21L)).thenReturn(member("21", "13800003333"));
        assertEquals(Outcome.NONE, service.evaluate(21L, 5L, 800, 0, now).outcome());
        when(mapper.selectInvitee(22L)).thenReturn(member(" ", "13800003333"));
        assertEquals(Outcome.NONE, service.evaluate(22L, 5L, 800, 0, now).outcome());
        when(mapper.selectInviteReward(20L)).thenReturn(Map.of("status", "GRANTED"));
        assertEquals(Outcome.NONE, service.evaluate(20L, 5L, 800, 0, now).outcome());
        verify(mapper, never()).insertInviteReward(anyLong(), anyLong(), anyString(), any(), any(), any(), any());
    }

    @Test
    void shortWalkUntrustedLocationOrNoPhoneWaitsWithoutWriting()
    {
        assertEquals("WALK_TOO_SHORT", service.evaluate(20L, 5L, 100, 2, now).reason());
        when(mapper.selectSessionTrust(20L, 5L)).thenReturn(Map.of("clean", 4L, "flagged", 1L));
        assertEquals("LOCATION_NOT_TRUSTED", service.evaluate(20L, 5L, 800, 0, now).reason());
        when(mapper.selectSessionTrust(20L, 5L)).thenReturn(Map.of("clean", 0L, "flagged", 0L));
        assertEquals("LOCATION_NOT_TRUSTED", service.evaluate(20L, 5L, 800, 0, now).reason());
        when(mapper.selectSessionTrust(20L, 5L)).thenThrow(new RuntimeException("table missing"));
        assertEquals(Outcome.WAIT, service.evaluate(20L, 5L, 800, 0, now).outcome());
        reset(mapper);
        when(mapper.selectInvitee(20L)).thenReturn(member("7", null));
        when(mapper.selectSessionTrust(20L, 5L)).thenReturn(Map.of("clean", 4L, "flagged", 0L));
        assertEquals("NO_PHONE", service.evaluate(20L, 5L, 800, 0, now).reason());
        verify(mapper, never()).insertInviteReward(anyLong(), anyLong(), anyString(), any(), any(), any(), any());
    }

    @Test
    void duplicatePhoneDeviceSamePhoneAndMonthlyCapAreRefusedAndRecorded()
    {
        when(mapper.countGrantedByPhoneHash(anyString())).thenReturn(1);
        assertEquals("DUPLICATE_PHONE", service.evaluate(20L, 5L, 800, 0, now).reason());
        when(mapper.countGrantedByPhoneHash(anyString())).thenReturn(0);
        when(mapper.countGrantedByDeviceHashes(anyList())).thenReturn(1);
        assertEquals("DUPLICATE_DEVICE", service.evaluate(20L, 5L, 800, 0, now).reason());
        when(mapper.countGrantedByDeviceHashes(anyList())).thenReturn(0);
        when(mapper.countGrantedForInviterSince(eq(7L), any())).thenReturn(10);
        assertEquals("INVITER_MONTHLY_CAP", service.evaluate(20L, 5L, 800, 0, now).reason());
        when(mapper.selectInvitee(7L)).thenReturn(member(null, "13800001111"));
        assertEquals("SAME_PHONE", service.evaluate(20L, 5L, 800, 0, now).reason());
        verify(mapper, times(4)).insertInviteReward(eq(20L), eq(7L), eq("REFUSED"), anyString(), eq(5L), anyString(), eq(now));
        verifyNoInteractions(progression, hook);
        verify(push, never()).notifyMemberAfterCommit(anyLong(), any());
    }

    @Test
    void disabledSwitchStopsEverything()
    {
        config.put("growth.invite.rewardEnabled", "false");
        assertEquals(Outcome.NONE, service.evaluate(20L, 5L, 800, 0, now).outcome());
        verifyNoInteractions(mapper);
    }

    @Test
    void phoneHashNormalisesCountryCodeAndPunctuation()
    {
        assertEquals(InviteRewardPolicy.phoneHash("13800001111"), InviteRewardPolicy.phoneHash("+86 138 0000 1111"));
        assertNull(InviteRewardPolicy.phoneHash(""));
        assertNull(InviteRewardPolicy.inviterOf("abc", 3L));
        assertEquals(9L, InviteRewardPolicy.inviterOf(" 9 ", 3L));
    }
}
