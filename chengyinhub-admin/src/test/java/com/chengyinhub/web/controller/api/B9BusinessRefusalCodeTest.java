package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.MerchantRegistrationDTO;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewRegistrationService;
import com.chengyinhub.business.service.RegistrationSeatService;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.business.service.support.UgcSubmissionSafety;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import com.chengyinhub.framework.web.service.SmsVerificationService;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.web.service.registration.RegistrationRedemptionApplication;
import com.chengyinhub.web.service.verify.RegistrationDynCodeLedger;
import com.chengyinhub.web.service.verify.RegistrationTicketApplication;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance-r3 B9: business refusals on auth/ownership/not-found/conflict paths return 4xx business codes
 * instead of 500, while real unexpected server errors retain 500 semantics.
 */
class B9BusinessRefusalCodeTest {

    private static final String DYN_SECRET = "b9-refusal-code-test-secret";

    private static void assertRefusal(AjaxResult r, int expectedCode, String expectedMsg) {
        assertEquals(Integer.valueOf(expectedCode), r.get(AjaxResult.CODE_TAG), String.valueOf(r));
        assertEquals(expectedMsg, r.get(AjaxResult.MSG_TAG), String.valueOf(r));
    }

    @Test
    void dyncodeIssueRefusalsReturn4xxAndPreserveMessages() {
        ICmsRegistrationService orders = mock(ICmsRegistrationService.class);
        RegistrationSeatService seats = mock(RegistrationSeatService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        IPerkLedgerService perks = mock(IPerkLedgerService.class);
        RegistrationTicketApplication app = new RegistrationTicketApplication(
                orders, seats, activities, perks, mock(QrcodeServiceImpl.class),
                DYN_SECRET, mock(RegistrationDynCodeLedger.class));

        assertRefusal(app.issueDynCode(9001L, null, null), HttpStatus.BAD_REQUEST, "参数缺失");
        assertRefusal(app.issueDynCode(9001L, 404L, null), HttpStatus.NOT_FOUND, "报名单不存在");

        CmsRegistration ownedBy9001 = new CmsRegistration();
        ownedBy9001.setId(31L);
        ownedBy9001.setMemberId(9001L);
        ownedBy9001.setOwnerType(2L);
        ownedBy9001.setOwnerId(61L);
        ownedBy9001.setRegistrationStatus(2);
        ownedBy9001.setPaymentStatus(2);
        ownedBy9001.setOrderNum(1L);
        when(orders.selectCmsRegistrationById(31L)).thenReturn(ownedBy9001);

        assertRefusal(app.issueDynCode(9004L, 31L, null), HttpStatus.FORBIDDEN, "无权操作该报名单");

        ownedBy9001.setPaymentStatus(1);
        assertRefusal(app.issueDynCode(9001L, 31L, null), HttpStatus.CONFLICT, "订单未完成支付或已结束,无法生成核销码");

        ownedBy9001.setPaymentStatus(2);
        when(perks.isTicketFullyConsumed(any(), eq(31L))).thenReturn(true);
        assertRefusal(app.issueDynCode(9001L, 31L, null), HttpStatus.CONFLICT, "该票已核销");
    }

    @Test
    void scanDynamicCodeRefusalsReturn4xxWhileServerErrorsStay500() {
        ApiRegistrationController controller = spy(new ApiRegistrationController());
        doReturn(9002L).when(controller).getAppUserId();
        RegistrationRedemptionApplication app = RegistrationRedemptionTestFixtures.application(controller);
        ICmsRegistrationService regSvc = mock(ICmsRegistrationService.class);
        ICmsActivityService actSvc = mock(ICmsActivityService.class);
        IMerchantAccessService accessSvc = mock(IMerchantAccessService.class);
        ReflectionTestUtils.setField(app, "dynSecret", DYN_SECRET);
        ReflectionTestUtils.setField(app, "registrationService", regSvc);
        ReflectionTestUtils.setField(app, "cmsActivityService", actSvc);
        ReflectionTestUtils.setField(app, "merchantAccessService", accessSvc);
        ReflectionTestUtils.setField(app, "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(app, "growthService", mock(IGrowthService.class));

        CmsRegistration reg = new CmsRegistration();
        reg.setId(31L);
        reg.setMemberId(9001L);
        reg.setOwnerType(2L);
        reg.setOwnerId(61L);
        reg.setPaymentStatus(2);
        reg.setOrderNum(1L);
        reg.setPurchaseKind(PurchaseKind.GUIDED_TICKET);
        when(regSvc.selectCmsRegistrationById(31L)).thenReturn(reg);

        CmsActivity act = new CmsActivity();
        act.setId(61L);
        act.setMemberId(9003L);
        when(actSvc.selectCmsActivityById(61L)).thenReturn(act);

        when(accessSvc.require(9002L, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(9002L, 8001L, 9002L, "MERCHANT_OWNER"));

        String validCode = VerifyDynCodeUtil.issue(31L, "activity", System.currentTimeMillis(), 60_000L, 1L, DYN_SECRET);

        // Non-owner scan_dynamic_code -> 403 + notRedeemed: true
        AjaxResult forbidden = controller.scanDynamicCode(validCode);
        assertRefusal(forbidden, HttpStatus.FORBIDDEN, "该店无权核销本场玩家票；俱乐部带队请扫描领队团码");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) forbidden.get(AjaxResult.DATA_TAG)).get("notRedeemed"));

        // Owner rescan -> 409 + alreadyVerified: true
        doReturn(9003L).when(controller).getAppUserId();
        when(regSvc.verifyAndSettle(reg, 9003L, 9003L, null)).thenReturn(false);
        AjaxResult rescan = controller.scanDynamicCode(validCode);
        assertRefusal(rescan, HttpStatus.CONFLICT, "该订单已核销");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) rescan.get(AjaxResult.DATA_TAG)).get("alreadyVerified"));

        // Invalid / expired code -> 400 / 409
        assertRefusal(controller.scanDynamicCode(""), HttpStatus.BAD_REQUEST, "核销码无效");
        String expired = VerifyDynCodeUtil.issue(31L, "activity", System.currentTimeMillis() - 120_000L, 60_000L, 2L, DYN_SECRET);
        assertRefusal(controller.scanDynamicCode(expired), HttpStatus.CONFLICT, "已过期");

        // Real unexpected failure inside settlement -> stays 500
        when(regSvc.verifyAndSettle(reg, 9003L, 9003L, null)).thenThrow(new RuntimeException("db connection lost"));
        AjaxResult serverErr = controller.scanDynamicCode(validCode);
        assertEquals(Integer.valueOf(HttpStatus.ERROR), serverErr.get(AjaxResult.CODE_TAG));
    }

    @Test
    void couponVerificationForeignAndStateRefusalsReturn4xx() {
        ApiCouponController controller = spy(new ApiCouponController());
        doReturn(9005L).when(controller).getAppUserId();
        ISmsCouponService couponSvc = mock(ISmsCouponService.class);
        ISmsCouponHistoryService historySvc = mock(ISmsCouponHistoryService.class);
        IMerchantAccessService accessSvc = mock(IMerchantAccessService.class);
        ReflectionTestUtils.setField(controller, "smsCouponService", couponSvc);
        ReflectionTestUtils.setField(controller, "smsCouponHistoryService", historySvc);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessSvc);
        when(accessSvc.require(9005L, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(9005L, 8002L, 9005L, "MERCHANT_OWNER"));

        assertRefusal(controller.couponVerification("", null, "MERCHANT"), HttpStatus.BAD_REQUEST, "请输入核销码");

        when(couponSvc.verifyCouponQrToken("bad-tok")).thenReturn(null);
        assertRefusal(controller.couponVerification("bad-tok", null, "MERCHANT"), HttpStatus.BAD_REQUEST, "二维码已过期或无效，请让顾客刷新券码后重试");

        when(couponSvc.verifyCouponQrToken("missing-tok")).thenReturn(999L);
        assertRefusal(controller.couponVerification("missing-tok", null, "MERCHANT"), HttpStatus.NOT_FOUND, "该优惠券不存在");

        when(couponSvc.verifyCouponQrToken("tok")).thenReturn(301L);
        SmsCouponHistory hist = new SmsCouponHistory();
        hist.setId(301L);
        hist.setCouponId(30L);
        when(historySvc.selectSmsCouponHistoryById(301L)).thenReturn(hist);
        SmsCoupon otherMerchantCoupon = new SmsCoupon();
        otherMerchantCoupon.setId(30L);
        otherMerchantCoupon.setMemberId(9002L);
        when(couponSvc.selectSmsCouponById(30L)).thenReturn(otherMerchantCoupon);

        assertRefusal(controller.couponVerification("tok", null, "MERCHANT"), HttpStatus.FORBIDDEN, "无权核销该优惠券");

        otherMerchantCoupon.setMemberId(9005L);
        when(historySvc.redeemCoupon(301L, 9005L, 8002L, 9005L, "MERCHANT_OWNER"))
                .thenReturn(ISmsCouponHistoryService.RedemptionResult.EXPIRED);
        assertRefusal(controller.couponVerification("tok", null, "MERCHANT"), HttpStatus.CONFLICT, "该优惠券已过期");
    }

    @Test
    void activityInfoUnknownAndCancelledRefusalsReturn404And409() {
        ApiActivityController controller = spy(new ApiActivityController());
        doReturn(9004L).when(controller).getAppUserId();
        ICmsActivityService activities = mock(ICmsActivityService.class);
        IViewRegistrationService views = mock(IViewRegistrationService.class);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activities);
        ReflectionTestUtils.setField(controller, "viewRegistrationService", views);

        assertRefusal(controller.activityInfo("0"), HttpStatus.BAD_REQUEST, "参数不合规");
        assertRefusal(controller.activityInfo("1"), HttpStatus.NOT_FOUND, "活动不存在");

        CmsActivity cancelled = new CmsActivity();
        cancelled.setId(99L);
        cancelled.setMemberId(9004L);
        cancelled.setCancelTime(new Date());
        cancelled.setDelFlag(0);
        when(activities.selectCmsActivityById(99L)).thenReturn(cancelled);
        when(views.selectRegistrationSummaries(any())).thenReturn(Collections.emptyList());

        AjaxResult cancelRes = controller.activityInfo("99");
        assertRefusal(cancelRes, HttpStatus.CONFLICT, "活动已取消，请在订单中查看退款或售后进度");
        assertEquals("ACTIVITY_CANCELLED", cancelRes.get("errorCode"));
    }

    @Test
    void activityPublishRefusalsReturn4xxWhileServerErrorsStay500() {
        ApiActivityController controller = spy(new ApiActivityController());
        doReturn(9004L).when(controller).getAppUserId();
        IUmsMemberService members = mock(IUmsMemberService.class);
        IRoleService roles = mock(IRoleService.class);
        IQuotaService quotas = mock(IQuotaService.class);
        UmsMember player = new UmsMember();
        player.setId(9004L);
        when(members.selectUmsMemberById(9004L)).thenReturn(player);
        when(roles.resolveRole(player)).thenReturn(IRoleService.ROLE_PLAYER);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "roleService", roles);
        ReflectionTestUtils.setField(controller, "quotaService", quotas);

        assertRefusal(controller.publish(new CmsActivityPublishRequest()), HttpStatus.FORBIDDEN, "仅俱乐部主理人或商家可发布活动");

        when(roles.resolveRole(player)).thenReturn(IRoleService.ROLE_CLUB);
        when(quotas.checkCreate(9004L, IQuotaService.TYPE_EVENT)).thenReturn("活动配额已满");
        assertRefusal(controller.publish(new CmsActivityPublishRequest()), HttpStatus.CONFLICT, "活动配额已满");

        when(quotas.checkCreate(9004L, IQuotaService.TYPE_EVENT)).thenThrow(new RuntimeException("unexpected db error"));
        AjaxResult serverErr = controller.publish(new CmsActivityPublishRequest());
        assertRefusal(serverErr, HttpStatus.ERROR, "发布活动失败，请稍后重试");
    }

    @Test
    void loginPhoneRefusalsReturn400And403AndPreserveHttp200Semantics() throws Exception {
        ApiLoginController controller = new ApiLoginController();
        SmsVerificationService sms = mock(SmsVerificationService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        ReflectionTestUtils.setField(controller, "smsVerificationService", sms);
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        MockHttpServletRequest req = new MockHttpServletRequest();

        assertRefusal(controller.loginPhone("bad", "123456", req), HttpStatus.BAD_REQUEST, "手机号格式不正确");
        assertRefusal(controller.loginPhone("13800138000", "", req), HttpStatus.BAD_REQUEST, "请输入验证码");

        when(sms.verify(eq("13800138000"), eq("000000"), anyString())).thenReturn(false);
        assertRefusal(controller.loginPhone("13800138000", "000000", req), HttpStatus.BAD_REQUEST, "验证码错误或已过期");

        when(sms.verify(eq("13800138000"), eq("123456"), anyString())).thenReturn(true);
        UmsMember frozen = new UmsMember();
        frozen.setId(1L);
        frozen.setStatus(2L);
        when(members.selectUmsMemberList(any())).thenReturn(Collections.singletonList(frozen));
        assertRefusal(controller.loginPhone("13800138000", "123456", req), HttpStatus.FORBIDDEN, "账号已被冻结");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(post("/api/login/phone")
                        .param("phone", "13800138000")
                        .param("code", "000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("验证码错误或已过期"));
    }

    @Test
    void roamFinishTwiceAndMissingSessionReturn409And404() {
        ApiRoamController controller = spy(new ApiRoamController());
        doReturn(9004L).when(controller).getAppUserId();
        RoamMapper mapper = mock(RoamMapper.class);
        ReflectionTestUtils.setField(controller, "roamMapper", mapper);

        assertRefusal(controller.finish("999", "", 100), HttpStatus.NOT_FOUND, "会话不存在");

        RoamSession settled = new RoamSession();
        settled.setId(10L);
        settled.setMemberId(9004L);
        settled.setStatus(1);
        when(mapper.selectOwnedSessionForUpdate(10L, 9004L)).thenReturn(settled);
        assertRefusal(controller.finish("10", "", 100), HttpStatus.CONFLICT, "本次漫游已结算");

        RoamSession open = new RoamSession();
        open.setId(11L);
        open.setMemberId(9004L);
        open.setStatus(0);
        open.setStartedAt(new Date());
        when(mapper.selectOwnedSessionForUpdate(11L, 9004L)).thenReturn(open);
        when(mapper.selectFoundPoisBySession(11L)).thenReturn(Collections.emptyList());
        when(mapper.updateSessionFinish(any())).thenReturn(0);
        assertRefusal(controller.finish("11", "", 100), HttpStatus.CONFLICT, "本次漫游已结算");
    }

    @Test
    void merchantRegistrationBadIdentityReturns400() {
        ApiMerchantController controller = spy(new ApiMerchantController());
        doReturn(990014L).when(controller).getAppUserId();
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.lockMemberById(990014L)).thenReturn(990014L);
        MerchantOperatorMapper operators = mock(MerchantOperatorMapper.class);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MemberIdentityService identityService = new MemberIdentityService();
        ReflectionTestUtils.setField(controller, "submissionSafety", mock(UgcSubmissionSafety.class));
        ReflectionTestUtils.setField(controller, "umsMemberMapper", members);
        ReflectionTestUtils.setField(controller, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "memberIdentityService", identityService);

        MerchantRegistrationDTO badCheckDigit = new MerchantRegistrationDTO();
        badCheckDigit.setName("测试店");
        badCheckDigit.setRealName("测试");
        badCheckDigit.setIdCardNumber("999999199001010017");
        badCheckDigit.setIdentityConsent(true);

        assertRefusal(controller.merchantRegistration(badCheckDigit), HttpStatus.BAD_REQUEST, MemberIdentityService.MSG_ID_CARD_INVALID);

        MerchantOperator active = new MerchantOperator();
        active.setId(1L);
        when(operators.selectActiveByMemberId(990014L)).thenReturn(active);
        assertRefusal(controller.merchantRegistration(badCheckDigit), HttpStatus.CONFLICT, "您已加入商家经营团队，不能再申请自己的商家");
    }
}
