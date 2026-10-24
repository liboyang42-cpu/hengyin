package com.chengyinhub.web.controller.api;

import com.chengyinhub.web.service.verify.CityNodeRedemptionApplication;

import com.chengyinhub.web.service.verify.RegistrationTicketApplication;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApiVerifyController} 动态核销码签发端点单测(BE-11)。
 * 覆盖归属/已支付/已核销门禁 + issue↔verify 往返一致(用真 {@link VerifyDynCodeUtil} 校验签发的码)。
 */
class ApiVerifyControllerTest {

    private static final String SECRET = "test-secret-城瘾-be11-ctrl";
    private static final Long ME = 1001L;
    private static final Long OWNER_MEMBER_ID = 9001L;
    private static final Long MERCHANT_ROW_ID = 11L;

    private ICmsRegistrationService registrationService;
    private com.chengyinhub.business.service.ICmsActivityService activityService;
    private RoamMapper roamMapper;
    private IMerchantAccessService merchantAccessService;
    private BusinessAuditEventMapper auditMapper;
    private QrcodeServiceImpl qrcodeService;
    private TestableApiVerifyController controller;
    private CityNodeRedemptionApplication cityNodeRedemptionApplication;

    @BeforeEach
    void setUp() {
        registrationService = mock(ICmsRegistrationService.class);
        activityService = mock(com.chengyinhub.business.service.ICmsActivityService.class);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setDelFlag(0);
        when(activityService.selectCmsActivityById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(activity);
        roamMapper = mock(RoamMapper.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        ISmsCouponHistoryService couponHistoryService = mock(ISmsCouponHistoryService.class);
        when(couponHistoryService.grantCouponById(7001L, 88L)).thenReturn(true);
        when(roamMapper.selectPendingCityNodeCouponId(51L, 7001L)).thenReturn(88L);
        qrcodeService = mock(QrcodeServiceImpl.class);
        when(qrcodeService.generateInlineQrcode(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("https://oss/qr.png");
        controller = new TestableApiVerifyController(ME);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        when(merchantAccessService.require(ME, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(ME, MERCHANT_ROW_ID,
                        OWNER_MEMBER_ID, MerchantRoleCode.MERCHANT_CHECKIN));
        // [P0-14] ③ 出码判据:verification_status 对 ③ 是「被核销过」不是「核销完」,
        //         还有欠的章节就得继续出码。本类测的是 ①(无 entitlement)⇒ 返 false ⇒ 走原逻辑。
        // [P0-14] 出码判据 = isTicketFullyConsumed(不能裸判 verification_status —— ③ 首次核销就置位)。
        // 本类测的是 ①(无 entitlement)⇒ 该方法退化成 verification_status==1。
        com.chengyinhub.business.service.IPerkLedgerService perkSvc =
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IPerkLedgerService.class);
        org.mockito.Mockito.when(perkSvc.isTicketFullyConsumed(org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        ReflectionTestUtils.setField(controller, "registrationTicketApplication", new RegistrationTicketApplication(
                registrationService, mock(com.chengyinhub.business.service.RegistrationSeatService.class),
                activityService, perkSvc, qrcodeService, SECRET));
        cityNodeRedemptionApplication = new CityNodeRedemptionApplication(
                merchantAccessService, roamMapper, couponHistoryService, auditMapper, qrcodeService, SECRET);
        ReflectionTestUtils.setField(controller, "cityNodeRedemptionApplication", cityNodeRedemptionApplication);
    }

    @Test
    void playerAndCityNodeCodesRenderSignedPngWithoutObjectStorage() throws Exception {
        when(registrationService.selectCmsRegistrationById(9L)).thenReturn(reg(9L, ME, 2, 0, 2L));
        CityNodeVO poi = cityNode(MERCHANT_ROW_ID);
        when(roamMapper.selectMerchantNodeDetail(51L)).thenReturn(poi);
        when(roamMapper.selectPlayerMerchantNodeDetail(51L, 0L)).thenReturn(poi);
        when(qrcodeService.generateQrcode(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("object storage unavailable"));
        when(qrcodeService.generateInlineQrcode(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> new QrcodeServiceImpl().generateInlineQrcode(
                        call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        for (AjaxResult result : new AjaxResult[] { controller.issueDynCode(9L), controller.issueCityNodeCode(51L) }) {
            assertEquals(200, result.get(AjaxResult.CODE_TAG));
            Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
            String png = (String) data.get("qrcodeUrl");
            assertNotNull(png);
            assertTrue(png.startsWith("data:image/png;base64,"));
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(
                    java.util.Base64.getDecoder().decode(png.substring(png.indexOf(',') + 1))));
            assertEquals(300, image.getWidth());
            assertEquals(300, image.getHeight());
            String decoded = new com.google.zxing.MultiFormatReader().decode(new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(
                            new com.google.zxing.RGBLuminanceSource(image.getWidth(), image.getHeight(),
                                    image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))))).getText();
            assertEquals(data.get("code"), decoded);
            assertTrue(VerifyDynCodeUtil.verify(decoded, System.currentTimeMillis(), SECRET).valid);
        }
        verify(qrcodeService, never()).generateQrcode(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void rejects_not_logged_in() {
        TestableApiVerifyController anon = new TestableApiVerifyController(0L);
        ReflectionTestUtils.setField(anon, "registrationTicketApplication", new RegistrationTicketApplication(
                registrationService, mock(com.chengyinhub.business.service.RegistrationSeatService.class), activityService,
                mock(com.chengyinhub.business.service.IPerkLedgerService.class), qrcodeService, SECRET));
        AjaxResult r = anon.issueDynCode(1L);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void rejects_missing_param() {
        AjaxResult r = controller.issueDynCode(null);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("参数缺失", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void rejects_registration_not_found() {
        when(registrationService.selectCmsRegistrationById(9L)).thenReturn(null);
        AjaxResult r = controller.issueDynCode(9L);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("报名单不存在", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void rejects_other_members_registration() {
        when(registrationService.selectCmsRegistrationById(9L)).thenReturn(reg(9L, 2002L, 2, 0, 2L));
        AjaxResult r = controller.issueDynCode(9L);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("无权操作该报名单", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void rejects_unpaid_registration() {
        // registrationStatus=1(待支付) => 拒
        when(registrationService.selectCmsRegistrationById(9L)).thenReturn(reg(9L, ME, 1, 0, 2L));
        AjaxResult r = controller.issueDynCode(9L);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("订单未完成支付或已结束,无法生成核销码", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void rejects_already_verified() {
        when(registrationService.selectCmsRegistrationById(9L)).thenReturn(reg(9L, ME, 2, 1, 2L));
        AjaxResult r = controller.issueDynCode(9L);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals("该票已核销", r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void issues_code_that_round_trips() {
        when(registrationService.selectCmsRegistrationById(9L)).thenReturn(reg(9L, ME, 2, 0, 2L));
        AjaxResult r = controller.issueDynCode(9L);
        assertEquals(200, r.get(AjaxResult.CODE_TAG));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
        assertNotNull(data);
        String code = (String) data.get("code");
        assertEquals("activity", data.get("type"));
        assertEquals("https://oss/qr.png", data.get("qrcodeUrl"));

        // 用真 util 以相同密钥校验签发的码,应还原出 regId=9
        VerifyDynCodeUtil.Result v = VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), SECRET);
        assertTrue(v.valid);
        assertEquals(Long.valueOf(9L), v.regId);
        assertEquals("activity", v.type);
    }

    @Test
    void cancelledActivityCannotIssueCodeEvenAfterManualCaseCloses() {
        when(registrationService.selectCmsRegistrationById(9L)).thenReturn(reg(9L, ME, 2, 0, 2L));
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setCancelTime(new java.util.Date());
        when(activityService.selectCmsActivityById(org.mockito.ArgumentMatchers.any())).thenReturn(activity);
        assertEquals("活动已取消，无法生成核销码", controller.issueDynCode(9L).get("msg"));
    }

    @Test
    void offlinedActivityStillIssuesSoldTicketCode() {
        when(registrationService.selectCmsRegistrationById(9L)).thenReturn(reg(9L, ME, 2, 0, 2L));
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setDelFlag(0);
        activity.setStatus(0L);
        activity.setPublishStatus(0L);
        when(activityService.selectCmsActivityById(org.mockito.ArgumentMatchers.any())).thenReturn(activity);
        assertEquals(200, controller.issueDynCode(9L).get("code"));
    }

    @Test
    void topic_owner_type_maps_to_topic() {
        when(registrationService.selectCmsRegistrationById(7L)).thenReturn(reg(7L, ME, 2, 0, 1L));
        AjaxResult r = controller.issueDynCode(7L);
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
        assertEquals("topic", data.get("type"));
    }

    @Test
    void cityNodeRedeemUsesMerchantRowIdInsteadOfPrincipalOrOwnerMemberId() {
        CityNodeVO poi = cityNode(MERCHANT_ROW_ID);
        when(roamMapper.selectMerchantNodeDetail(51L)).thenReturn(poi);
        when(roamMapper.redeemCityNodeReward(51L, 7001L)).thenReturn(1);

        AjaxResult result = controller.redeemCityNodeCode(cityNodeCode(51L, 7001L));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(merchantAccessService).require(ME, MerchantPermission.VERIFY);
        verify(roamMapper).redeemCityNodeReward(51L, 7001L);
    }

    @Test
    void offlineCityNodeCanRenewCodeOnlyForOwnPendingCheckin() {
        when(roamMapper.selectPendingCityNodeCouponId(51L, ME)).thenReturn(null);
        CityNodeVO poi = cityNode(MERCHANT_ROW_ID);
        poi.setStatus(0);
        when(roamMapper.selectMerchantNodeDetail(51L)).thenReturn(poi);
        assertEquals(500, controller.issueCityNodeCode(51L).get(AjaxResult.CODE_TAG));
        when(roamMapper.selectPendingCityNodeCouponId(51L, ME)).thenReturn(88L);
        assertEquals(200, controller.issueCityNodeCode(51L).get(AjaxResult.CODE_TAG));
    }

    @Test
    void cityNodeRedeemUsesCheckinCouponAfterTemplateChanges() {
        CityNodeVO poi = cityNode(MERCHANT_ROW_ID);
        poi.setStatus(0);
        poi.setCouponId(99L);
        when(roamMapper.selectMerchantNodeDetail(51L)).thenReturn(poi);
        when(roamMapper.redeemCityNodeReward(51L, 7001L)).thenReturn(1);
        assertEquals(200, controller.redeemCityNodeCode(cityNodeCode(51L, 7001L)).get(AjaxResult.CODE_TAG));
        ISmsCouponHistoryService service = (ISmsCouponHistoryService) ReflectionTestUtils.getField(cityNodeRedemptionApplication, "couponHistoryService");
        verify(service).grantCouponById(7001L, 88L);
        verify(service, never()).grantCouponById(7001L, 99L);
    }

    @Test
    void disabledCityNodeCouponStillRedeemsButTellsMerchantNothingWasGranted() {
        // C-30:据点券被平台手动失效 → 核销照常(翻转+审计),不发券,不报「库存不足」
        when(roamMapper.selectMerchantNodeDetail(51L)).thenReturn(cityNode(MERCHANT_ROW_ID));
        when(roamMapper.redeemCityNodeReward(51L, 7001L)).thenReturn(1);
        ISmsCouponHistoryService service = (ISmsCouponHistoryService) ReflectionTestUtils.getField(cityNodeRedemptionApplication, "couponHistoryService");
        when(service.isCouponNotIssuable(88L)).thenReturn(true);

        AjaxResult result = controller.redeemCityNodeCode(cityNodeCode(51L, 7001L));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("核销成功,该券已失效或停发,未发放", result.get(AjaxResult.MSG_TAG));
        assertEquals(false, ((Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("couponGranted"));
        verify(roamMapper).redeemCityNodeReward(51L, 7001L);
        verify(auditMapper).insertBusinessAuditEvent(any());
        verify(service, never()).grantCouponById(7001L, 88L);
    }

    @Test
    void cityNodeEmployeeRedeemKeepsOwnerAndRecordsActualActor() {
        when(merchantAccessService.require(ME, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(ME, MERCHANT_ROW_ID,
                        OWNER_MEMBER_ID, MerchantRoleCode.MERCHANT_CHECKIN));
        when(roamMapper.selectMerchantNodeDetail(51L)).thenReturn(cityNode(MERCHANT_ROW_ID));
        when(roamMapper.redeemCityNodeReward(51L, 7001L)).thenReturn(1);

        AjaxResult result = controller.redeemCityNodeCode(cityNodeCode(51L, 7001L));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("CITY_NODE", audit.getValue().getDomainType());
        assertEquals(51L, audit.getValue().getDomainId());
        assertEquals(OWNER_MEMBER_ID, audit.getValue().getOwnerMemberId());
        assertEquals(ME, audit.getValue().getActorMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_CHECKIN, audit.getValue().getActorRoleCode());
        assertEquals("CITY_NODE_REDEEM", audit.getValue().getAction());
        assertEquals(7001L, audit.getValue().getRelationId());
    }

    @Test
    void cityNodeAuditFailureFailsTheTransactionalRedeem() {
        when(roamMapper.selectMerchantNodeDetail(51L)).thenReturn(cityNode(MERCHANT_ROW_ID));
        when(roamMapper.redeemCityNodeReward(51L, 7001L)).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> controller.redeemCityNodeCode(cityNodeCode(51L, 7001L)));

        assertEquals("据点核销审计写入失败", error.getMessage());
    }

    @Test
    void cityNodeIdCollisionWithPrincipalMustNotAuthorizeAnotherMerchant() {
        CityNodeVO poi = cityNode(ME);
        when(roamMapper.selectMerchantNodeDetail(52L)).thenReturn(poi);

        AjaxResult result = controller.redeemCityNodeCode(cityNodeCode(52L, 7002L));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("无权核销该据点", result.get(AjaxResult.MSG_TAG));
        verify(roamMapper, never()).redeemCityNodeReward(52L, 7002L);
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void cityNodeAnonymousRedeemFailsBeforeAccessOrAudit() {
        TestableApiVerifyController anonymous = new TestableApiVerifyController(0L);
        ReflectionTestUtils.setField(anonymous, "cityNodeRedemptionApplication", cityNodeRedemptionApplication);

        AjaxResult result = anonymous.redeemCityNodeCode(cityNodeCode(51L, 7001L));

        assertEquals("请先登录", result.get(AjaxResult.MSG_TAG));
        verify(merchantAccessService, never()).require(any(), any());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    private static CityNodeVO cityNode(Long merchantId) {
        CityNodeVO poi = new CityNodeVO();
        poi.setPoiId(51L);
        poi.setMerchantId(merchantId);
        poi.setStatus(1);
        return poi;
    }

    private static String cityNodeCode(Long poiId, Long playerId) {
        return VerifyDynCodeUtil.issue(poiId, "citynode_" + playerId,
                System.currentTimeMillis(), 60_000L, 7L, SECRET);
    }

    private static CmsRegistration reg(Long id, Long memberId, Integer regStatus, Integer verifyStatus, Long ownerType) {
        CmsRegistration reg = new CmsRegistration();
        reg.setId(id);
        reg.setMemberId(memberId);
        reg.setRegistrationStatus(regStatus);
        reg.setPaymentStatus(regStatus == 2 ? 2 : 0);
        reg.setVerificationStatus(verifyStatus);
        reg.setOwnerType(ownerType);
        return reg;
    }

    private static class TestableApiVerifyController extends ApiVerifyController {
        private final Long appUserId;

        private TestableApiVerifyController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            return appUserId;
        }
    }
}
