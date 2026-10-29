package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.CoopGroupMemberVerify;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.mapper.CoopGroupMemberVerifyMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-21 核销收口:条件置位 + 入账 + 台账,同一事务。
 *
 * <p><b>为什么必须是一个事务</b>(评审用真实推演抓出来的,初版把这条搞错了):核销是主题结算
 * **唯一**被写入的时刻 —— {@code SettlementEntryServiceImpl.recordOnPayment} 对 ownerType=1 直接
 * 返 0(「主题报名的执行商家在核销时才由商家池确定,支付阶段不入账」)。若条件置位先提交、入账再抛,
 * 商家看到「核销失败」去重扫 → 置位返 0 行 → 「该订单已核销」⇒ **这笔钱商家永远收不到,且没有任何
 * 重试路径**(对账任务只比总额,发现不了「一条明细都没有」)。
 * 改之前的「无脑覆盖写」反而没这个坑:重扫会把整条幂等尾巴重跑一遍并收敛 ——
 * 等于初版用「可重试」换了「防并发」。现在两个都要。
 *
 * ⚠️ 负控已实测:
 *  - 去掉 @Transactional ⇒ isTransactional 红
 *  - 入账/台账挪到 CAS **之前** ⇒ casComesFirst 红
 *  - CAS 返 0 时不 return false ⇒ alreadyVerified_noSideEffects 红
 *  - 台账探针去掉 setRegistrationType(1) ⇒ ledgerProbeCarriesType1 红
 */
class RegistrationVerifyAndSettleTest {

    private static final Long REG = 5001L;
    private static final Long MERCHANT = 77L;
    private static final Long PAYEE = 88L;

