package com.chengyinhub.web.service.verify;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import com.chengyinhub.web.controller.api.ApiVerifyController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Spring proxy/DataSourceTransactionManager, with a mock JDBC connection. This proves
 * transaction wiring/commit/rollback calls, not SQL rollback, stock conservation or MySQL locking. */
class CityNodeRedemptionApplicationTest {
    private static final long ACTOR = 1001L;
    private static final String SECRET = "city-application-fixture-signing-value";
    private IMerchantAccessService access;
    private RoamMapper roam;
    private ISmsCouponHistoryService coupons;
    private BusinessAuditEventMapper audit;
    private DataSource source;
    private Connection connection;
    private DataSourceTransactionManager transactions;
    private CityNodeRedemptionApplication application;
    private ApiVerifyController controller;
    private final List<String> stages = new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        stages.clear(); access = mock(IMerchantAccessService.class); roam = mock(RoamMapper.class);
        coupons = mock(ISmsCouponHistoryService.class); audit = mock(BusinessAuditEventMapper.class);
        source = mock(DataSource.class); connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        transactions = new DataSourceTransactionManager(source);
        CityNodeRedemptionApplication target = new CityNodeRedemptionApplication(access, roam, coupons, audit, mock(com.chengyinhub.business.service.impl.QrcodeServiceImpl.class), SECRET);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        application = (CityNodeRedemptionApplication) proxy.getProxy();
        controller = new ApiVerifyController() { @Override public Long getAppUserId() { return ACTOR; } };
        ReflectionTestUtils.setField(controller, "cityNodeRedemptionApplication", application);
        when(access.require(ACTOR, MerchantPermission.VERIFY)).thenAnswer(call -> stage("permission",
                new MerchantAccessContext(ACTOR, 11L, 9001L, MerchantRoleCode.MERCHANT_CHECKIN)));
        when(roam.lockClaimOwner(7001L)).thenAnswer(call -> stage("claim", 7001L));
        CityNodeVO poi = new CityNodeVO(); poi.setPoiId(51L); poi.setMerchantId(11L);
        when(roam.selectMerchantNodeDetail(51L)).thenAnswer(call -> stage("detail", poi));
        when(roam.selectPendingCityNodeCouponId(51L, 7001L)).thenAnswer(call -> stage("pending", 88L));
        when(roam.redeemCityNodeReward(51L, 7001L)).thenAnswer(call -> stage("cas", 1));
        when(coupons.isCouponNotIssuable(88L)).thenAnswer(call -> stage("coupon-state", false));
        when(coupons.grantCouponById(7001L, 88L)).thenAnswer(call -> stage("grant", true));
        when(audit.insertBusinessAuditEvent(any())).thenAnswer(call -> stage("audit", 1));
    }
    private <T> T stage(String name, T result) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), name);
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly(), name);
        Object holder = TransactionSynchronizationManager.getResource(source); assertNotNull(holder, name);
        assertSame(connection, ((ConnectionHolder) holder).getConnection(), name);
        stages.add(name); return result;
    }
    private String code() { return VerifyDynCodeUtil.issue(51L, "citynode_7001", System.currentTimeMillis(), 300000L, 7L, SECRET); }
    @Test void issuanceUsesTheSameApplicationWithoutOpeningATransaction() throws Exception {
        assertNull(CityNodeRedemptionApplication.class.getMethod("issueCityNodeCode", long.class, Long.class)
                .getAnnotation(Transactional.class));
        CityNodeVO poi = new CityNodeVO(); poi.setPoiId(51L); poi.setMerchantId(11L);
        doReturn(poi).when(roam).selectMerchantNodeDetail(51L);
        doReturn(poi).when(roam).selectPlayerMerchantNodeDetail(51L, 0L);
        com.chengyinhub.business.service.impl.QrcodeServiceImpl qr =
                (com.chengyinhub.business.service.impl.QrcodeServiceImpl) ReflectionTestUtils.getField(
                        org.springframework.test.util.AopTestUtils.<Object>getTargetObject(application), "qrcodeService");
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(300, (int) call.getArgument(1)); assertEquals(300, (int) call.getArgument(2));
            return "data:image/png;base64,fixture";
        }).when(qr).generateInlineQrcode(anyString(), anyInt(), anyInt());
        AjaxResult response = controller.issueCityNodeCode(51L);
        assertEquals(200, response.get("code")); Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(300000L, data.get("ttlMs")); assertEquals(51L, data.get("poiId"));
        VerifyDynCodeUtil.Result signed = VerifyDynCodeUtil.verify((String) data.get("code"), System.currentTimeMillis(), SECRET);
        assertTrue(signed.valid); assertEquals(51L, signed.regId); assertEquals("citynode_" + ACTOR, signed.type);
        verifyNoInteractions(source, connection, access, coupons, audit);
        verify(roam, never()).lockClaimOwner(anyLong()); verify(roam, never()).redeemCityNodeReward(anyLong(), anyLong());
    }
    @Test void controllerCallsTheApplicationProxyAndAllStagesShareOneWritableTransaction() throws Exception {
        assertFalse(AopUtils.isAopProxy(controller)); assertTrue(AopUtils.isAopProxy(application));
        assertNull(ApiVerifyController.class.getMethod("redeemCityNodeCode", String.class).getAnnotation(Transactional.class));
        TransactionAttribute attribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                CityNodeRedemptionApplication.class.getMethod("redeemCityNodeCode", long.class, String.class), CityNodeRedemptionApplication.class);
        assertNotNull(attribute); assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, attribute.getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_DEFAULT, attribute.getIsolationLevel()); assertFalse(attribute.isReadOnly());
        assertTrue(attribute.rollbackOn(new Exception("checked rollback contract")));
        AjaxResult response = controller.redeemCityNodeCode(code());
        assertEquals(200, response.get("code")); assertEquals("核销成功,券已发放玩家", response.get("msg"));
        assertEquals(Map.of("memberId", 7001L, "poiId", 51L, "couponId", 88L, "couponGranted", true), response.get("data"));
        assertEquals(List.of("permission", "claim", "detail", "pending", "cas", "coupon-state", "grant", "audit"), stages);
        verify(source, times(1)).getConnection(); verify(connection).commit(); verify(connection, never()).rollback();
    }
    @Test void stockRefusalThrowsAndRollsBackInsteadOfReturningAfterTheCas() throws Exception {
        doAnswer(call -> stage("grant", false)).when(coupons).grantCouponById(7001L, 88L);
        ServiceException refusal = assertThrowsExactly(ServiceException.class, () -> controller.redeemCityNodeCode(code()));
        assertEquals("券库存不足,核销失败", refusal.getMessage());
        verify(connection).rollback(); verify(connection, never()).commit(); verifyNoInteractions(audit);
        assertEquals("grant", stages.get(stages.size() - 1));
    }
    @Test void auditFailureAfterCouponGrantRollsBackTheSameTransaction() throws Exception {
        doAnswer(call -> stage("audit", 0)).when(audit).insertBusinessAuditEvent(any());
        IllegalStateException failure = assertThrowsExactly(IllegalStateException.class, () -> controller.redeemCityNodeCode(code()));
        assertEquals("据点核销审计写入失败", failure.getMessage());
        verify(coupons).grantCouponById(7001L, 88L); verify(connection).rollback(); verify(connection, never()).commit();
        assertEquals(List.of("permission", "claim", "detail", "pending", "cas", "coupon-state", "grant", "audit"), stages);
    }
    @Test void duplicateCasDoesNotGrantOrAuditAndKeepsTheOriginalRefusal() throws Exception {
        doAnswer(call -> stage("cas", 0)).when(roam).redeemCityNodeReward(51L, 7001L);
        AjaxResult refusal = controller.redeemCityNodeCode(code());
        assertEquals(500, refusal.get("code")); assertEquals("玩家未到店打卡,或该券已核销", refusal.get("msg"));
        verifyNoInteractions(coupons, audit); verify(connection).commit(); verify(connection, never()).rollback();
    }
    @Test void permissionIsRecheckedInsideTheTransactionBeforeSignatureOrClaimMutation() throws Exception {
        doAnswer(call -> { stage("permission", 0); throw new ServiceException("商家身份无效或权限不足", 403); })
                .when(access).require(ACTOR, MerchantPermission.VERIFY);
        ServiceException refusal = assertThrowsExactly(ServiceException.class, () -> controller.redeemCityNodeCode("not-a-code"));
        assertEquals(Integer.valueOf(403), refusal.getCode()); assertEquals(List.of("permission"), stages);
        verifyNoInteractions(roam, coupons, audit); verify(connection).rollback(); verify(connection, never()).commit();
    }
    @Test void requiredPropagationJoinsAnExistingTransactionWithoutAnInnerCommit() throws Exception {
        AjaxResult result = new TransactionTemplate(transactions).execute(status -> {
            AjaxResult response = controller.redeemCityNodeCode(code());
            try { verify(connection, never()).commit(); } catch (java.sql.SQLException failed) { throw new AssertionError(failed); }
            return response;
        });
        assertNotNull(result); assertEquals(200, result.get("code"));
        verify(source, times(1)).getConnection(); verify(connection).commit(); verify(connection, never()).rollback();
    }
    @Test void disabledCouponStillCommitsTheClaimAndAuditButPreservesTheNoGrantResponse() throws Exception {
        doAnswer(call -> stage("coupon-state", true)).when(coupons).isCouponNotIssuable(88L);
        AjaxResult response = controller.redeemCityNodeCode(code());
        assertEquals(200, response.get("code")); assertEquals("核销成功,该券已失效或停发,未发放", response.get("msg"));
        assertEquals(false, ((Map<?, ?>) response.get("data")).get("couponGranted"));
        verify(coupons, never()).grantCouponById(anyLong(), anyLong()); verify(audit).insertBusinessAuditEvent(any());
        verify(connection).commit(); verify(connection, never()).rollback();
    }
    @Test void anonymousApplicationCallRetainsTheOriginalFirstGateWithoutPermissionOrMutation() throws Exception {
        AjaxResult response = application.redeemCityNodeCode(0L, code());
        assertEquals(500, response.get("code")); assertEquals("请先登录", response.get("msg"));
        verifyNoInteractions(access, roam, coupons, audit); verify(connection).commit(); verify(connection, never()).rollback();
    }
    private com.chengyinhub.business.domain.BusinessAuditEvent original(String afterJson) {
        com.chengyinhub.business.domain.BusinessAuditEvent row = new com.chengyinhub.business.domain.BusinessAuditEvent();
        row.setDomainType("CITY_NODE"); row.setDomainId(51L); row.setOwnerMemberId(9001L);
        row.setAction("CITY_NODE_REDEEM"); row.setTargetType("ROAM_CHECKIN"); row.setTargetId(51L);
        row.setRelationId(7001L); row.setRequestId("citynode-redeem-51-7001"); row.setAfterJson(afterJson);
        return row;
    }
    @Test void originalResultCommitsWithAuditAndReadsBackAfterCodeExpiryWithoutCurrentCouponRules() throws Exception {
        java.util.concurrent.atomic.AtomicReference<com.chengyinhub.business.domain.BusinessAuditEvent> saved = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(call -> { saved.set(call.getArgument(0)); return stage("audit", 1); }).when(audit).insertBusinessAuditEvent(any());
        doAnswer(call -> stage("receipt", saved.get())).when(audit).selectByDomainActionRequestForUpdate(
                "CITY_NODE", 51L, "CITY_NODE_REDEEM", "citynode-redeem-51-7001");
        AjaxResult first = controller.redeemCityNodeCode(code());
        assertNotNull(saved.get());
        com.alibaba.fastjson2.JSONObject frozen = com.alibaba.fastjson2.JSON.parseObject(saved.get().getAfterJson());
        assertEquals(Integer.valueOf(1), frozen.getInteger("status")); assertEquals(Integer.valueOf(1), frozen.getInteger("receiptVersion"));
        assertEquals(first.get("msg"), frozen.getString("message"));
        assertEquals(Long.valueOf(88L), frozen.getJSONObject("data").getLong("couponId"));
        assertTrue(frozen.getJSONObject("data").getBoolean("couponGranted"));
        doReturn(true).when(coupons).isCouponNotIssuable(88L);
        String expired = VerifyDynCodeUtil.issue(51L, "citynode_7001", 1L, 1L, 99L, SECRET);
        assertFalse(VerifyDynCodeUtil.verify(expired, System.currentTimeMillis(), SECRET).valid);
        stages.clear(); AjaxResult replay = controller.cityNodeReceipt(expired);
        assertEquals(first, replay); assertEquals(List.of("permission", "claim", "detail", "receipt"), stages);
        verify(roam, times(1)).redeemCityNodeReward(51L, 7001L); verify(coupons, times(1)).grantCouponById(7001L, 88L);
        verify(coupons, times(1)).isCouponNotIssuable(88L); verify(audit, times(1)).insertBusinessAuditEvent(any());
        verify(connection, times(2)).commit(); verify(connection, never()).rollback();
    }
    @Test void receiptCannotTurnLegacyOrMissingOrCorruptFactsIntoAnAck() throws Exception {
        for (String json : new String[] { null, "{\"status\":1}", "not-json", "{\"status\":1,\"receiptVersion\":1,\"data\":[]}" }) {
            doReturn(original(json)).when(audit).selectByDomainActionRequestForUpdate(
                    "CITY_NODE", 51L, "CITY_NODE_REDEEM", "citynode-redeem-51-7001");
            AjaxResult response = controller.cityNodeReceipt(code());
            assertEquals(520, response.get("code")); assertEquals("原据点核销结果待确认，请勿重复核销", response.get("msg"));
            assertNull(response.get("data"));
        }
        doReturn(null).when(audit).selectByDomainActionRequestForUpdate("CITY_NODE", 51L, "CITY_NODE_REDEEM", "citynode-redeem-51-7001");
        assertEquals(520, controller.cityNodeReceipt(code()).get("code"));
        verifyNoInteractions(coupons); verify(audit, never()).insertBusinessAuditEvent(any());
        verify(roam, never()).selectPendingCityNodeCouponId(anyLong(), anyLong());
        verify(roam, never()).redeemCityNodeReward(anyLong(), anyLong());
    }
    @Test void receiptRechecksCurrentPermissionAndPoiOwnershipBeforeAccessingAnyOriginalFact() throws Exception {
        CityNodeVO otherShop = new CityNodeVO(); otherShop.setMerchantId(12L);
        doReturn(otherShop).when(roam).selectMerchantNodeDetail(51L);
        AjaxResult forbidden = controller.cityNodeReceipt(code());
        assertEquals(500, forbidden.get("code")); assertEquals("无权核销该据点", forbidden.get("msg"));
        verifyNoInteractions(audit, coupons);
        doAnswer(call -> { stage("permission", 0); throw new ServiceException("商家身份无效或权限不足", 403); })
                .when(access).require(ACTOR, MerchantPermission.VERIFY);
        ServiceException revoked = assertThrowsExactly(ServiceException.class, () -> controller.cityNodeReceipt(code()));
        assertEquals(Integer.valueOf(403), revoked.getCode());
        verify(roam, times(1)).lockClaimOwner(7001L); verifyNoInteractions(audit, coupons);
    }
    @Test void receiptSignatureOrOriginalScopeMismatchCannotReadAnotherPlayerOrOwner() throws Exception {
        assertEquals(500, controller.cityNodeReceipt(code() + "tampered").get("code"));
        verifyNoInteractions(roam, audit, coupons);
        com.chengyinhub.business.domain.BusinessAuditEvent wrongPlayer = original("{\"status\":1}");
        wrongPlayer.setRelationId(7002L);
        doReturn(wrongPlayer).when(audit).selectByDomainActionRequestForUpdate("CITY_NODE", 51L, "CITY_NODE_REDEEM", "citynode-redeem-51-7001");
        assertEquals(520, controller.cityNodeReceipt(code()).get("code"));
        wrongPlayer.setRelationId(7001L); wrongPlayer.setOwnerMemberId(9002L);
        assertEquals(520, controller.cityNodeReceipt(code()).get("code"));
        verifyNoInteractions(coupons); verify(roam, never()).redeemCityNodeReward(anyLong(), anyLong());
    }
    @Test void receiptDatabaseFailureIsNotConvertedToAbsenceOrPermissionAndRollsBack() throws Exception {
        doAnswer(call -> { stage("receipt", 0); throw new IllegalStateException("fixture storage failure"); })
                .when(audit).selectByDomainActionRequestForUpdate("CITY_NODE", 51L, "CITY_NODE_REDEEM", "citynode-redeem-51-7001");
        IllegalStateException failed = assertThrowsExactly(IllegalStateException.class, () -> controller.cityNodeReceipt(code()));
        assertEquals("fixture storage failure", failed.getMessage()); verify(connection).rollback(); verify(connection, never()).commit();
        verifyNoInteractions(coupons); verify(audit, never()).insertBusinessAuditEvent(any());
    }
    @Test void terminalReadDoesNotRelaxExpiryForNewUseOrPermitAnyNewMutation() throws Exception {
        String expired = VerifyDynCodeUtil.issue(51L, "citynode_7001", 1L, 1L, 1L, SECRET);
        AjaxResult refusal = controller.redeemCityNodeCode(expired);
        assertEquals(500, refusal.get("code")); assertEquals("已过期", refusal.get("msg"));
        verifyNoInteractions(roam, coupons, audit);
        AjaxResult unknown = controller.cityNodeReceipt(expired); assertEquals(520, unknown.get("code"));
        verifyNoInteractions(coupons); verify(roam, never()).redeemCityNodeReward(anyLong(), anyLong());
        verify(audit, never()).insertBusinessAuditEvent(any());
    }

    @Test void storedSkippedDecisionIsAnOriginalResultEvenAfterCouponBecomesIssuableAgain() {
        doReturn(true).when(coupons).isCouponNotIssuable(88L);
        org.mockito.ArgumentCaptor<com.chengyinhub.business.domain.BusinessAuditEvent> captured =
                org.mockito.ArgumentCaptor.forClass(com.chengyinhub.business.domain.BusinessAuditEvent.class);
        AjaxResult first = controller.redeemCityNodeCode(code());
        assertEquals(false, ((Map<?, ?>) first.get("data")).get("couponGranted"));
        verify(audit).insertBusinessAuditEvent(captured.capture());
        doReturn(captured.getValue()).when(audit).selectByDomainActionRequestForUpdate(
                "CITY_NODE", 51L, "CITY_NODE_REDEEM", "citynode-redeem-51-7001");
        doReturn(false).when(coupons).isCouponNotIssuable(88L);
        assertEquals(first, controller.cityNodeReceipt(code()));
        verify(coupons, times(1)).isCouponNotIssuable(88L); verify(coupons, never()).grantCouponById(anyLong(), anyLong());
        verify(audit, times(1)).insertBusinessAuditEvent(any()); verify(roam, times(1)).redeemCityNodeReward(51L, 7001L);
    }
    @Test void typedFrozenIdentityDecisionAndMessageMustAllAgreeBeforeAnyAck() {
        for (Map<String, Object> invalidData : List.of(
                Map.<String, Object>of("poiId", 52L, "memberId", 7001L, "couponId", 88L, "couponGranted", true),
                Map.<String, Object>of("poiId", 51L, "memberId", 7002L, "couponId", 88L, "couponGranted", true),
                Map.<String, Object>of("poiId", 51L, "memberId", 7001L, "couponId", -1L, "couponGranted", true),
                Map.<String, Object>of("poiId", 51L, "memberId", 7001L, "couponId", 88L, "couponGranted", "true"),
                Map.<String, Object>of("poiId", 51L, "memberId", 7001L, "couponId", 88L, "couponGranted", false))) {
            String json = com.alibaba.fastjson2.JSON.toJSONString(Map.of("status", 1, "receiptVersion", 1,
                    "data", invalidData, "message", "核销成功,券已发放玩家"));
            doReturn(original(json)).when(audit).selectByDomainActionRequestForUpdate(
                    "CITY_NODE", 51L, "CITY_NODE_REDEEM", "citynode-redeem-51-7001");
            assertEquals(520, controller.cityNodeReceipt(code()).get("code"));
        }
        verifyNoInteractions(coupons); verify(audit, never()).insertBusinessAuditEvent(any());
        verify(roam, never()).redeemCityNodeReward(anyLong(), anyLong());
    }

}
