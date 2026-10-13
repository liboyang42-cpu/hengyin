package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.service.ISmsCouponService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ApiMerchantCouponSummaryScopeTest {

    @Test
    void couponSummaryUsesOwnerAndExactMerchantScopeAndCountsOnlyOnAirCoupons()
            throws Exception {
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        ISmsCouponService couponService = mock(ISmsCouponService.class);
        when(accessService.require(901L, MerchantPermission.COUPON_MANAGE)).thenReturn(
                new MerchantAccessContext(901L, 11L, 9001L, MerchantRoleCode.MERCHANT_MANAGER));
        when(couponService.selectSmsCouponList(any())).thenAnswer(call -> {
            SmsCoupon probe = call.getArgument(0);
            assertEquals(9001L, probe.getMemberId());
            assertEquals(11L, probe.getMerchantId());
            assertEquals(1, probe.getScopeType());
            // CU-M-86:「N 张在投放」只数 status=1 且在有效期内的券 —— 已停发的照旧计入
            // 券总数与领取/核销累计,但不许冒充在投放。
            long day = 24L * 60L * 60L * 1000L;
            long now = System.currentTimeMillis();
            SmsCoupon merchantA = new SmsCoupon();
            merchantA.setStatus(1);
            merchantA.setStartTime(new java.util.Date(now - day));
            merchantA.setEndTime(new java.util.Date(now + day));
            merchantA.setReceiveCount(3L);
            merchantA.setUseCount(2L);
            merchantA.setDelFlag(0);
            SmsCoupon stopped = new SmsCoupon();
            stopped.setStatus(4);
            stopped.setStartTime(new java.util.Date(now - day));
            stopped.setEndTime(new java.util.Date(now + day));
            stopped.setReceiveCount(4L);
            stopped.setUseCount(1L);
            stopped.setDelFlag(0);
            return java.util.Arrays.asList(merchantA, stopped);
        });

        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(901L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(controller, "smsCouponService", couponService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/merchant/coupon-summary"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.couponCount").value(1))
                .andExpect(jsonPath("$.data.couponTotal").value(2))
                .andExpect(jsonPath("$.data.received").value(7))
                .andExpect(jsonPath("$.data.verified").value(3));
    }
}
