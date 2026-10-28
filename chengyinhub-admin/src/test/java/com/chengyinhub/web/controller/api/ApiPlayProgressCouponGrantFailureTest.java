package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ISmsCouponHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点发券的失败必须可观测:服务层返回 false(券不存在/库存耗尽)时,
 * controller 的私有 helper 不得把它当成功丢弃,必须原样返回给调用方。
 */
class ApiPlayProgressCouponGrantFailureTest
{
    private static final Long MEMBER = 501L;
    private static final Long COUPON = 77L;

    private ApiPlayProgressController controllerWith(ISmsCouponHistoryService service)
    {
        ApiPlayProgressController controller = new ApiPlayProgressController();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", service);
        return controller;
    }

    private Object grant(ApiPlayProgressController controller, Long memberId, Long couponId)
    {
        return com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "grantCouponById", memberId, couponId);
    }

    @Test
    void grantReturnsTrueAndPassesMemberAndCouponWhenServiceSucceeds()
    {
        ISmsCouponHistoryService service = mock(ISmsCouponHistoryService.class);
        when(service.grantCouponById(MEMBER, COUPON)).thenReturn(true);

        Object result = grant(controllerWith(service), MEMBER, COUPON);

        assertEquals(Boolean.class, result == null ? null : result.getClass(),
                "helper 必须返回 boolean,返回 void/null 说明发券结果被丢弃");
        assertTrue((Boolean) result, "服务层发券成功时 helper 应返回 true");
        verify(service).grantCouponById(MEMBER, COUPON);
    }

    @Test
    void grantReturnsFalseWhenCouponMissingOrOutOfStock()
    {
        ISmsCouponHistoryService service = mock(ISmsCouponHistoryService.class);
        when(service.grantCouponById(MEMBER, COUPON)).thenReturn(false);

        Object result = grant(controllerWith(service), MEMBER, COUPON);

        assertEquals(Boolean.class, result == null ? null : result.getClass(),
                "helper 必须返回 boolean,返回 void/null 说明发券失败被静默吞掉");
        assertFalse((Boolean) result, "券不存在/库存耗尽时 helper 不得把 false 当成功");
        verify(service).grantCouponById(MEMBER, COUPON);
    }

    @Test
    void grantSkipsDisabledOrStoppedCouponWithoutManualTopUpNoise()
    {
        ISmsCouponHistoryService service = mock(ISmsCouponHistoryService.class);
        when(service.isCouponNotIssuable(COUPON)).thenReturn(true);

        Object result = grant(controllerWith(service), MEMBER, COUPON);

        assertFalse((Boolean) result, "失效/停发券跳过发券,helper 返回 false");
        verify(service, never()).grantCouponById(MEMBER, COUPON);
    }
}
