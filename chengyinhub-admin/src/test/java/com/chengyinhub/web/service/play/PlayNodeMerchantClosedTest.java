package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.IMmsMerchantService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** M-14:商家点「已打烊」后,玩家站点卡的营业态位显示「今日已打烊」;读不到时不编造打烊。 */
class PlayNodeMerchantClosedTest {

    @Test
    void closedMerchantIsReportedOnceAndCached() {
        PlayNodeQueryApplication app = new PlayNodeQueryApplication();
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant closed = new MmsMerchant();
        closed.setBusinessStatus(0);
        MmsMerchant open = new MmsMerchant();
        open.setBusinessStatus(1);
        when(merchants.selectMmsMerchantByMemberId(8006L)).thenReturn(closed);
        when(merchants.selectMmsMerchantByMemberId(8007L)).thenReturn(open);
        when(merchants.selectMmsMerchantByMemberId(8008L)).thenThrow(new RuntimeException("db"));
        ReflectionTestUtils.setField(app, "mmsMerchantService", merchants);
        Map<Long, Boolean> cache = new HashMap<>();

        assertTrue(app.merchantClosed(cache, 8006L));
        assertTrue(app.merchantClosed(cache, 8006L));
        verify(merchants, times(1)).selectMmsMerchantByMemberId(8006L);
        assertFalse(app.merchantClosed(cache, 8007L));
        assertFalse(app.merchantClosed(cache, 8008L), "读失败按营业处理");
        assertFalse(app.merchantClosed(cache, null));
        assertEquals("今日已打烊", PlayNodeQueryApplication.MERCHANT_CLOSED_TODAY);
    }
}
