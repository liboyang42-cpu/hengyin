package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedemptionRewardClawbackIdentityTest {
    @Test
    void unknownReceiptNeverVoidsOrDeletesTheGrant() {
        CmsTopicCompletionCouponRewardMapper grants = mock(CmsTopicCompletionCouponRewardMapper.class);
        SmsCouponHistoryMapper histories = mock(SmsCouponHistoryMapper.class);
        when(histories.selectCompletionRewardHistoryId(501L, 700L)).thenReturn(null);
        Map<String, Object> result = reverse(grants, histories);
        assertEquals("UNKNOWN_IDENTITY_NOT_RECOVERED", result.get("result"));
        verify(histories, never()).selectSmsCouponHistoryByIdForUpdate(anyLong());
        assertNoAssetWrite(grants, histories);
    }

    @Test
    void lockingReadRechecksIdentityChangedSinceTheReceiptLookup() {
        for (String mismatch : new String[] {"missing", "member", "coupon", "deleted"}) {
            CmsTopicCompletionCouponRewardMapper grants = mock(CmsTopicCompletionCouponRewardMapper.class);
            SmsCouponHistoryMapper histories = mock(SmsCouponHistoryMapper.class);
            when(histories.selectCompletionRewardHistoryId(501L, 700L)).thenReturn(41L);
            SmsCouponHistory row = original();
            if ("member".equals(mismatch)) row.setMemberId(502L);
            if ("coupon".equals(mismatch)) row.setCouponId(31L);
            if ("deleted".equals(mismatch)) row.setDelFlag(1);
            when(histories.selectSmsCouponHistoryByIdForUpdate(41L))
                    .thenReturn("missing".equals(mismatch) ? null : row);
            assertEquals("UNKNOWN_IDENTITY_NOT_RECOVERED", reverse(grants, histories).get("result"));
            assertNoAssetWrite(grants, histories);
        }
    }

    @Test
    void usedOriginalDoesNotDeleteGrantOrLookForAReplacement() {
        CmsTopicCompletionCouponRewardMapper grants = mock(CmsTopicCompletionCouponRewardMapper.class);
        SmsCouponHistoryMapper histories = mock(SmsCouponHistoryMapper.class);
        SmsCouponHistory row = original(); row.setUseStatus(1);
        when(histories.selectCompletionRewardHistoryId(501L, 700L)).thenReturn(41L);
        when(histories.selectSmsCouponHistoryByIdForUpdate(41L)).thenReturn(row);
        assertEquals("USED_NOT_RECOVERED", reverse(grants, histories).get("result"));
        verify(grants, never()).deleteGrant(anyLong(), anyLong());
        verify(histories, never()).selectSmsCouponHistoryByIdForUpdate(43L);
    }

    private SmsCouponHistory original() {
        SmsCouponHistory row = new SmsCouponHistory();
        row.setId(41L); row.setMemberId(501L); row.setCouponId(30L);
        row.setUseStatus(0); row.setDelFlag(0);
        return row;
    }

    private Map<String, Object> reverse(CmsTopicCompletionCouponRewardMapper grants,
                                      SmsCouponHistoryMapper histories) {
        RedemptionRewardClawbackService service = new RedemptionRewardClawbackService();
        when(grants.selectCouponId(700L, 501L)).thenReturn(30L);
        ReflectionTestUtils.setField(service, "completionCouponRewardMapper", grants);
        ReflectionTestUtils.setField(service, "smsCouponHistoryMapper", histories);
        return ReflectionTestUtils.invokeMethod(service, "voidCompletionCoupon", 501L, 700L, 61L);
    }

    private void assertNoAssetWrite(CmsTopicCompletionCouponRewardMapper grants, SmsCouponHistoryMapper histories) {
        verify(histories, never()).voidUnusedCoupon(anyLong(), anyString());
        verify(grants, never()).deleteGrant(anyLong(), anyLong());
    }
}
