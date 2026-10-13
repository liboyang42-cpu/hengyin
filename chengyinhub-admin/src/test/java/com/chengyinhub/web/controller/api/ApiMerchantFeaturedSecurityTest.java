package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IMerchantNodeDiscoveryService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantFeaturedSecurityTest {

    private static final long OWNER = 9L;
    private static final long MERCHANT_ID = 77L;
    private static final long FEATURED_ID = 66L;

    @Test
    void saveDecorRejectsForeignActivityEvenWhenItIsPublic() {
        Fixture fixture = fixture();
        CmsActivity foreign = publicActivity(10L);
        when(fixture.activityService.selectCmsActivityById(FEATURED_ID)).thenReturn(foreign);
        when(fixture.activityService.selectPublicCmsActivityById(FEATURED_ID)).thenReturn(foreign);

        AjaxResult result = fixture.controller.saveDecor(featuredRequest(1, FEATURED_ID));

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(fixture.merchantService, never()).updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString());
    }

    @Test
    void saveDecorRejectsPrivateOrDeletedFeaturedObjects() {
        Fixture activityFixture = fixture();
        CmsActivity privateActivity = publicActivity(OWNER);
        privateActivity.setPublishStatus(0L);
        when(activityFixture.activityService.selectCmsActivityById(FEATURED_ID)).thenReturn(privateActivity);
        when(activityFixture.activityService.selectPublicCmsActivityById(FEATURED_ID)).thenReturn(null);
        assertNotEquals(200, activityFixture.controller.saveDecor(featuredRequest(1, FEATURED_ID))
                .get(AjaxResult.CODE_TAG));
        verify(activityFixture.merchantService, never()).updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString());

        Fixture couponFixture = fixture();
        SmsCoupon deletedCoupon = activeCoupon(OWNER);
        deletedCoupon.setDelFlag(2);
        when(couponFixture.couponService.selectSmsCouponById(FEATURED_ID)).thenReturn(deletedCoupon);
        assertNotEquals(200, couponFixture.controller.saveDecor(featuredRequest(2, FEATURED_ID))
                .get(AjaxResult.CODE_TAG));
        verify(couponFixture.merchantService, never()).updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString());
    }

    @Test
    void featuredCouponMustBelongToTheExactMerchantRowEvenWhenOwnerIsTheSame() {
        Fixture saveFixture = fixture();
        SmsCoupon siblingStoreCoupon = activeCoupon(OWNER);
        siblingStoreCoupon.setMerchantId(88L);
        when(saveFixture.couponService.selectSmsCouponById(FEATURED_ID))
                .thenReturn(siblingStoreCoupon);

        assertNotEquals(200, saveFixture.controller.saveDecor(featuredRequest(2, FEATURED_ID))
                .get(AjaxResult.CODE_TAG));
        verify(saveFixture.merchantService, never()).updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString());

        Fixture publicFixture = fixture();
        publicFixture.merchant.setFeaturedType(2);
        publicFixture.merchant.setFeaturedId(FEATURED_ID);
        when(publicFixture.couponService.selectSmsCouponById(FEATURED_ID))
                .thenReturn(siblingStoreCoupon);
        assertFalse(publicFixture.controller.publicDetail(merchantRequest()).containsKey("featured"));
    }

    @Test
    void merchantOperatorCanSetOwnerScopedActivityAsExactStoreFeaturedContent() {
        Fixture fixture = fixture(10L, MerchantRoleCode.MERCHANT_MANAGER);
        CmsActivity activity = publicActivity(OWNER);
        when(fixture.activityService.selectPublicCmsActivityById(FEATURED_ID)).thenReturn(activity);

        AjaxResult result = fixture.controller.saveDecor(featuredRequest(1, FEATURED_ID));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(fixture.merchantService).updateAuthorizedProfile(
                org.mockito.ArgumentMatchers.argThat(update ->
                        Long.valueOf(MERCHANT_ID).equals(update.getId())
                                && Integer.valueOf(1).equals(update.getFeaturedType())
                                && Long.valueOf(FEATURED_ID).equals(update.getFeaturedId())),
                anyLong(), anyString());
    }

    @Test
    void featuredCouponMustBeInsideItsAuthoritativeTimeWindow() {
        Fixture futureFixture = fixture();
        SmsCoupon futureCoupon = activeCoupon(OWNER);
        futureCoupon.setStartTime(new Date(System.currentTimeMillis() + 60_000L));
        futureCoupon.setEndTime(new Date(System.currentTimeMillis() + 120_000L));
        when(futureFixture.couponService.selectSmsCouponById(FEATURED_ID)).thenReturn(futureCoupon);
        assertNotEquals(200, futureFixture.controller.saveDecor(featuredRequest(2, FEATURED_ID))
                .get(AjaxResult.CODE_TAG));

        Fixture expiredFixture = fixture();
        expiredFixture.merchant.setFeaturedType(2);
        expiredFixture.merchant.setFeaturedId(FEATURED_ID);
        SmsCoupon expiredCoupon = activeCoupon(OWNER);
        expiredCoupon.setStartTime(new Date(System.currentTimeMillis() - 120_000L));
        expiredCoupon.setEndTime(new Date(System.currentTimeMillis() - 60_000L));
        when(expiredFixture.couponService.selectSmsCouponById(FEATURED_ID)).thenReturn(expiredCoupon);
        assertFalse(expiredFixture.controller.publicDetail(merchantRequest()).containsKey("featured"));

        Fixture missingWindowFixture = fixture();
        SmsCoupon missingWindowCoupon = activeCoupon(OWNER);
        missingWindowCoupon.setStartTime(null);
        missingWindowCoupon.setEndTime(null);
        when(missingWindowFixture.couponService.selectSmsCouponById(FEATURED_ID))
                .thenReturn(missingWindowCoupon);
        assertNotEquals(200, missingWindowFixture.controller.saveDecor(featuredRequest(2, FEATURED_ID))
                .get(AjaxResult.CODE_TAG));
    }

    @Test
    void publicDetailRechecksCurrentVisibilityAndOwnership() {
        Fixture privateFixture = fixture();
        privateFixture.merchant.setFeaturedType(1);
        privateFixture.merchant.setFeaturedId(FEATURED_ID);
        CmsActivity privateActivity = publicActivity(OWNER);
        privateActivity.setStatus(0L);
        when(privateFixture.activityService.selectCmsActivityById(FEATURED_ID)).thenReturn(privateActivity);
        when(privateFixture.activityService.selectPublicCmsActivityById(FEATURED_ID)).thenReturn(null);
        assertFalse(privateFixture.controller.publicDetail(merchantRequest()).containsKey("featured"));

        Fixture foreignFixture = fixture();
        foreignFixture.merchant.setFeaturedType(2);
        foreignFixture.merchant.setFeaturedId(FEATURED_ID);
        when(foreignFixture.couponService.selectSmsCouponById(FEATURED_ID))
                .thenReturn(activeCoupon(10L));
        assertFalse(foreignFixture.controller.publicDetail(merchantRequest()).containsKey("featured"));
    }

    @Test
    void publicDetailProjectsActivityAndCouponToExplicitSafeFields() throws Exception {
        Fixture activityFixture = fixture();
        activityFixture.merchant.setFeaturedType(1);
        activityFixture.merchant.setFeaturedId(FEATURED_ID);
        CmsActivity activity = publicActivity(OWNER);
        activity.setName("夜游活动");
        activity.setImgUrl("cover.png");
        activity.setReason("内部审核原因");
        activity.setCreateBy("admin-secret");
        when(activityFixture.activityService.selectCmsActivityById(FEATURED_ID)).thenReturn(activity);
        when(activityFixture.activityService.selectPublicCmsActivityById(FEATURED_ID)).thenReturn(activity);

        Object activityFeatured = activityFixture.controller.publicDetail(merchantRequest()).get("featured");
        assertNotNull(activityFeatured);
        assertFalse(activityFeatured instanceof CmsActivity);
        String activityJson = new ObjectMapper().writeValueAsString(activityFeatured);
        assertFalse(activityJson.contains("memberId"));
        assertFalse(activityJson.contains("reason"));
        assertFalse(activityJson.contains("createBy"));
        assertFalse(activityJson.contains("publishStatus"));

        Fixture couponFixture = fixture();
        couponFixture.merchant.setFeaturedType(2);
        couponFixture.merchant.setFeaturedId(FEATURED_ID);
        SmsCoupon coupon = activeCoupon(OWNER);
        coupon.setName("夜行优惠券");
        coupon.setAmount(new BigDecimal("8.00"));
        coupon.setCode("INTERNAL-CODE");
        coupon.setCreateBy("admin-secret");
        when(couponFixture.couponService.selectSmsCouponById(FEATURED_ID)).thenReturn(coupon);

        Object couponFeatured = couponFixture.controller.publicDetail(merchantRequest()).get("featured");
        assertNotNull(couponFeatured);
        assertFalse(couponFeatured instanceof SmsCoupon);
        String couponJson = new ObjectMapper().writeValueAsString(couponFeatured);
        assertFalse(couponJson.contains("memberId"));
        assertFalse(couponJson.contains("merchantId"));
        assertFalse(couponJson.contains("code"));
        assertFalse(couponJson.contains("createBy"));
        assertFalse(couponJson.contains("publishCount"));
    }

    @Test
    void saveDecorAcceptsOwnedPublicFeaturedAndClearingButRejectsMalformedPairs() {
        Fixture fixture = fixture();
        CmsActivity activity = publicActivity(OWNER);
        when(fixture.activityService.selectCmsActivityById(FEATURED_ID)).thenReturn(activity);
        when(fixture.activityService.selectPublicCmsActivityById(FEATURED_ID)).thenReturn(activity);
        assertEquals(200, fixture.controller.saveDecor(featuredRequest(1, FEATURED_ID))
                .get(AjaxResult.CODE_TAG));

        Fixture clearFixture = fixture();
        assertEquals(200, clearFixture.controller.saveDecor(featuredRequest(0, null))
                .get(AjaxResult.CODE_TAG));

        Fixture malformedFixture = fixture();
        assertNotEquals(200, malformedFixture.controller.saveDecor(featuredRequest(9, FEATURED_ID))
                .get(AjaxResult.CODE_TAG));
        verify(malformedFixture.merchantService, never()).updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString());
    }

    private static CmsActivity publicActivity(long owner) {
        CmsActivity activity = new CmsActivity();
        activity.setId(FEATURED_ID);
        activity.setMemberId(owner);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        return activity;
    }

    private static SmsCoupon activeCoupon(long owner) {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(FEATURED_ID);
        coupon.setMemberId(owner);
        coupon.setScopeType(1);
        coupon.setMerchantId(MERCHANT_ID);
        coupon.setStatus(1);
        coupon.setDelFlag(0);
        coupon.setStartTime(new Date(System.currentTimeMillis() - 60_000L));
        coupon.setEndTime(new Date(System.currentTimeMillis() + 60_000L));
        return coupon;
    }

    private static MmsMerchant featuredRequest(Integer type, Long id) {
        MmsMerchant request = new MmsMerchant();
        request.setFeaturedType(type);
        request.setFeaturedId(id);
        return request;
    }

    private static MmsMerchant merchantRequest() {
        MmsMerchant request = new MmsMerchant();
        request.setId(MERCHANT_ID);
        return request;
    }

    private static Fixture fixture() {
        return fixture(OWNER, MerchantRoleCode.MERCHANT_OWNER);
    }

    private static Fixture fixture(long principal, String roleCode) {
        Fixture fixture = new Fixture();
        fixture.merchant = new MmsMerchant();
        fixture.merchant.setId(MERCHANT_ID);
        fixture.merchant.setMemberId(OWNER);
        fixture.merchant.setStatus(1L);
        fixture.merchant.setAccountStatus(1L);
        fixture.merchant.setDelFlag(0);

        fixture.merchantService = mock(IMmsMerchantService.class);
        fixture.activityService = mock(ICmsActivityService.class);
        fixture.couponService = mock(ISmsCouponService.class);
        fixture.controller = new TestableController(principal);
        when(fixture.merchantService.selectMmsMerchantById(MERCHANT_ID)).thenReturn(fixture.merchant);
        when(fixture.merchantService.updateAuthorizedProfile(any(MmsMerchant.class), anyLong(), anyString())).thenReturn(1);

        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        when(accessService.require(principal, MerchantPermission.PROFILE_WRITE))
                .thenReturn(new MerchantAccessContext(principal, MERCHANT_ID, OWNER, roleCode));
        ReflectionTestUtils.setField(fixture.controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(fixture.controller, "mmsMerchantService", fixture.merchantService);
        ReflectionTestUtils.setField(fixture.controller, "cmsActivityService", fixture.activityService);
        ReflectionTestUtils.setField(fixture.controller, "smsCouponService", fixture.couponService);
        ReflectionTestUtils.setField(fixture.controller, "coopInviteService", mock(ICoopInviteService.class));
        ReflectionTestUtils.setField(fixture.controller, "sysCategoryService", mock(ISysCategoryService.class));
        ReflectionTestUtils.setField(fixture.controller, "merchantNodeDiscoveryService", mock(IMerchantNodeDiscoveryService.class));
        ReflectionTestUtils.setField(fixture.controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(fixture.controller, "umsMemberService", mock(IUmsMemberService.class));
        return fixture;
    }

    private static class TestableController extends ApiMerchantController {
        private final long principal;

        private TestableController(long principal) {
            this.principal = principal;
        }

        @Override
        public Long getAppUserId() {
            return principal;
        }
    }

    private static class Fixture {
        private TestableController controller;
        private MmsMerchant merchant;
        private IMmsMerchantService merchantService;
        private ICmsActivityService activityService;
        private ISmsCouponService couponService;
    }
}
