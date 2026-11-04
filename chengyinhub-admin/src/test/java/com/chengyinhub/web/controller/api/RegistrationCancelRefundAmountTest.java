package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.util.RefundPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * check-1104 j1 F7:取消前要看到这一单退多少。订单详情下发 cancelRefundAmount =
 * 微信实付 × 当前档位比例(向下取分),与自助取消实际退款同一公式(RefundPolicy.applyCashRatio,
 * TeamGroupServiceImpl.refundOne 走的就是它)。
 */
class RegistrationCancelRefundAmountTest {

    private static final Long REG = 8101L;
    private static final Long ACTIVITY = 901L;
    private static final Long MEMBER = 99L;
    private static final long HOUR = 3600_000L;

    private com.chengyinhub.business.service.IMmsMerchantService shops;
    private Long activityOwner;

    private CmsRegistration detail(long startsInMs, String wechatCash, String parentOrderNo) {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        ApiRegistrationController controller = new ApiRegistrationController() {
            @Override public Long getAppUserId() { return MEMBER; }
        };
        CmsRegistration row = new CmsRegistration();
        row.setId(REG);
        row.setMemberId(MEMBER);
        row.setOwnerType(2L);
        row.setOwnerId(ACTIVITY);
        row.setRegistrationStatus(2);
        row.setPaymentStatus(2);
        row.setVerificationStatus(0);
        row.setPayableAmount(new BigDecimal(wechatCash));
        row.setWechatPaymentAmount(new BigDecimal(wechatCash));
        row.setParentOrderNo(parentOrderNo);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setStartDate(new Date(System.currentTimeMillis() + startsInMs));
        activity.setMemberId(activityOwner);
        when(registrations.selectCmsRegistrationById(REG)).thenReturn(row);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrations);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", activities);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", mock(IRefundService.class));
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "cancellationReadService",
                mock(com.chengyinhub.business.service.support.RegistrationCancellationReadService.class));
        if (shops != null) {
            ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "mmsMerchantService", shops);
        }
        assertEquals(200, RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG)).get("code"));
        return row;
    }

    @Test
    void moreThan24HoursBeforeStartShowsTheFullCash() {
        assertEquals(new BigDecimal("69.90"), detail(72 * HOUR, "69.90", null).getCancelRefundAmount());
    }

    @Test
    void within24HoursShowsHalfRoundedDownLikeTheRealRefund() {
        assertEquals(new BigDecimal("19.50"), detail(12 * HOUR, "39.00", null).getCancelRefundAmount());
        assertEquals(new BigDecimal("19.49"), detail(12 * HOUR, "38.99", null).getCancelRefundAmount(),
                "向下取分,与 TeamGroupServiceImpl 实退同一公式");
        assertEquals(RefundPolicy.applyCashRatio(new BigDecimal("38.99"), new BigDecimal("0.5")), new BigDecimal("19.49"));
    }

    @Test
    void afterStartOrMultiTicketChildShowsNoAmount() {
        assertNull(detail(-HOUR, "39.00", null).getCancelRefundAmount(), "开始后不可退,不出金额");
        assertNull(detail(72 * HOUR, "39.00", "P-1").getCancelRefundAmount(), "多票从单不代表整单现金,不推算");
    }

    /** check-1104 j1 F14:商家自己发的场次,订单「主办方」写店铺名,不写「城瘾用户」。 */
    @Test
    void merchantOwnActivityOrderNamesTheShop() {
        shops = mock(com.chengyinhub.business.service.IMmsMerchantService.class);
        com.chengyinhub.business.domain.MmsMerchant shop = new com.chengyinhub.business.domain.MmsMerchant();
        shop.setName("梧桐里书房");
        shop.setStatus(1L);
        shop.setAccountStatus(1L);
        shop.setDelFlag(0);
        when(shops.selectMmsMerchantByMemberId(990005L)).thenReturn(shop);
        activityOwner = 990005L;
        assertEquals("梧桐里书房", detail(72 * HOUR, "39.00", null).getOrganizerName());
    }
}
