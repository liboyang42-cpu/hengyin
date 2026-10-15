package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.domain.dto.CouponPublisDTO;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.domain.ViewCouponHistory;
import com.chengyinhub.system.service.IViewCouponHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCouponControllerMerchantAccessTest {

    @Test
    void publishCountMustBePositiveAtRequestBoundary() {
        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            jakarta.validation.Validator validator = factory.getValidator();
            for (Long count : new Long[] {null, -1L, 0L}) {
                assertFalse(validator.validateValue(CouponPublisDTO.class, "publishCount", count).isEmpty());
            }
            assertEquals(0, validator.validateValue(CouponPublisDTO.class, "publishCount", 1L).size());
        }
    }

    private static final Long PRINCIPAL_MEMBER_ID = 7002L;
    private static final Long OWNER_MEMBER_ID = 9001L;
    private static final Long MERCHANT_ROW_ID = 11L;

    private ISmsCouponService couponService;
    private ISmsCouponHistoryService historyService;
    private IQuotaService quotaService;
    private RedisCache redisCache;
    private IViewCouponHistoryService viewCouponHistoryService;
    private QrcodeServiceImpl qrcodeService;
    private IMerchantAccessService merchantAccessService;
    private IUmsMemberService memberService;
    private IRoleService roleService;
    private ApiCouponController controller;

    @BeforeEach
    void setUp() {
        couponService = mock(ISmsCouponService.class);
        historyService = mock(ISmsCouponHistoryService.class);
        quotaService = mock(IQuotaService.class);
        redisCache = mock(RedisCache.class);
        viewCouponHistoryService = mock(IViewCouponHistoryService.class);
        qrcodeService = mock(QrcodeServiceImpl.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        memberService = mock(IUmsMemberService.class);
        roleService = mock(IRoleService.class);
        controller = new LoggedInCouponController();

        ReflectionTestUtils.setField(controller, "smsCouponService", couponService);
        ReflectionTestUtils.setField(controller, "smsCouponHistoryService", historyService);
        ReflectionTestUtils.setField(controller, "quotaService", quotaService);
        ReflectionTestUtils.setField(controller, "redisCache", redisCache);
        ReflectionTestUtils.setField(controller, "viewCouponHistoryService", viewCouponHistoryService);
        ReflectionTestUtils.setField(controller, "qrcodeService", qrcodeService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(controller, "umsMemberService", memberService);
        ReflectionTestUtils.setField(controller, "roleService", roleService);

        when(merchantAccessService.require(PRINCIPAL_MEMBER_ID, MerchantPermission.COUPON_MANAGE))
                .thenReturn(access(MerchantRoleCode.MERCHANT_MARKETING));
        when(merchantAccessService.require(PRINCIPAL_MEMBER_ID, MerchantPermission.VERIFY))
                .thenReturn(access(MerchantRoleCode.MERCHANT_CHECKIN));
    }

    @Test
    void rewardSelectionRequestsIssuableCouponsOnly() {
        when(couponService.selectSmsCouponList(any())).thenReturn(Collections.emptyList());
        controller.myPublishCouponList(null, "1", "MERCHANT");
        ArgumentCaptor<SmsCoupon> query = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(couponService).selectSmsCouponList(query.capture());
        assertEquals(Boolean.TRUE, query.getValue().getParams().get("issuableOnly"));
    }

    @Test
    void publishListUsesMerchantOwnerInsteadOfOperatorPrincipal() {
        when(couponService.selectSmsCouponList(any())).thenReturn(Collections.emptyList());

        AjaxResult result = controller.myPublishCouponList(null, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.COUPON_MANAGE);
        verify(couponService).updateExpiredCouponsByMemberId(OWNER_MEMBER_ID);
        ArgumentCaptor<SmsCoupon> query = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(couponService).selectSmsCouponList(query.capture());
        assertEquals(OWNER_MEMBER_ID, query.getValue().getMemberId());
        assertEquals(1, query.getValue().getScopeType());
        assertEquals(MERCHANT_ROW_ID, query.getValue().getMerchantId());
    }

    @Test
    void publishScopesQuotaLockAndCouponToMerchantOwner() {
        when(quotaService.checkCreate(OWNER_MEMBER_ID, IQuotaService.TYPE_COUPON)).thenReturn(null);
        when(redisCache.setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(couponService.insertSmsCoupon(any())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Integer> action = invocation.getArgument(2);
            return action.get();
        }).when(quotaService).runWithCreateGuard(eq(OWNER_MEMBER_ID), eq(IQuotaService.TYPE_COUPON), any());

        AjaxResult result = controller.publish(publishRequest());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.COUPON_MANAGE);
        verify(quotaService).checkCreate(OWNER_MEMBER_ID, IQuotaService.TYPE_COUPON);
        verify(quotaService).runWithCreateGuard(eq(OWNER_MEMBER_ID), eq(IQuotaService.TYPE_COUPON), any());
        ArgumentCaptor<String> lockKey = ArgumentCaptor.forClass(String.class);
        verify(redisCache).setIfAbsent(lockKey.capture(), eq("1"), eq(60), eq(TimeUnit.SECONDS));
        assertFalse(lockKey.getValue().contains(String.valueOf(PRINCIPAL_MEMBER_ID)));
        assertEquals("coupon:publish:" + OWNER_MEMBER_ID + ":2:",
                lockKey.getValue().substring(0, lockKey.getValue().lastIndexOf(':') + 1));
        ArgumentCaptor<SmsCoupon> coupon = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(couponService).insertSmsCoupon(coupon.capture());
        assertEquals(OWNER_MEMBER_ID, coupon.getValue().getMemberId());
        assertEquals(1, coupon.getValue().getScopeType());
        assertEquals(MERCHANT_ROW_ID, coupon.getValue().getMerchantId());
    }

    @Test
    void publishReturnsGeneratedCouponIdSoRewardSelectorCanAttachIt() {
        // C-31:发布流「新建即选中」读 res.data.id;不回 id 会让节点奖励静默挂成 couponId=0。
        when(redisCache.setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(couponService.insertSmsCoupon(any())).thenAnswer(invocation -> {
            ((SmsCoupon) invocation.getArgument(0)).setId(4567L);
            return 1;
        });
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Integer> action = invocation.getArgument(2);
            return action.get();
        }).when(quotaService).runWithCreateGuard(eq(OWNER_MEMBER_ID), eq(IQuotaService.TYPE_COUPON), any());

        AjaxResult result = controller.publish(publishRequest());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(4567L, ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("id"));
    }

    @Test
    void activeCouponCodeIsReadableWithoutObjectStorage() throws Exception {
        SmsCouponHistory history = couponHistory(301L, 30L);
        history.setMemberId(PRINCIPAL_MEMBER_ID);
        history.setUseStatus(0);
        history.setEndTime(new Date(System.currentTimeMillis() + 60_000));
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
        String credential = "coupon-dynamic-credential-with-60-second-validity";
        when(couponService.generateCouponQrToken(301L)).thenReturn(credential);
        QrcodeServiceImpl memoryQr = org.mockito.Mockito.spy(new QrcodeServiceImpl());
        org.mockito.Mockito.doThrow(new IllegalStateException("Object storage is unavailable"))
                .when(memoryQr).generateQrcode(anyString(), eq(300), eq(300));
        ReflectionTestUtils.setField(controller, "qrcodeService", memoryQr);

        AjaxResult response = controller.qrToken(301L);

        assertEquals(200, response.get(AjaxResult.CODE_TAG));
        java.util.Map<?, ?> data = (java.util.Map<?, ?>) response.get(AjaxResult.DATA_TAG);
        assertEquals(credential, data.get("token"));
        assertEquals(60, data.get("expiresIn"));
        String uri = (String) data.get("qrcodeUrl");
        org.junit.jupiter.api.Assertions.assertTrue(uri.startsWith("data:image/png;base64,"));
        byte[] png = java.util.Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        com.google.zxing.BinaryBitmap bitmap = new com.google.zxing.BinaryBitmap(
                new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(
                        image.getWidth(), image.getHeight(), pixels)));
        assertEquals(credential, new com.google.zxing.qrcode.QRCodeReader().decode(bitmap).getText());
        verify(memoryQr, never()).generateQrcode(anyString(), eq(300), eq(300));
    }

    @Test
    void terminalHistoryGetsStatusButNoScannableToken() {
        // C-36:已过期/已核销不再签发可扫码 token(不依赖钱包列表先跑过期刷新)。
        for (int useStatus : new int[]{0, 1}) {
            SmsCouponHistory history = couponHistory(301L, 30L);
            history.setMemberId(PRINCIPAL_MEMBER_ID);
            history.setUseStatus(useStatus);
            history.setEndTime(new java.util.Date(System.currentTimeMillis() - 60_000));
            when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
            java.util.Map<?, ?> qr = (java.util.Map<?, ?>) controller.qrToken(301L).get("data");
            assertEquals(useStatus == 0 ? 2 : 1, qr.get("useStatus"));
            assertFalse(qr.containsKey("token"));
            assertFalse(qr.containsKey("qrcodeUrl"));
        }
        verify(couponService, never()).generateCouponQrToken(any());
    }

    @Test
    void disabledCouponVerificationTellsMerchantItIsStopped() {
        when(couponService.verifyCouponQrToken("dyn")).thenReturn(301L);
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(couponHistory(301L, 30L));
        when(couponService.selectSmsCouponById(30L)).thenReturn(coupon(30L, OWNER_MEMBER_ID));
        when(historyService.redeemCoupon(any(), any(), any(), any(), anyString()))
                .thenReturn(ISmsCouponHistoryService.RedemptionResult.DISABLED);
        AjaxResult result = controller.couponVerification(null, "dyn", "MERCHANT");
        assertEquals(500, result.get("code"));
        assertEquals("该优惠券已停用", result.get("msg"));
    }

    @Test
    void verificationUsesPrincipalForPermissionAndOwnerForCouponAttribution() {
        SmsCouponHistory history = couponHistory(301L, 30L);
        when(couponService.verifyCouponQrToken("dyn-token")).thenReturn(301L);
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
        when(couponService.selectSmsCouponById(30L)).thenReturn(coupon(30L, OWNER_MEMBER_ID));
        when(historyService.redeemCoupon(301L, OWNER_MEMBER_ID, MERCHANT_ROW_ID, PRINCIPAL_MEMBER_ID,
                MerchantRoleCode.MERCHANT_CHECKIN))
                .thenReturn(ISmsCouponHistoryService.RedemptionResult.REDEEMED);

        AjaxResult result = controller.couponVerification(null, "dyn-token", "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.VERIFY);
        verify(historyService).redeemCoupon(301L, OWNER_MEMBER_ID, MERCHANT_ROW_ID, PRINCIPAL_MEMBER_ID,
                MerchantRoleCode.MERCHANT_CHECKIN);
    }

    @Test
    void principalIdCollisionCannotAuthorizeAnotherMerchantCoupon() {
        SmsCouponHistory history = couponHistory(302L, 31L);
        when(couponService.verifyCouponQrToken("collision-token")).thenReturn(302L);
        when(historyService.selectSmsCouponHistoryById(302L)).thenReturn(history);
        when(couponService.selectSmsCouponById(31L)).thenReturn(coupon(31L, PRINCIPAL_MEMBER_ID));

        AjaxResult result = controller.couponVerification(null, "collision-token", "MERCHANT");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("无权核销该优惠券", result.get(AjaxResult.MSG_TAG));
        verify(historyService, never()).redeemCoupon(any(), any(), any(), any(), anyString());
    }

    @Test
    void deniedPublisherCannotReachQuotaLockOrWrite() {
        when(merchantAccessService.require(PRINCIPAL_MEMBER_ID, MerchantPermission.COUPON_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        ServiceException error = assertThrows(ServiceException.class,
                () -> controller.publish(publishRequest()));

        assertEquals(403, error.getCode());
        verify(quotaService, never()).checkCreate(any(), anyString());
        verify(redisCache, never()).setIfAbsent(anyString(), any(), any(), any());
        verify(couponService, never()).insertSmsCoupon(any());
    }

    @Test
    void deniedVerifierCannotResolveOrRedeemCoupon() {
        when(merchantAccessService.require(PRINCIPAL_MEMBER_ID, MerchantPermission.VERIFY))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        ServiceException error = assertThrows(ServiceException.class,
                () -> controller.couponVerification(null, "dyn-token", "MERCHANT"));

        assertEquals(403, error.getCode());
        verify(couponService, never()).verifyCouponQrToken(anyString());
        verify(historyService, never()).redeemCoupon(any(), any(), any(), any(), anyString());
    }

    @Test
    void ordinaryMembersReceivedCouponListDoesNotRequireMerchantAccess() {
        when(viewCouponHistoryService.selectViewCouponHistoryList(any())).thenReturn(Collections.emptyList());

        AjaxResult result = controller.myPublishCouponList((String) null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(historyService).updateExpiredCouponsByMemberId(PRINCIPAL_MEMBER_ID);
        ArgumentCaptor<ViewCouponHistory> query = ArgumentCaptor.forClass(ViewCouponHistory.class);
        verify(viewCouponHistoryService).selectViewCouponHistoryList(query.capture());
        assertEquals(PRINCIPAL_MEMBER_ID, query.getValue().getMemberId());
        verify(merchantAccessService, never()).require(any(), anyString());
    }

    @Test
    void ordinaryMemberCanDisplayOwnCouponWithoutMerchantAccess() {
        SmsCouponHistory history = couponHistory(401L, 40L);
        history.setMemberId(PRINCIPAL_MEMBER_ID);
        when(historyService.selectSmsCouponHistoryById(401L)).thenReturn(history);
        when(couponService.generateCouponQrToken(401L)).thenReturn("user-token");
        when(qrcodeService.generateQrcode("user-token", 300, 300)).thenReturn("https://qr/user.png");
        when(couponService.selectSmsCouponById(40L)).thenReturn(coupon(40L, OWNER_MEMBER_ID));

        AjaxResult result = controller.qrToken(401L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService, never()).require(any(), anyString());
    }

    @Test
    void clubPublishListKeepsClubPrincipalOwnershipWithoutMerchantAccess() {
        configureClubIdentity();
        when(couponService.selectSmsCouponList(any())).thenReturn(Collections.emptyList());

        AjaxResult result = controller.myPublishCouponList(null, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService, never()).require(any(), anyString());
        verify(couponService).updateExpiredCouponsByMemberId(PRINCIPAL_MEMBER_ID);
        ArgumentCaptor<SmsCoupon> query = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(couponService).selectSmsCouponList(query.capture());
        assertEquals(PRINCIPAL_MEMBER_ID, query.getValue().getMemberId());
        assertEquals(null, query.getValue().getScopeType());
        assertEquals(null, query.getValue().getMerchantId());
    }

    @Test
    void explicitMerchantPublishListWinsOverClubRoleForDualIdentity() {
        configureClubIdentity();
        when(couponService.selectSmsCouponList(any())).thenReturn(Collections.emptyList());

        AjaxResult result = controller.myPublishCouponList(null, null, "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.COUPON_MANAGE);
        verify(couponService).updateExpiredCouponsByMemberId(OWNER_MEMBER_ID);
        ArgumentCaptor<SmsCoupon> query = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(couponService).selectSmsCouponList(query.capture());
        assertEquals(OWNER_MEMBER_ID, query.getValue().getMemberId());
        assertEquals(1, query.getValue().getScopeType());
        assertEquals(MERCHANT_ROW_ID, query.getValue().getMerchantId());
    }

    @Test
    void explicitMerchantPublishWinsOverClubRoleForDualIdentity() {
        configureClubIdentity();
        CouponPublisDTO request = publishRequest();
        request.setScope("MERCHANT");
        when(quotaService.checkCreate(OWNER_MEMBER_ID, IQuotaService.TYPE_COUPON)).thenReturn(null);
        when(redisCache.setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(couponService.insertSmsCoupon(any())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Integer> action = invocation.getArgument(2);
            return action.get();
        }).when(quotaService).runWithCreateGuard(eq(OWNER_MEMBER_ID), eq(IQuotaService.TYPE_COUPON), any());

        AjaxResult result = controller.publish(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.COUPON_MANAGE);
        ArgumentCaptor<SmsCoupon> coupon = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(couponService).insertSmsCoupon(coupon.capture());
        assertEquals(OWNER_MEMBER_ID, coupon.getValue().getMemberId());
        assertEquals(1, coupon.getValue().getScopeType());
        assertEquals(MERCHANT_ROW_ID, coupon.getValue().getMerchantId());
    }

    @Test
    void explicitMerchantVerificationUsesEmployeeActorEvenWhenClubCapabilityIsDisabled() {
        configureClubIdentity();
        UserPermission permission = new UserPermission();
        permission.setCanRedeemCoupon(false);
        when(roleService.getPermission(any(UmsMember.class))).thenReturn(permission);
        SmsCouponHistory history = couponHistory(601L, 60L);
        when(couponService.verifyCouponQrToken("dual-token")).thenReturn(601L);
        when(historyService.selectSmsCouponHistoryById(601L)).thenReturn(history);
        when(couponService.selectSmsCouponById(60L)).thenReturn(coupon(60L, OWNER_MEMBER_ID));
        when(historyService.redeemCoupon(601L, OWNER_MEMBER_ID, MERCHANT_ROW_ID, PRINCIPAL_MEMBER_ID,
                MerchantRoleCode.MERCHANT_CHECKIN))
                .thenReturn(ISmsCouponHistoryService.RedemptionResult.REDEEMED);

        AjaxResult result = controller.couponVerification(null, "dual-token", "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.VERIFY);
        verify(historyService).redeemCoupon(601L, OWNER_MEMBER_ID, MERCHANT_ROW_ID, PRINCIPAL_MEMBER_ID,
                MerchantRoleCode.MERCHANT_CHECKIN);
        verify(roleService, never()).getPermission(any(UmsMember.class));
    }

    @Test
    void deniedDualIdentityMerchantVerificationStopsBeforeCouponLookup() {
        configureClubIdentity();
        when(merchantAccessService.require(PRINCIPAL_MEMBER_ID, MerchantPermission.VERIFY))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        ServiceException error = assertThrows(ServiceException.class,
                () -> controller.couponVerification(null, "dual-token", "MERCHANT"));

        assertEquals(403, error.getCode());
        verify(couponService, never()).verifyCouponQrToken(anyString());
        verify(historyService, never()).redeemCoupon(any(), any(), any(), any(), anyString());
    }

    @Test
    void deniedDualIdentityMerchantPublishListStopsBeforeCouponQuery() {
        configureClubIdentity();
        when(merchantAccessService.require(PRINCIPAL_MEMBER_ID, MerchantPermission.COUPON_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        ServiceException error = assertThrows(ServiceException.class,
                () -> controller.myPublishCouponList(null, null, "MERCHANT"));

        assertEquals(403, error.getCode());
        verify(couponService, never()).updateExpiredCouponsByMemberId(any());
        verify(couponService, never()).selectSmsCouponList(any());
    }

    @Test
    void deniedDualIdentityMerchantPublishStopsBeforeQuotaOrWrite() {
        configureClubIdentity();
        CouponPublisDTO request = publishRequest();
        request.setScope("MERCHANT");
        when(merchantAccessService.require(PRINCIPAL_MEMBER_ID, MerchantPermission.COUPON_MANAGE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        ServiceException error = assertThrows(ServiceException.class, () -> controller.publish(request));

        assertEquals(403, error.getCode());
        verify(quotaService, never()).checkCreate(any(), anyString());
        verify(redisCache, never()).setIfAbsent(anyString(), any(), any(), any());
        verify(couponService, never()).insertSmsCoupon(any());
    }

    @Test
    void unknownCouponScopeFailsClosedBeforeAnyReadOrWrite() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> controller.myPublishCouponList(null, null, "CLUB_OR_MERCHANT"));

        assertEquals(400, error.getCode());
        verify(couponService, never()).updateExpiredCouponsByMemberId(any());
        verify(merchantAccessService, never()).require(any(), anyString());
    }

    @Test
    void clubPublishKeepsQuotaLockAndCouponOnClubPrincipal() {
        configureClubIdentity();
        when(quotaService.checkCreate(PRINCIPAL_MEMBER_ID, IQuotaService.TYPE_COUPON)).thenReturn(null);
        when(redisCache.setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(couponService.insertSmsCoupon(any())).thenReturn(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<Integer> action = invocation.getArgument(2);
            return action.get();
        }).when(quotaService).runWithCreateGuard(eq(PRINCIPAL_MEMBER_ID), eq(IQuotaService.TYPE_COUPON), any());

        AjaxResult result = controller.publish(publishRequest());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService, never()).require(any(), anyString());
        verify(quotaService).checkCreate(PRINCIPAL_MEMBER_ID, IQuotaService.TYPE_COUPON);
        verify(quotaService).runWithCreateGuard(eq(PRINCIPAL_MEMBER_ID), eq(IQuotaService.TYPE_COUPON), any());
        ArgumentCaptor<SmsCoupon> coupon = ArgumentCaptor.forClass(SmsCoupon.class);
        verify(couponService).insertSmsCoupon(coupon.capture());
        assertEquals(PRINCIPAL_MEMBER_ID, coupon.getValue().getMemberId());
        assertEquals(null, coupon.getValue().getScopeType());
        assertEquals(null, coupon.getValue().getMerchantId());
    }

    @Test
    void clubWithoutRedeemCapabilityCannotRedeemItsOwnCoupon() {
        configureClubIdentity();
        UserPermission permission = new UserPermission();
        permission.setCanRedeemCoupon(false);
        when(roleService.getPermission(any(UmsMember.class))).thenReturn(permission);
        SmsCouponHistory history = couponHistory(501L, 50L);
        when(couponService.verifyCouponQrToken("club-token")).thenReturn(501L);
        when(historyService.selectSmsCouponHistoryById(501L)).thenReturn(history);
        when(couponService.selectSmsCouponById(50L)).thenReturn(coupon(50L, PRINCIPAL_MEMBER_ID));
        when(historyService.redeemCoupon(501L, PRINCIPAL_MEMBER_ID, null, PRINCIPAL_MEMBER_ID,
                IRoleService.ROLE_CLUB))
                .thenReturn(ISmsCouponHistoryService.RedemptionResult.REDEEMED);

        AjaxResult result = controller.couponVerification(null, "club-token", null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("当前身份不支持核销优惠券", result.get(AjaxResult.MSG_TAG));
        verify(merchantAccessService, never()).require(any(), anyString());
        verify(historyService, never()).redeemCoupon(any(), any(), any(), any(), anyString());
    }

    @Test
    void legacyClubWithRedeemCapabilityKeepsClubOwnerAndActor() {
        configureClubIdentity();
        UserPermission permission = new UserPermission();
        permission.setCanRedeemCoupon(true);
        when(roleService.getPermission(any(UmsMember.class))).thenReturn(permission);
        SmsCouponHistory history = couponHistory(502L, 51L);
        when(couponService.verifyCouponQrToken("club-allowed-token")).thenReturn(502L);
        when(historyService.selectSmsCouponHistoryById(502L)).thenReturn(history);
        when(couponService.selectSmsCouponById(51L)).thenReturn(coupon(51L, PRINCIPAL_MEMBER_ID));
        when(historyService.redeemCoupon(502L, PRINCIPAL_MEMBER_ID, null, PRINCIPAL_MEMBER_ID,
                IRoleService.ROLE_CLUB)).thenReturn(ISmsCouponHistoryService.RedemptionResult.REDEEMED);

        AjaxResult result = controller.couponVerification(null, "club-allowed-token", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService, never()).require(any(), anyString());
        verify(historyService).redeemCoupon(502L, PRINCIPAL_MEMBER_ID, null, PRINCIPAL_MEMBER_ID,
                IRoleService.ROLE_CLUB);
    }

    @Test
    void credentialAndStatusRejectAnotherPlayersHistory() {
        SmsCouponHistory history = couponHistory(301L, 30L);
        history.setMemberId(PRINCIPAL_MEMBER_ID + 1);
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
        org.junit.jupiter.api.Assertions.assertNotEquals(200, controller.qrToken(301L).get("code"));
        org.junit.jupiter.api.Assertions.assertNotEquals(200, controller.couponStatus(301L).get("code"));
        verify(couponService, never()).generateCouponQrToken(any());
    }

    @Test
    void expiredUnusedHistoryIsReportedExpiredEvenWithoutOpeningWalletFirst() {
        SmsCouponHistory history = couponHistory(301L, 30L);
        history.setMemberId(PRINCIPAL_MEMBER_ID);
        history.setEndTime(new java.util.Date(System.currentTimeMillis() - 60_000));
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
        when(couponService.generateCouponQrToken(301L)).thenReturn("current-token");
        when(qrcodeService.generateQrcode("current-token", 300, 300)).thenReturn("test-qr");
        java.util.Map<?, ?> qr = (java.util.Map<?, ?>) controller.qrToken(301L).get("data");
        java.util.Map<?, ?> state = (java.util.Map<?, ?>) controller.couponStatus(301L).get("data");
        assertEquals(2, qr.get("useStatus"));
        assertEquals(2, state.get("useStatus"));
    }

    @Test
    void revokedHistoryIsNotReportedAsAnUnusedCredential() {
        SmsCouponHistory history = couponHistory(301L, 30L);
        history.setMemberId(PRINCIPAL_MEMBER_ID);
        history.setDelFlag(1);
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
        org.junit.jupiter.api.Assertions.assertNotEquals(200, controller.qrToken(301L).get("code"));
        org.junit.jupiter.api.Assertions.assertNotEquals(200, controller.couponStatus(301L).get("code"));
    }

    @Test
    void schemaDeletedHistoryCannotIssueQrOrReportUnusedStatus() {
        SmsCouponHistory history = couponHistory(301L, 30L);
        history.setMemberId(PRINCIPAL_MEMBER_ID);
        history.setDelFlag(2);
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
        assertEquals(410, controller.qrToken(301L).get("code"));
        assertEquals(410, controller.couponStatus(301L).get("code"));
        verify(couponService, never()).generateCouponQrToken(any());
    }

    @Test
    void redeemedHistoryRemainsRedeemedAfterItsExpiryDate() {
        SmsCouponHistory history = couponHistory(301L, 30L);
        history.setMemberId(PRINCIPAL_MEMBER_ID);
        history.setUseStatus(1);
        history.setEndTime(new java.util.Date(System.currentTimeMillis() - 60_000));
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
        assertEquals(1, ((java.util.Map<?, ?>) controller.couponStatus(301L).get("data")).get("useStatus"));
    }

    @Test
    void staticCouponCodeIsNeverRedeemableEvenWhenALiveOwnedRecordMatches() {
        // F6:静态 coupon_code 是永久凭据(截图即可复用),不得绕过 60s 动态码时效。
        // 夹具故意把「能核销」的一切前提都备齐:记录存在、归属本店、服务层会返回 REDEEMED。
        when(couponService.verifyCouponQrToken("COU1726000000000ABCDEF")).thenReturn(null);
        SmsCouponHistory history = couponHistory(301L, 30L);
        when(historyService.selectSmsCouponHistoryList(any())).thenReturn(java.util.Collections.singletonList(history));
        when(couponService.selectSmsCouponById(30L)).thenReturn(coupon(30L, OWNER_MEMBER_ID));
        when(historyService.redeemCoupon(any(), any(), any(), any(), anyString()))
                .thenReturn(ISmsCouponHistoryService.RedemptionResult.REDEEMED);
        AjaxResult result = controller.couponVerification("COU1726000000000ABCDEF", null, "MERCHANT");
        assertEquals(500, result.get("code"));
        assertEquals("二维码已过期或无效，请让顾客刷新券码后重试", result.get("msg"));
        verify(historyService, never()).redeemCoupon(any(), any(), any(), any(), anyString());
    }

    @Test
    void dynamicTokenCarriedInCodeParameterStillRedeems() {
        // 负控:去掉静态回落不能误伤「扫码器把动态 token 塞进 code」这条正常路径。
        when(couponService.verifyCouponQrToken("dyn-in-code")).thenReturn(301L);
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(couponHistory(301L, 30L));
        when(couponService.selectSmsCouponById(30L)).thenReturn(coupon(30L, OWNER_MEMBER_ID));
        when(historyService.redeemCoupon(301L, OWNER_MEMBER_ID, MERCHANT_ROW_ID, PRINCIPAL_MEMBER_ID,
                MerchantRoleCode.MERCHANT_CHECKIN)).thenReturn(ISmsCouponHistoryService.RedemptionResult.REDEEMED);
        assertEquals(200, controller.couponVerification("dyn-in-code", null, "MERCHANT").get("code"));
    }

    @Test
    void expiredExplicitDynamicTokenNeverFallsBackToStaticRedemption() {
        when(couponService.verifyCouponQrToken("expired-token")).thenReturn(null);
        assertEquals(500, controller.couponVerification(null, "expired-token", "MERCHANT").get("code"));
        verify(historyService, never()).selectSmsCouponHistoryList(any());
        verify(historyService, never()).redeemCoupon(any(), any(), any(), any(), anyString());
    }

    @Test
    void stopIssuingTurnsOwnActiveCouponIntoStopped() {
        when(couponService.selectSmsCouponById(30L)).thenReturn(merchantCoupon(30L, 1));
        when(couponService.stopIssuingCoupon(30L, OWNER_MEMBER_ID)).thenReturn(1);

        AjaxResult result = controller.stopIssuing(30L, "MERCHANT");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService).require(PRINCIPAL_MEMBER_ID, MerchantPermission.COUPON_MANAGE);
        verify(couponService).stopIssuingCoupon(30L, OWNER_MEMBER_ID);
    }

    @Test
    void stopIssuingRefusesForeignOwnerOrWrongMerchantRow() {
        SmsCoupon foreign = merchantCoupon(30L, 1);
        foreign.setMemberId(OWNER_MEMBER_ID + 1);
        when(couponService.selectSmsCouponById(30L)).thenReturn(foreign);
        assertEquals("无权停发该优惠券", controller.stopIssuing(30L, "MERCHANT").get(AjaxResult.MSG_TAG));

        SmsCoupon otherRow = merchantCoupon(31L, 1);
        otherRow.setMerchantId(MERCHANT_ROW_ID + 1);
        when(couponService.selectSmsCouponById(31L)).thenReturn(otherRow);
        assertEquals("无权停发该优惠券", controller.stopIssuing(31L, "MERCHANT").get(AjaxResult.MSG_TAG));

        verify(couponService, never()).stopIssuingCoupon(any(), any());
    }

    @Test
    void stopIssuingRejectsInvalidatedStoppedAndEndedCoupons() {
        when(couponService.selectSmsCouponById(30L)).thenReturn(merchantCoupon(30L, 3));
        assertEquals("该优惠券已被平台手动失效", controller.stopIssuing(30L, "MERCHANT").get(AjaxResult.MSG_TAG));

        when(couponService.selectSmsCouponById(31L)).thenReturn(merchantCoupon(31L, 4));
        assertEquals("该优惠券已停发,无需重复操作", controller.stopIssuing(31L, "MERCHANT").get(AjaxResult.MSG_TAG));

        when(couponService.selectSmsCouponById(32L)).thenReturn(merchantCoupon(32L, 2));
        assertEquals("已结束的优惠券无法停发", controller.stopIssuing(32L, "MERCHANT").get(AjaxResult.MSG_TAG));

        verify(couponService, never()).stopIssuingCoupon(any(), any());
    }

    @Test
    void qrTokenProjectsManuallyInvalidatedCouponAsTerminalWithoutToken() {
        SmsCouponHistory history = couponHistory(301L, 30L);
        history.setMemberId(PRINCIPAL_MEMBER_ID);
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
        when(couponService.selectSmsCouponById(30L)).thenReturn(merchantCoupon(30L, 3));

        java.util.Map<?, ?> qr = (java.util.Map<?, ?>) controller.qrToken(301L).get("data");
        java.util.Map<?, ?> state = (java.util.Map<?, ?>) controller.couponStatus(301L).get("data");

        assertEquals(3, qr.get("useStatus"));
        assertEquals(3, state.get("useStatus"));
        assertFalse(qr.containsKey("token"));
        assertFalse(qr.containsKey("qrcodeUrl"));
        verify(couponService, never()).generateCouponQrToken(any());
    }

    @Test
    void usedCouponInitialReadKeepsSameTimeAsPollingWithoutIssuingToken() {
        SmsCouponHistory history = couponHistory(301L, 30L);
        history.setMemberId(PRINCIPAL_MEMBER_ID);
        history.setUseStatus(1);
        history.setUseTime(new Date(1790585466000L));
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
        when(couponService.selectSmsCouponById(30L)).thenReturn(merchantCoupon(30L, 1));

        java.util.Map<?, ?> qr = (java.util.Map<?, ?>) controller.qrToken(301L).get("data");
        java.util.Map<?, ?> state = (java.util.Map<?, ?>) controller.couponStatus(301L).get("data");

        assertEquals(1, qr.get("useStatus"));
        assertEquals(history.getUseTime(), qr.get("useTime"));
        assertEquals(state.get("useTime"), qr.get("useTime"));
        assertFalse(qr.containsKey("token"));
        assertFalse(qr.containsKey("qrcodeUrl"));
        verify(couponService, never()).generateCouponQrToken(any());
    }

    @Test
    void qrTokenKeepsMerchantStoppedCouponUsable() {
        // 商家停发(4)只停新增发放,已领券照常出码可核销。
        SmsCouponHistory history = couponHistory(301L, 30L);
        history.setMemberId(PRINCIPAL_MEMBER_ID);
        when(historyService.selectSmsCouponHistoryById(301L)).thenReturn(history);
        when(couponService.selectSmsCouponById(30L)).thenReturn(merchantCoupon(30L, 4));
        when(couponService.generateCouponQrToken(301L)).thenReturn("token-after-stop");
        when(qrcodeService.generateQrcode("token-after-stop", 300, 300)).thenReturn("qr-after-stop");

        java.util.Map<?, ?> qr = (java.util.Map<?, ?>) controller.qrToken(301L).get("data");

        assertEquals(0, qr.get("useStatus"));
        assertEquals("token-after-stop", qr.get("token"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void receivedListProjectsOnlyManuallyInvalidatedUnusedRows() {
        when(viewCouponHistoryService.selectViewCouponHistoryList(any())).thenReturn(java.util.Arrays.asList(
                viewRow(1L, 30L, 0L), viewRow(2L, 31L, 0L), viewRow(3L, 32L, 1L)));
        java.util.Map<Long, Integer> statuses = new java.util.HashMap<>();
        statuses.put(30L, 3);
        statuses.put(31L, 4);
        statuses.put(32L, 3);
        when(couponService.selectCouponStatuses(any())).thenReturn(statuses);

        AjaxResult result = controller.myPublishCouponList((String) null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        java.util.List<ViewCouponHistory> rows =
                (java.util.List<ViewCouponHistory>) result.get(AjaxResult.DATA_TAG);
        assertEquals(3L, rows.get(0).getUseStatus(), "手动失效未使用 → 展示为已失效");
        assertEquals(0L, rows.get(1).getUseStatus(), "商家停发不拦已领券 → 仍显示待使用");
        assertEquals(1L, rows.get(2).getUseStatus(), "已核销事实不因失效被改写");
    }

    private static SmsCoupon merchantCoupon(Long couponId, int status) {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(couponId);
        coupon.setMemberId(OWNER_MEMBER_ID);
        coupon.setScopeType(1);
        coupon.setMerchantId(MERCHANT_ROW_ID);
        coupon.setStatus(status);
        return coupon;
    }

    private static ViewCouponHistory viewRow(Long id, Long couponId, Long useStatus) {
        ViewCouponHistory row = new ViewCouponHistory();
        row.setId(id);
        row.setCouponId(couponId);
        row.setUseStatus(useStatus);
        return row;
    }

    private void configureClubIdentity() {
        UmsMember club = new UmsMember();
        club.setId(PRINCIPAL_MEMBER_ID);
        club.setRole(IRoleService.ROLE_CLUB);
        when(memberService.selectUmsMemberById(PRINCIPAL_MEMBER_ID)).thenReturn(club);
        when(roleService.resolveRole(club)).thenReturn(IRoleService.ROLE_CLUB);
    }

    private static MerchantAccessContext access(String roleCode) {
        return new MerchantAccessContext(PRINCIPAL_MEMBER_ID, MERCHANT_ROW_ID, OWNER_MEMBER_ID, roleCode);
    }

    private static CouponPublisDTO publishRequest() {
        CouponPublisDTO request = new CouponPublisDTO();
        request.setName("周末饮品券");
        request.setStartTime(new Date(1_000L));
        request.setEndTime(new Date(2_000L));
        request.setPublishCount(20L);
        request.setCouponType(2);
        request.setDescription("到店使用");
        return request;
    }

    private static SmsCouponHistory couponHistory(Long historyId, Long couponId) {
        SmsCouponHistory history = new SmsCouponHistory();
        history.setId(historyId);
        history.setCouponId(couponId);
        history.setUseStatus(0);
        return history;
    }

    private static SmsCoupon coupon(Long couponId, Long ownerMemberId) {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(couponId);
        coupon.setMemberId(ownerMemberId);
        return coupon;
    }

    private static final class LoggedInCouponController extends ApiCouponController {
        @Override
        public Long getAppUserId() {
            return PRINCIPAL_MEMBER_ID;
        }
    }
}