    private CmsRegistrationMapper regMapper;
    private com.chengyinhub.business.mapper.CmsActivityMapper activityMapper;
    private CmsRegistrationMerchantMapper registrationMerchantMapper;
    private CmsRegistrationScanMapper scanMapper;
    private CoopGroupMemberVerifyMapper groupMemberVerifyMapper;
    private ISettlementEntryService settlementSvc;
    private BusinessAuditEventMapper auditMapper;
    private com.chengyinhub.business.mapper.CmsTopicMapper topicMapper;
    private ICoopSettlementClaimService fulfillmentClaims;
    private final java.util.concurrent.atomic.AtomicLong auditIds = new java.util.concurrent.atomic.AtomicLong(100);
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        regMapper = mock(CmsRegistrationMapper.class);
        activityMapper = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        registrationMerchantMapper = mock(CmsRegistrationMerchantMapper.class);
        scanMapper = mock(CmsRegistrationScanMapper.class);
        groupMemberVerifyMapper = mock(CoopGroupMemberVerifyMapper.class);
        settlementSvc = mock(ISettlementEntryService.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        topicMapper = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        fulfillmentClaims = mock(ICoopSettlementClaimService.class);
        when(fulfillmentClaims.recordConfirmedStationFulfillment(anyLong())).thenReturn(java.util.Optional.empty());
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(300L);
        when(topicMapper.selectCmsTopicByIdForUpdate(300L)).thenReturn(topic);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenAnswer(call -> reg());

        service = new CmsRegistrationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "growthService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IGrowthService.class));
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "coopSettlementClaimService", fulfillmentClaims);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMerchantMapper", registrationMerchantMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationScanMapper", scanMapper);
        ReflectionTestUtils.setField(service, "coopGroupMemberVerifyMapper", groupMemberVerifyMapper);
        ReflectionTestUtils.setField(service, "settlementEntryService", settlementSvc);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        // [赏金] verifyAndSettle 末尾会调 onVerification;本用例不管赏金,给个哑 mock 即可。
        // 赏金自身的判定见 MerchantReferralBountyTest。
        ReflectionTestUtils.setField(service, "merchantReferralBountyService",
                mock(com.chengyinhub.business.service.impl.MerchantReferralBountyService.class));

        when(scanMapper.selectCmsRegistrationScanList(any()))
                .thenReturn(Collections.<CmsRegistrationScan>emptyList());
        when(auditMapper.insertBusinessAuditEvent(any())).thenAnswer(call -> {
            BusinessAuditEvent event = call.getArgument(0);
            event.setId(auditIds.incrementAndGet());
            return 1;
        });
    }

    private CmsRegistration reg() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(1L);
        r.setOwnerId(300L);
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setVerificationStatus(0);
        return r;
    }

    private boolean verifyAndSettle() {
        return service.verifyAndSettle(reg(), MERCHANT, PAYEE, 1);
    }

    @Test
    void cancelledActivityRejectsBeforeVerificationAndSettlement() {
        CmsRegistration row = reg();
        row.setOwnerType(2L);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setCancelTime(new java.util.Date());
        when(activityMapper.selectCmsActivityByIdForUpdate(300L)).thenReturn(activity);
        assertEquals("活动已取消，无法核销", assertThrows(IllegalStateException.class,
                () -> service.verifyAndSettle(row, MERCHANT, PAYEE, 1)).getMessage());
        verify(regMapper, never()).verifyIfNotVerified(anyLong(), anyLong());
        verify(settlementSvc, never()).recordPaidRegistration(any(), any());
        verify(scanMapper, never()).insertCmsRegistrationScan(any());
    }

    @Test
    void offlinedActivityStillFulfilsSoldTicketAndLocksBeforeRegistration() {
        CmsRegistration row = reg();
        row.setOwnerType(2L);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setDelFlag(0);
        activity.setStatus(0L);
        activity.setPublishStatus(0L);
        when(activityMapper.selectCmsActivityByIdForUpdate(300L)).thenReturn(activity);
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);
        assertTrue(service.verifyAndSettle(row, MERCHANT, PAYEE, 1));
        InOrder order = inOrder(activityMapper, regMapper);
        order.verify(activityMapper).selectCmsActivityByIdForUpdate(300L);
        order.verify(regMapper).verifyIfNotVerified(REG, MERCHANT);
    }

    @Test
    void missingOrDeletedActivityCannotBeRedeemed() {
        CmsRegistration row = reg();
        row.setOwnerType(2L);
        com.chengyinhub.business.domain.CmsActivity deleted = new com.chengyinhub.business.domain.CmsActivity();
        deleted.setDelFlag(2);
        for (com.chengyinhub.business.domain.CmsActivity activity : new com.chengyinhub.business.domain.CmsActivity[]{null, deleted}) {
            when(activityMapper.selectCmsActivityByIdForUpdate(300L)).thenReturn(activity);
            assertEquals("活动不存在", assertThrows(IllegalStateException.class,
                    () -> service.verifyAndSettle(row, MERCHANT, PAYEE, 1)).getMessage());
        }
        verify(regMapper, never()).verifyIfNotVerified(anyLong(), anyLong());
    }

    /**
     * **这条是本类的心脏**:方法必须真的挂着 @Transactional,否则 CAS 会先提交,
     * 下游一失败这笔核销就永远补不回来。
     */
    @Test
    void isTransactional() throws Exception {
        Method m = CmsRegistrationServiceImpl.class.getMethod("verifyAndSettle",
                CmsRegistration.class, Long.class, Long.class, Integer.class);
        org.springframework.transaction.annotation.Transactional tx =
                m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(tx, "verifyAndSettle 必须 @Transactional —— 没有事务,CAS 先提交、入账再抛 = "
                + "商家重扫被「已核销」挡回,钱永远收不到");
        assertTrue(tx.rollbackFor().length > 0 && tx.rollbackFor()[0] == Exception.class,
                "必须 rollbackFor = Exception.class:默认只对 RuntimeException 回滚,"
                        + "受检异常会让 CAS 留在已提交态");
    }

    /** 顺序:CAS → 入账 → 台账。CAS 必须第一个,它是并发的序列化点。 */
    @Test
    void casComesFirst() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);

        assertTrue(verifyAndSettle());

        InOrder o = inOrder(regMapper, settlementSvc, scanMapper);
        o.verify(regMapper).verifyIfNotVerified(REG, MERCHANT);
        o.verify(settlementSvc).recordPaidRegistration(any(), eq(PAYEE));
        o.verify(scanMapper).insertCmsRegistrationScan(any());
    }

    /** CAS 返 0 后锁内读回真实已核销态 ⇒ 返 false,且**入账/台账一步都不能走**。 */
    @Test
    void alreadyVerified_noSideEffects() {
        CmsRegistration verified = reg();
        verified.setVerificationStatus(1);
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(0);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(verified);

        assertFalse(verifyAndSettle());

        verify(regMapper).selectCmsRegistrationByIdForUpdate(REG);
        verify(settlementSvc, never()).recordPaidRegistration(any(), anyLong());
        verify(scanMapper, never()).insertCmsRegistrationScan(any());
    }

    /** 退款或取消赢得 CAS 时必须返回既有业务错误，不能伪报「已核销」。 */
    @Test
    void refundOrCancellationWinsCas_reportsRefundedInsteadOfVerified() {
        CmsRegistration refunded = reg();
        refunded.setPaymentStatus(4);
        refunded.setRegistrationStatus(3);
        refunded.setVerificationStatus(1);
        CmsRegistration cancelled = reg();
        cancelled.setRegistrationStatus(3);
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(0);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(refunded, cancelled);

        for (int attempt = 0; attempt < 2; attempt++) {
            IllegalStateException error = assertThrows(IllegalStateException.class, this::verifyAndSettle);
            assertEquals("订单未完成支付或已退款,无法核销", error.getMessage());
        }

        verify(settlementSvc, never()).recordPaidRegistration(any(), anyLong());
        verify(scanMapper, never()).insertCmsRegistrationScan(any());
    }

    /** 人工退款冻结语义优先于终态解释，CAS 返 0 后仍先报告冻结。 */
    @Test
    void activeManualRefundFreeze_keepsPriorityOverTerminalReadback() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(0);
        when(regMapper.hasActiveManualRefundCaseForUpdate(REG)).thenReturn(true);

        IllegalStateException error = assertThrows(IllegalStateException.class, this::verifyAndSettle);

        assertEquals("订单正在人工退款处理中，暂不可核销", error.getMessage());
        verify(regMapper, never()).selectCmsRegistrationByIdForUpdate(anyLong());
        verify(settlementSvc, never()).recordPaidRegistration(any(), anyLong());
    }

    /** CAS 返 0 却缺失或仍未核销时 fail-closed，不能把未知状态授权成重复核销。 */
    @Test
    void casLostWithoutKnownTerminalState_failsClosed() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(0);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(null, reg());

        for (int attempt = 0; attempt < 2; attempt++) {
            IllegalStateException error = assertThrows(IllegalStateException.class, this::verifyAndSettle);
            assertEquals("订单数据异常,无法核销", error.getMessage());
        }

        verify(settlementSvc, never()).recordPaidRegistration(any(), anyLong());
        verify(scanMapper, never()).insertCmsRegistrationScan(any());
    }

    /** 入账抛异常 ⇒ 异常必须往外传(让事务回滚),不能被吞成 false。 */
    @Test
    void settlementFailure_propagatesSoTransactionRollsBack() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);
        doThrow(new RuntimeException("结算库超时"))
                .when(settlementSvc).recordPaidRegistration(any(), anyLong());

        RuntimeException e = assertThrows(RuntimeException.class, this::verifyAndSettle);
        assertTrue(e.getMessage().contains("结算库超时"),
                "异常必须原样往外抛 —— 吞掉就没人回滚 CAS,商家重扫会被挡回且钱收不到");
    }

    /** 入账的收款方用调用方给的 payeeId,不是核销人(topic 与 activity 的收款方口径不同)。 */
    @Test
    void settlesToGivenPayee() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);

        verifyAndSettle();

        verify(settlementSvc).recordPaidRegistration(any(), eq(PAYEE));
    }

    @Test
    void employeeVerificationKeepsOwnerForBusinessAndPersistsActualActor() {
        Long employee = 7002L;
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);

        assertTrue(service.verifyAndSettle(reg(), MERCHANT, PAYEE, 1,
                employee, "MERCHANT_CHECKIN"));

        verify(regMapper).verifyIfNotVerified(REG, MERCHANT);
        verify(settlementSvc).recordPaidRegistration(any(), eq(PAYEE));
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("REGISTRATION", audit.getValue().getDomainType());
        assertEquals(REG, audit.getValue().getDomainId());
        assertEquals(MERCHANT, audit.getValue().getOwnerMemberId());
        assertEquals(employee, audit.getValue().getActorMemberId());
        assertEquals("MERCHANT_CHECKIN", audit.getValue().getActorRoleCode());
        assertEquals("REGISTRATION_REDEEM", audit.getValue().getAction());
        assertEquals("CMS_REGISTRATION", audit.getValue().getTargetType());
        assertEquals(REG, audit.getValue().getTargetId());
    }

    @Test
    void auditWriteFailurePropagatesFromTransactionalVerification() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);
        org.mockito.Mockito.doReturn(0).when(auditMapper).insertBusinessAuditEvent(any());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.verifyAndSettle(reg(), MERCHANT, PAYEE, 1,
                        7002L, "MERCHANT_CHECKIN"));

        assertEquals("核销审计写入失败", error.getMessage());
    }

    /**
     * [V3] 台账探针必须带 registration_type=1。不带会命中报名创建时按节点模板插的 type=0 行
     * (member_id = 模板主,常即正主商家)⇒ 误判「台账已有」⇒ 核销行永远不插。
     */
    @Test
    void ledgerProbeCarriesType1() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);

        verifyAndSettle();

        ArgumentCaptor<CmsRegistrationScan> probe = ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanMapper).selectCmsRegistrationScanList(probe.capture());
        assertEquals(Integer.valueOf(1), probe.getValue().getRegistrationType(),
                "探针不带 type=1 会命中模板主的 type=0 行 ⇒ 核销台账永远缺行");
        assertEquals(MERCHANT, probe.getValue().getMemberId());
    }

    /** 插进去的台账行是 type=1 且带核销方式。 */
    @Test
    void insertsLedgerRowAsType1() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);

        verifyAndSettle();

        ArgumentCaptor<CmsRegistrationScan> ins = ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanMapper).insertCmsRegistrationScan(ins.capture());
        assertEquals(Integer.valueOf(1), ins.getValue().getRegistrationType());
        assertEquals(MERCHANT, ins.getValue().getMemberId());
        assertEquals(Integer.valueOf(1), ins.getValue().getValidationMethod());
    }

    /** 台账已有本商家的 type=1 行 ⇒ 不重复插,但核销仍算成功(台账是幂等补写不是核销结果)。 */
    @Test
    void existingLedgerRow_noDuplicateInsertButStillSucceeds() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);
        CmsRegistrationScan existing = new CmsRegistrationScan();
        existing.setRegistrationType(1);
        when(scanMapper.selectCmsRegistrationScanList(any()))
                .thenReturn(Collections.singletonList(existing));

        assertTrue(verifyAndSettle());

        verify(scanMapper, never()).insertCmsRegistrationScan(any());
    }

    /** 带队核销除普通核销台账外，必须同事务写入 activity 事实；少这一行就会在结算时跨场次串算。 */
    @Test
    void groupActivityVerification_persistsExactActivityFact() {
        Long activityId = 400L;
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);
        when(groupMemberVerifyMapper.insertCoopGroupMemberVerify(any())).thenReturn(1);

        assertTrue(service.verifyAndSettleForGroupActivity(reg(), MERCHANT, activityId));

        ArgumentCaptor<CoopGroupMemberVerify> cap = ArgumentCaptor.forClass(CoopGroupMemberVerify.class);
        verify(groupMemberVerifyMapper).insertCoopGroupMemberVerify(cap.capture());
        assertEquals(Long.valueOf(300L), cap.getValue().getTopicId());
        assertEquals(activityId, cap.getValue().getActivityId());
        assertEquals(REG, cap.getValue().getRegistrationId());
        assertEquals(MERCHANT, cap.getValue().getVerifierMemberId());
    }

    private CmsRegistrationMerchant station(Long memberId, Long topicId, Integer validationMethod) {
        CmsRegistrationMerchant station = new CmsRegistrationMerchant();
        station.setId(701L);
        station.setMemberId(memberId);
        station.setTopicId(topicId);
        station.setAuditStatus(1L);
        station.setDelFlag(0);
        station.setValidationMethod(validationMethod);
        station.setAddressName("西岸书店");
        station.setAddress("滨江路 18 号");
        station.setTemplateId(8802L);
        return station;
    }

    /** 所选记录不是当前商家自己的,必须在 CAS/入账/台账之前被归属闸拦下。 */
    @Test
    void selectedStationOwnedByAnotherMerchant_rejectedBeforeVerification() {
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(reg());
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(999L, 300L, 3));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.verifyAndSettleForStation(reg(), MERCHANT, 701L));

        assertEquals("所选据点不属于当前商家或路线", e.getMessage(),
                "必须命中 memberId/topicId 归属闸,不能用通用异常掩盖越权");
        verify(regMapper, never()).verifyIfNotVerified(anyLong(), anyLong());
        verify(settlementSvc, never()).recordPaidRegistration(any(), anyLong());
        verify(scanMapper, never()).insertCmsRegistrationScan(any());
    }

    /** 同商家但属于另一条路线的据点也不能拿来核销本票。 */
    @Test
    void selectedStationFromAnotherTopic_rejectedBeforeVerification() {
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(reg());
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(MERCHANT, 301L, 3));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.verifyAndSettleForStation(reg(), MERCHANT, 701L));

        assertEquals("所选据点不属于当前商家或路线", e.getMessage());
        verify(regMapper, never()).verifyIfNotVerified(anyLong(), anyLong());
    }

    /** 选中的据点缺 validationMethod 时 fail-closed,绝不能落一条归属为空的核销流水。 */
    @Test
    void selectedStationWithoutValidationMethod_stillVerifiesAndRecordsAttribution() {
        // 2026-08-08 设计反转:原先这里是 fail-closed(缺 validation_method 就拒绝核销)。
        // 但 cms_registration_merchant.validation_method 可空,而生产代码里**没有任何路径**给它赋值,
        // 线上存量大概率为 NULL —— 凭一个从来没人填的字段把真实商家的真实核销挡在门外,
        // 是本次改动新造的故障模式,比「流水少一个方法字段」严重得多。
        //
        // 本次要解决的是「流水答不出是哪一站」。所以判据换成:**归属照落,核销照过**。
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(reg());
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(MERCHANT, 300L, null));
        when(regMapper.verifyIfNotVerified(anyLong(), anyLong())).thenReturn(1);

        assertTrue(service.verifyAndSettleForStation(reg(), MERCHANT, 701L),
                "validation_method 为空不该阻断核销");

        ArgumentCaptor<CmsRegistrationScan> ledger = ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanMapper).insertCmsRegistrationScan(ledger.capture());
        assertNotNull(ledger.getValue().getName(),
                "据点归属必须落库 —— 这才是本次改动要解决的问题,与 validation_method 是否为空无关");
    }

    /** 合法选择必须把所选行自己的 validationMethod 写进流水,而不是 null 或候选第一行。 */
    @Test
    void selectedStationWritesItsValidationMethodToLedger() {
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(reg());
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(MERCHANT, 300L, 3));
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);

        assertTrue(service.verifyAndSettleForStation(reg(), MERCHANT, 701L));

        ArgumentCaptor<CmsRegistrationScan> ins = ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanMapper).insertCmsRegistrationScan(ins.capture());
        assertEquals(Integer.valueOf(3), ins.getValue().getValidationMethod(),
                "流水必须取 registrationMerchantId=701 对应行的 validationMethod");
        assertEquals("西岸书店", ins.getValue().getName(), "流水必须落所选据点名称快照");
        assertEquals("滨江路 18 号", ins.getValue().getAddress(), "流水必须落所选据点地址快照");
        assertEquals(Long.valueOf(8802L), ins.getValue().getTemplateId(),
                "流水必须落所选据点模板,不能只剩无法区分据点的 validationMethod");
    }

    /** 同一码并发/重复选择仍共用报名单 CAS,只有第一次能入账与写流水。 */
    @Test
    void repeatedStationChoiceOnlySettlesOnce() {
        CmsRegistration verified = reg();
        verified.setVerificationStatus(1);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(reg(), reg(), verified);
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(MERCHANT, 300L, 3));
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1, 0);

        assertTrue(service.verifyAndSettleForStation(reg(), MERCHANT, 701L));
        assertFalse(service.verifyAndSettleForStation(reg(), MERCHANT, 701L));

        verify(settlementSvc).recordPaidRegistration(any(), eq(MERCHANT));
        verify(scanMapper).insertCmsRegistrationScan(any());
    }

    /** 退款与核销并发时,事务内锁定回读到退款态必须停在支付闸,不能继续 CAS 入账。 */
    @Test
    void refundedRegistrationRecheckedInsideTransaction_beforeStationOrFundsWrite() {
        CmsRegistration refunded = reg();
        refunded.setPaymentStatus(3);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(refunded);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.verifyAndSettleForStation(reg(), MERCHANT, 701L));

        assertEquals("订单未完成支付或已退款,无法核销", e.getMessage());
        verify(registrationMerchantMapper, never()).selectCmsRegistrationMerchantByIdForUpdate(anyLong());
        verify(regMapper, never()).verifyIfNotVerified(anyLong(), anyLong());
        verify(settlementSvc, never()).recordPaidRegistration(any(), anyLong());
    }

    /** 归属复核、CAS、结算、台账必须在同一事务。 */
    @Test
    void selectedStationVerificationIsTransactional() throws Exception {
        Method m = CmsRegistrationServiceImpl.class.getMethod("verifyAndSettleForStation",
                CmsRegistration.class, Long.class, Long.class);
        org.springframework.transaction.annotation.Transactional tx =
                m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(tx, "所选据点复核与核销收口必须同事务");
        assertTrue(tx.rollbackFor().length > 0 && tx.rollbackFor()[0] == Exception.class);
    }

    @Test
    void selectedStationPassesOnlyGeneratedOriginalAuditIdAfterOrderedLocksAndCas() {
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(MERCHANT, 300L, 3));
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);

        assertTrue(service.verifyAndSettleForStation(reg(), MERCHANT, 701L, 7002L, "MERCHANT_CHECKIN"));

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        InOrder order = inOrder(topicMapper, regMapper, registrationMerchantMapper, settlementSvc,
                auditMapper, fulfillmentClaims);
        order.verify(topicMapper).selectCmsTopicByIdForUpdate(300L);
        order.verify(regMapper).selectCmsRegistrationByIdForUpdate(REG);
        order.verify(registrationMerchantMapper).selectCmsRegistrationMerchantByIdForUpdate(701L);
        order.verify(regMapper).verifyIfNotVerified(REG, MERCHANT);
        order.verify(settlementSvc).recordPaidRegistration(any(), eq(MERCHANT));
        order.verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        BusinessAuditEvent source = audit.getValue();
        assertTrue(source.getId() > 0);
        assertEquals("REGISTRATION_STATION_REDEEM", source.getAction());
        assertEquals("REGISTRATION", source.getDomainType()); assertEquals(REG, source.getDomainId());
        assertEquals("CMS_REGISTRATION", source.getTargetType()); assertEquals(REG, source.getTargetId());
        assertEquals(MERCHANT, source.getOwnerMemberId()); assertEquals(Long.valueOf(7002L), source.getActorMemberId());
        assertEquals(Long.valueOf(701L), source.getRelationId());
        assertEquals("registration-redeem-" + REG, source.getRequestId());
        order.verify(fulfillmentClaims).recordConfirmedStationFulfillment(source.getId());
        // This explicit legacy/no-V1 fixture returns empty, never a fabricated proof ID.
    }

    @Test
    void ownStationRechecksCurrentOwnershipBeforeCasRatherThanTrustingServerSnapshot() {
        CmsRegistrationMerchant observed = station(MERCHANT, 300L, 3);
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(999L, 300L, 3));

        assertEquals("所选据点不属于当前商家或路线", assertThrows(IllegalStateException.class,
                () -> service.verifyAndSettleForOwnStation(reg(), MERCHANT, MERCHANT, observed)).getMessage());

        InOrder order = inOrder(topicMapper, regMapper, registrationMerchantMapper);
        order.verify(topicMapper).selectCmsTopicByIdForUpdate(300L);
        order.verify(regMapper).selectCmsRegistrationByIdForUpdate(REG);
        order.verify(registrationMerchantMapper).selectCmsRegistrationMerchantByIdForUpdate(701L);
        verify(regMapper, never()).verifyIfNotVerified(anyLong(), anyLong());
        org.mockito.Mockito.verifyNoInteractions(settlementSvc, auditMapper, fulfillmentClaims);
    }

    @Test
    void ownStationRecordsTheRecheckedStationAndItsGeneratedAudit() {
        CmsRegistrationMerchant observed = station(MERCHANT, 300L, 1);
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(MERCHANT, 300L, 3));
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);

        assertTrue(service.verifyAndSettleForOwnStation(reg(), MERCHANT, MERCHANT, observed));
        ArgumentCaptor<CmsRegistrationScan> scan = ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanMapper).insertCmsRegistrationScan(scan.capture());
        assertEquals(Integer.valueOf(3), scan.getValue().getValidationMethod());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        verify(fulfillmentClaims).recordConfirmedStationFulfillment(audit.getValue().getId());
        assertEquals(Long.valueOf(701L), audit.getValue().getRelationId());
        assertEquals(BusinessAuditEvent.ACTOR_TYPE_MEMBER, audit.getValue().getActorType());
    }

    @Test
    void stationAuditMissingGeneratedIdCannotCallFulfillmentRecorder() {
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(MERCHANT, 300L, 3));
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);
        org.mockito.Mockito.doAnswer(call -> 1).when(auditMapper).insertBusinessAuditEvent(any());

        assertEquals("核销审计未返回有效原事件ID", assertThrows(IllegalStateException.class,
                () -> service.verifyAndSettleForStation(reg(), MERCHANT, 701L)).getMessage());
        org.mockito.Mockito.verifyNoInteractions(fulfillmentClaims);
    }

    @Test
    void stationFulfillmentRejectionEscapesOriginalTransactionalBoundary() {
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(MERCHANT, 300L, 3));
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);
        when(fulfillmentClaims.recordConfirmedStationFulfillment(anyLong()))
                .thenThrow(new IllegalStateException("confirmed V1 source conflict"));

        assertEquals("confirmed V1 source conflict", assertThrows(IllegalStateException.class,
                () -> service.verifyAndSettleForStation(reg(), MERCHANT, 701L)).getMessage());
        // Propagation only: actual SQL rollback is asserted in RegistrationStationFulfillmentMySqlIT.
    }

    @Test
    void stationReplayDoesNotCreateAnotherAuditOrCallEconomicRecorderAgain() {
        CmsRegistration verified = reg(); verified.setVerificationStatus(1);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(reg(), reg(), verified);
        when(registrationMerchantMapper.selectCmsRegistrationMerchantByIdForUpdate(701L))
                .thenReturn(station(MERCHANT, 300L, 3));
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1, 0);

        assertTrue(service.verifyAndSettleForStation(reg(), MERCHANT, 701L));
        assertFalse(service.verifyAndSettleForStation(reg(), MERCHANT, 701L));
        verify(auditMapper, org.mockito.Mockito.times(1)).insertBusinessAuditEvent(any());
        verify(fulfillmentClaims, org.mockito.Mockito.times(1)).recordConfirmedStationFulfillment(anyLong());
        verify(settlementSvc, org.mockito.Mockito.times(1)).recordPaidRegistration(any(), eq(MERCHANT));
    }
}
