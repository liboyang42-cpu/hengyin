package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.domain.OfficialEventRewardIssuance;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.OfficialEventV2Mapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C-30:V2 结算券/集体券被平台手动失效 → 跳过发券,经验/徽章照常投递,不抛错回滚保存点。 */
class OfficialEventV2SettlementHelperDisabledCouponTest {

    @Test
    void disabledSettleAndCollectiveCouponsAreSkippedWhileXpAndBadgeStillIssue() {
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        MemberCollectibleMapper collectibles = mock(MemberCollectibleMapper.class);
        ISmsCouponHistoryService coupons = mock(ISmsCouponHistoryService.class);
        IGrowthService growth = mock(IGrowthService.class);
        AlarmService alarms = mock(AlarmService.class);
        OfficialEventV2SettlementHelper helper = new OfficialEventV2SettlementHelper();
        ReflectionTestUtils.setField(helper, "v2Mapper", v2Mapper);
        ReflectionTestUtils.setField(helper, "collectibleMapper", collectibles);
        ReflectionTestUtils.setField(helper, "couponHistoryService", coupons);
        ReflectionTestUtils.setField(helper, "growthService", growth);
        ReflectionTestUtils.setField(helper, "alarmService", alarms);

        OfficialEventRewardIssuance issuance = new OfficialEventRewardIssuance();
        issuance.setId(900L);
        issuance.setEventId(1L);
        issuance.setMemberId(101L);
        when(v2Mapper.claimPendingRewardIssuance(900L)).thenReturn(1);
        when(coupons.isCouponNotIssuable(77L)).thenReturn(true);
        when(coupons.isCouponNotIssuable(78L)).thenReturn(true);

        assertTrue(helper.issue(issuance, 77L, 50, true, 78L, true));

        verify(coupons, never()).grantCouponById(anyLong(), anyLong());
        verify(alarms, never()).alarm(anyString(), anyString());
        verify(growth).addExp(eq(101L), eq(50), anyString());
        verify(collectibles).insertMemberCollectible(any(MemberCollectible.class));
    }
}
