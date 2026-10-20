package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.dto.CouponPublisDTO;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 商家优惠券先发后审(2026-10-20):资质闸 + 同步文本门禁 POST_REVIEW + 打标留抽检单。 */
class CouponPublishReviewModeTest {

    private static final Long PRINCIPAL = 7002L;
    private static final Long OWNER = 9001L;
    private static final Long MERCHANT_ROW = 11L;

    private ISmsCouponService couponService;
    private IQuotaService quotaService;
    private RedisCache redisCache;
    private IMerchantAccessService merchantAccessService;
    private IContentSafetyGate gate;
    private IAuditTaskService auditTaskService;
    private ApiCouponController controller;

    @BeforeEach
    void setUp() {
        couponService = mock(ISmsCouponService.class);
        quotaService = mock(IQuotaService.class);
        redisCache = mock(RedisCache.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        gate = mock(IContentSafetyGate.class);
        auditTaskService = mock(IAuditTaskService.class);
        controller = new ApiCouponController() {
            @Override
            public Long getAppUserId() {
                return PRINCIPAL;
            }
        };
        ReflectionTestUtils.setField(controller, "smsCouponService", couponService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "redisCache", redisCache);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "roleService", mock(IRoleService.class));
        ReflectionTestUtils.setField(controller, "contentSafetyGate", gate);
        ReflectionTestUtils.setField(controller, "auditTaskService", auditTaskService);

        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.COUPON_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, MERCHANT_ROW, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        when(redisCache.setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(couponService.insertSmsCoupon(any())).thenAnswer(inv -> {
            ((SmsCoupon) inv.getArgument(0)).setId(4567L);
            return 1;
        });
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Supplier<Integer> action = inv.getArgument(2);
            return action.get();
        }).when(quotaService).runWithCreateGuard(eq(OWNER), eq(IQuotaService.TYPE_COUPON), any());
    }

    @Test
    void cleanTextPublishesImmediatelyWithoutAuditTask() {
        when(gate.decide(any())).thenReturn(SafetyDecision.pass());

        AjaxResult result = controller.publish(request());

        assertTrue(result.isSuccess());
        ArgumentCaptor<SmsCoupon> coupon = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(couponService).insertSmsCoupon(coupon.capture());
        assertEquals(1, coupon.getValue().getStatus());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), anyString());
        ArgumentCaptor<PublishSafetyRequest> safety = ArgumentCaptor.forClass(PublishSafetyRequest.class);
        verify(gate).decide(safety.capture());
        assertEquals(PublishSafetyRequest.ReviewMode.POST_REVIEW.name(), safety.getValue().getReviewMode());
        assertEquals(AuditTask.BIZ_COUPON, safety.getValue().getContentType());
        assertEquals(OWNER, safety.getValue().getMemberId());
        assertTrue(safety.getValue().getText().contains("周末饮品券"));
        assertTrue(safety.getValue().getText().contains("到店使用"));
    }

    @Test
    void riskyTextIsRefusedAndNothingIsInserted() {
        when(gate.decide(any())).thenReturn(SafetyDecision.block("TEXT_RISK"));

        AjaxResult result = controller.publish(request());

        assertFalse(result.isSuccess());
        assertEquals("优惠券内容包含违规信息，请修改后重试", result.get(AjaxResult.MSG_TAG));
        verify(couponService, never()).insertSmsCoupon(any());
        verify(redisCache, never()).setIfAbsent(anyString(), any(), any(Integer.class), any());
    }

    @Test
    void unapprovedMerchantIsRefusedBeforeGateAndInsert() {
        // require() 只对 mms_merchant.status=1/account_status=1 的在营商家返回上下文,否则 403
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.COUPON_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        CouponPublisDTO req = request();
        req.setScope("MERCHANT");

        assertThrows(ServiceException.class, () -> controller.publish(req));
        verify(gate, never()).decide(any());
        verify(couponService, never()).insertSmsCoupon(any());
    }

    @Test
    void merchantContextWithoutMerchantRowIsRefused() {
        when(merchantAccessService.require(PRINCIPAL, MerchantPermission.COUPON_MANAGE))
                .thenReturn(new MerchantAccessContext(PRINCIPAL, null, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        CouponPublisDTO req = request();
        req.setScope("MERCHANT");

        AjaxResult result = controller.publish(req);

        assertEquals("商家未通过入驻审核，暂不能发布优惠券", result.get(AjaxResult.MSG_TAG));
        verify(gate, never()).decide(any());
        verify(couponService, never()).insertSmsCoupon(any());
    }

    @Test
    void flaggedDecisionPublishesAndLeavesSpotCheckTask() {
        when(gate.decide(any())).thenReturn(SafetyDecision.passFlagged("TEXT_REVIEW"));

        AjaxResult result = controller.publish(request());

        assertTrue(result.isSuccess());
        verify(couponService).insertSmsCoupon(any());
        ArgumentCaptor<String> snap = ArgumentCaptor.forClass(String.class);
        verify(auditTaskService).submit(eq(AuditTask.BIZ_COUPON), eq(4567L), eq(OWNER), eq("post_review_flag"), snap.capture());
        assertTrue(snap.getValue().contains("TEXT_REVIEW"));
    }

    private static CouponPublisDTO request() {
        CouponPublisDTO request = new CouponPublisDTO();
        request.setName("周末饮品券");
        request.setStartTime(new Date(1_000L));
        request.setEndTime(new Date(2_000L));
        request.setPublishCount(20L);
        request.setCouponType(2);
        request.setDescription("到店使用");
        return request;
    }
}
