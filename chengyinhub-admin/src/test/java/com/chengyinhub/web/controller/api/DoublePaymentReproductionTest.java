package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.CoopSettlementDetail;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.mapper.CoopSettlementEvidenceMapper;
import com.chengyinhub.business.mapper.CoopSettlementDetailMapper;
import com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper;
import com.chengyinhub.business.mapper.SettlementClaimMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.FrozenCoopOrderSettlementSnapshotReader;
import com.chengyinhub.business.service.impl.SnapshotFulfillmentWindowResolver;
import com.chengyinhub.business.service.impl.TopicSettlementEvidenceService;
import com.chengyinhub.business.service.impl.CoopSettlementClaimServiceImpl;
import com.chengyinhub.business.service.impl.SettlementClaimServiceImpl;
import com.chengyinhub.business.service.support.FailClosedCoopOrderLineupSource;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.mapper.CoopOrderStationFulfillmentMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopGroupMemberVerifyMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.ISettlementClaimService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.ClubMemberServiceImpl;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.impl.PricingServiceImpl;
import com.chengyinhub.business.service.impl.SettlementEntryServiceImpl;
import com.chengyinhub.business.service.impl.VerificationRiskService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2 资损探针：同一商家既是 audit_status=1 中标方、又持有生效 CoopOrder 时，
 * 一次玩家核销和随后一次主题结算是否会分别写入两套可出款账本。
 *
 * <p>测试走真实 controller 核销资格分支、{@link CmsRegistrationServiceImpl}、
 * {@link SettlementEntryServiceImpl} 和 {@link CmsTopicServiceImpl#executeTopicSettlement()}；
 * 只在 Mapper 边界捕获待落库对象，不用源码字符串代替业务执行。</p>
 *
 * <p><b>复现结论：</b>同一报名给同一商家同时生成 settlement_detail ¥90 和
 * coop_settlement MERCHANT_VERIFY ¥30，双付坐实。</p>
 *
 * <p><b>可重复负控（已实跑）：</b>把 setUp 中的 entryService 临时改成 Mockito spy，
 * 并 stub {@code recordPaidRegistration(any(), anyLong())} 返回 0，以模拟路径 a 被互斥；
 * 再运行本测试会因 {@code settlementDetailMapper.insertAdjustment} 零调用而转红。
 * 恢复真实 entryService 后测试转绿。当前生产实现没有可直接拆除的互斥条件。</p>
 */
class DoublePaymentReproductionTest {

    private static final Long TOPIC = 700L;
    private static final Long REGISTRATION = 5001L;
    private static final Long MERCHANT = 900002L;
    private static final Long PUBLISHER = 900001L;
    private static final Long PLAYER = 900003L;
    private static final Long COOP_ORDER = 41L;
    private static final Long STATION = 601L;
    private static final String CODE = "T2-DOUBLE-PAY";

    private CmsRegistrationMapper registrationMapper;
    private SettlementDetailMapper settlementDetailMapper;
    private CoopSettlementMapper coopSettlementMapper;
    private IPointsAwardService topicPointsAwardService;
    private CmsTopicServiceImpl topicService;
    private ApiRegistrationController controller;
    private CoopSettlementEvidenceMapper evidenceMapper;
    private CoopSettlementDetailMapper coopDetailMapper;
    private SettlementClaimMapper claimMapper;
    private IUmsMemberService topicMemberService;
    private CmsRegistrationScan originalScan;
    private OmsTicket frozenTicket;
    private BusinessAuditEvent originalVerificationAudit;
    private CoopOrderStationFulfillmentMapper stationFulfillments;

    @BeforeEach
    void setUp() {
        registrationMapper = mock(CmsRegistrationMapper.class);
        settlementDetailMapper = mock(SettlementDetailMapper.class);
        coopSettlementMapper = mock(CoopSettlementMapper.class);
        CmsRegistrationScanMapper scanMapper = mock(CmsRegistrationScanMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CoopOrderMapper coopOrderMapper = mock(CoopOrderMapper.class);
        CoopInviteMapper coopInviteMapper = mock(CoopInviteMapper.class);
        ISettlementRuleService ruleService = mock(ISettlementRuleService.class);

        CmsTopic topic = topic();
        CmsRegistration registration = registration();
        CoopOrder order = merchantOrder();
        when(registrationMapper.selectByverificationCode(CODE)).thenReturn(registration);
        // A包核销在原事务内按原ID锁读当前订单(requireCurrentUsableRegistration)；同一已付原行。
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(REGISTRATION)).thenReturn(registration);
        when(registrationMapper.verifyIfNotVerified(REGISTRATION, MERCHANT)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "核销 CAS 必须位于原业务事务");
            if (!Integer.valueOf(2).equals(registration.getPaymentStatus())
                    || !Integer.valueOf(2).equals(registration.getRegistrationStatus())
                    || Integer.valueOf(1).equals(registration.getVerificationStatus())) return 0;
            registration.setVerificationStatus(1);
            return 1;
        });
        when(scanMapper.insertCmsRegistrationScan(any())).thenAnswer(call -> {
            originalScan = call.getArgument(0);
            originalScan.setId("91");
            registration.setVerificationTime(originalScan.getCreateTime());
            return 1;
        });
        when(scanMapper.selectCmsRegistrationScanList(any()))
                .thenReturn(Collections.<CmsRegistrationScan>emptyList());
        when(settlementDetailMapper.insertAdjustment(any(SettlementDetail.class))).thenReturn(1);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectTopicsForSettlement()).thenReturn(Collections.singletonList(topic));
        when(topicMapper.selectCmsTopicByIdForUpdate(topic.getId())).thenReturn(topic);
        when(topicMapper.claimTopicForSettlement(TOPIC)).thenReturn(1, 0);
        when(registrationMapper.selectTotalSalesByTopicId(TOPIC)).thenReturn(new BigDecimal("100.00"));
        when(registrationMapper.selectVerifiedHeadsByTopicId(TOPIC)).thenReturn(1);
        when(registrationMapper.selectVerifiedSalesByTopicId(TOPIC)).thenReturn(new BigDecimal("100.00"));
        when(registrationMapper.selectVerifiedHeadsByTopicAndMerchant(TOPIC, MERCHANT)).thenReturn(1);
        when(registrationMapper.selectVerifiedSalesByTopicAndMerchant(TOPIC, MERCHANT))
                .thenReturn(new BigDecimal("100.00"));
        when(registrationMapper.selectVerifiedRegistrationIdsByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(REGISTRATION));
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC))
                .thenReturn(Collections.singletonList(order));
        when(coopSettlementMapper.selectByTopicId(TOPIC)).thenReturn(Collections.<CoopSettlement>emptyList());
        when(coopSettlementMapper.countByOrderId(COOP_ORDER)).thenReturn(0);
        // 落库行数守卫:生产要求每行应付 insert 恰好影响 1 行,mock 默认 0 会被判成写入失败。
        when(coopSettlementMapper.insertCoopSettlement(any(CoopSettlement.class))).thenReturn(1);
        when(coopInviteMapper.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());
        when(ruleService.getActiveRule(MERCHANT, "topic")).thenReturn(entryRule());
        when(ruleService.getActiveRule(0L, "topic")).thenReturn(topicRule());

        SettlementEntryServiceImpl entryService = new SettlementEntryServiceImpl();
        ReflectionTestUtils.setField(entryService, "settlementDetailMapper", settlementDetailMapper);
        ReflectionTestUtils.setField(entryService, "settlementRuleService", ruleService);
        ReflectionTestUtils.setField(entryService, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(entryService, "cmsActivityMapper", mock(CmsActivityMapper.class));
        // 双付闸要用它判「这个主题走不走 coop 结算」——与 CmsTopicServiceImpl 同一个谓词
        ReflectionTestUtils.setField(entryService, "coopOrderMapper", coopOrderMapper);
        // 闸后面那段(建 claim → 写分账明细)也要装齐:缺件的话把闸拆掉只会得到一个 NPE,
        // 负控就只能证明「拆了会炸」,证明不了「拆了会重新写出那几行 settlement_detail」。
        ISettlementClaimService claimService = mock(ISettlementClaimService.class);
        when(claimService.claim(any(SettlementClaim.class))).thenReturn(new SettlementClaim());
        ReflectionTestUtils.setField(entryService, "settlementClaimService", claimService);

        CmsRegistrationServiceImpl registrationService = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(registrationService, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(registrationService, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(registrationService, "cmsRegistrationScanMapper", scanMapper);
        ReflectionTestUtils.setField(registrationService, "coopGroupMemberVerifyMapper",
                mock(CoopGroupMemberVerifyMapper.class));
        ReflectionTestUtils.setField(registrationService, "settlementEntryService", entryService);
        ReflectionTestUtils.setField(registrationService, "merchantReferralBountyService",
                mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(registrationService, "verificationRiskService",
                mock(VerificationRiskService.class));
        ReflectionTestUtils.setField(registrationService, "growthService",
                mock(com.chengyinhub.business.service.IGrowthService.class));
        BusinessAuditEventMapper auditMapper = mock(BusinessAuditEventMapper.class);
        when(auditMapper.insertBusinessAuditEvent(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "原审计与核销 CAS 同事务");
            BusinessAuditEvent audit = call.getArgument(0);
            audit.setId(701L); originalVerificationAudit = audit;
            return 1;
        });
        when(auditMapper.selectByTarget("CMS_REGISTRATION", REGISTRATION)).thenAnswer(call ->
                originalVerificationAudit == null ? Collections.emptyList() : List.of(originalVerificationAudit));
        ReflectionTestUtils.setField(registrationService, "businessAuditEventMapper", auditMapper);

        // Existing resource-boundary unit managers provide actual Spring transaction markers;
        // mapper doubles do not prove database commit/rollback. Topic close uses REQUIRES_NEW.
        PlatformTransactionManager transactionManager =
                com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.unitWriteTransactions();
        CoopComplaintMapper complaintMapper = mock(CoopComplaintMapper.class);
        topicService = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(topicService, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(topicService, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(topicService, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(topicService, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(topicService, "settlementRuleService", ruleService);
        ReflectionTestUtils.setField(topicService, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(topicService, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(topicService, "coopSettlementMapper", coopSettlementMapper);
        ReflectionTestUtils.setField(topicService, "pricingService", new PricingServiceImpl());
        topicMemberService = mock(IUmsMemberService.class);
        ReflectionTestUtils.setField(topicService, "umsMemberService", topicMemberService);
        ReflectionTestUtils.setField(topicService, "umsMemberBalanceDetailService",
                mock(IUmsMemberBalanceDetailService.class));
        ReflectionTestUtils.setField(topicService, "clubService",
                mock(com.chengyinhub.business.service.IClubService.class));
        ReflectionTestUtils.setField(topicService, "coopComplaintMapper", complaintMapper);
        ReflectionTestUtils.setField(topicService, "coopInviteMapper", coopInviteMapper);
        ReflectionTestUtils.setField(topicService, "coopInviteService", mock(ICoopInviteService.class));
        // H8:settleTopic 在现金/应付台账写完后调 awardPublisherSettlement(生产侧是 @Autowired)。
        // 不装这件,主题结算会以 NPE 被 per-topic catch 吞掉 —— 那是装配缺件,不是被测行为。
        topicPointsAwardService = mock(IPointsAwardService.class);
        ReflectionTestUtils.setField(topicService, "pointsAwardService", topicPointsAwardService);
        CoopSettlementClaimServiceImpl settlement =
                installOriginalCoopEvidence(registration, topicMapper, coopOrderMapper, coopInviteMapper);
        ReflectionTestUtils.setField(registrationService, "coopSettlementClaimService", settlement);

        ICmsRegistrationMerchantService merchantService = mock(ICmsRegistrationMerchantService.class);
        CmsRegistrationMerchant winner = new CmsRegistrationMerchant();
        winner.setId(STATION);
        winner.setMemberId(MERCHANT);
        winner.setTopicId(TOPIC);
        winner.setAuditStatus(1L);
        winner.setDelFlag(0);
        winner.setValidationMethod(1);
        CmsRegistrationMerchantMapper stations = mock(CmsRegistrationMerchantMapper.class);
        when(stations.selectCmsRegistrationMerchantByIdForUpdate(STATION)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "据点归属须在原核销事务内当前读");
            return winner;
        });
        ReflectionTestUtils.setField(registrationService, "cmsRegistrationMerchantMapper", stations);
        when(merchantService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(winner));

        // Let the real G owner read the audit we actually inserted and the original legacy order.
        // There is no supplyBillingScope on this old order: the real reader returns Optional.empty,
        // rather than a mocked successful attribution or a manufactured V1 station receipt.
        stationFulfillments = mock(CoopOrderStationFulfillmentMapper.class);
        when(stationFulfillments.selectAuditRegistrationLocation(701L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertNotNull(originalVerificationAudit);
            assertEquals(701L, originalVerificationAudit.getId());
            return registration;
        });
        when(stationFulfillments.selectRegistrationForUpdate(REGISTRATION)).thenReturn(registration);
        when(stationFulfillments.selectAuditForUpdate(701L)).thenAnswer(call -> originalVerificationAudit);
        when(stationFulfillments.selectEffectiveOrdersForUpdate(TOPIC)).thenAnswer(call ->
                coopOrderMapper.selectEffectiveOrdersByTopicId(TOPIC));
        when(stationFulfillments.selectByAuditForUpdate(701L)).thenReturn(null);
        ReflectionTestUtils.setField(settlement, "stationFulfillments", stationFulfillments);

        ClubMemberServiceImpl clubMemberService = new ClubMemberServiceImpl();
        ReflectionTestUtils.setField(clubMemberService, "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(clubMemberService, "clubMapper", mock(ClubMapper.class));
        ReflectionTestUtils.setField(clubMemberService, "clubMemberMapper", mock(ClubMemberMapper.class));

        controller = spy(new ApiRegistrationController());
        doReturn(MERCHANT).when(controller).getAppUserId();
        ProxyFactory registrationProxy = new ProxyFactory(registrationService);
        registrationProxy.setInterfaces(ICmsRegistrationService.class);
        registrationProxy.addAdvice(new TransactionInterceptor(
                com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.unitBusinessTransactions(),
                new AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationService",
                (ICmsRegistrationService) registrationProxy.getProxy());
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationMerchantService", merchantService);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "coopOrderMapper", coopOrderMapper);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "clubMemberService", clubMemberService);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "activityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "topicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "dynSecret", "test-secret");
        IMerchantAccessService merchantAccessService = mock(IMerchantAccessService.class);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "merchantAccessService", merchantAccessService);
        when(merchantAccessService.require(MERCHANT, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MERCHANT, 11L, MERCHANT,
                        MerchantRoleCode.MERCHANT_OWNER));
    }

    /**
     * ★回归闸:有生效合作单的主题,票款**只走 coop 结算**,核销不得再整单入账。
     *
     * 这个用例原本是**双付的复现**(2026-08-07 坐实):同一商家既中标又有 coop_order 时,
     * 一张 ¥100 的票被记两次 —— settlement_detail ¥90 + coop_settlement ¥30,
     * 两笔指向同一张报名,合计 ¥120 从 ¥100 里出去。
     *
     * 修复后它转成回归闸:断言 settlement_detail **没有**这笔商家款,
     * 钱只从 coop_settlement 出。
     * ⚠️ 两条都要断言 —— 只断言「coop 有」不够,那样把闸拆掉也不会红。
     */
    @Test
    void topicWithCoopOrder_paysMerchantOnlyThroughCoopLedger() {
        AjaxResult verification = controller.scanQrCode("topic", CODE);
        assertEquals("核销成功", verification.get("msg"));
        assertNotNull(originalVerificationAudit);
        assertEquals("REGISTRATION_STATION_REDEEM", originalVerificationAudit.getAction());
        assertEquals(REGISTRATION, originalVerificationAudit.getDomainId());
        assertEquals(MERCHANT, originalVerificationAudit.getOwnerMemberId());
        assertEquals(STATION, originalVerificationAudit.getRelationId());
        verify(stationFulfillments).selectAuditForUpdate(originalVerificationAudit.getId());
        verify(stationFulfillments, never()).insertConfirmed(any());
        // 本单测在 Mapper 边界装入已封窗的票证据；不调用票务写侧，不模拟真实数据库时间旅行。
        assertNotNull(originalScan);
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100);
        while (System.currentTimeMillis() <= originalScan.getCreateTime().getTime() && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        Date end = new Date();
        assertTrue(originalScan.getCreateTime().before(end), "原扫码先于结算封窗");
        frozenTicket.setEndTime(end);
        assertEquals(1, topicService.executeTopicSettlement());

        SettlementDetail detail = merchantSettlementDetail();
        assertNull(detail,
                "有生效合作单时核销不得再写 settlement_detail —— 写了就是同一张票付两遍");

        CoopSettlement coop = merchantVerifySettlement();
        assertNotNull(coop, "钱仍要从 coop 结算出,别把商家的钱一起掐掉");
        assertEquals(TOPIC, coop.getTopicId());
        assertEquals(COOP_ORDER, coop.getCoopOrderId());
        assertEquals(MERCHANT, coop.getMerchantId());
        assertEquals(REGISTRATION.toString(), coop.getVerifiedRegIds(),
                "coop 结算快照必须指向同一张已核销报名");
        assertMoney("100.00", coop.getVerifiedSales(), "coop 分润基数是同一张报名的 ¥100");
        assertMoney("30.00", coop.getAmount(), "按 30% 给商家记 ¥30,这是他唯一的一笔");

        // H8:发布者的对价是 event15 积分,不是现金 —— 两头都要断言,只断一头把闸拆了也不会红。
        assertEquals(Collections.<SettlementDetail>emptyList(), capturedSettlementDetails(),
                "有生效合作单时核销一行 settlement_detail 都不许写 —— 含发布者(creator)那一行");
        ArgumentCaptor<CoopSettlementDetail> origin = ArgumentCaptor.forClass(CoopSettlementDetail.class);
        verify(coopDetailMapper).insertIgnoreDetail(origin.capture());
        assertEquals(REGISTRATION, origin.getValue().getRegistrationId());
        assertMoney("30.00", origin.getValue().getOriginAmount(), "唯一 coop 明细保留非零原始金额");
        ArgumentCaptor<SettlementClaim> claim = ArgumentCaptor.forClass(SettlementClaim.class);
        verify(claimMapper).insertClaim(claim.capture());
        assertEquals("TOPIC_EDITION", claim.getValue().getCommercialContextType());
        assertEquals(TOPIC, claim.getValue().getCommercialContextId());
        assertEquals("REGISTRATION", claim.getValue().getFulfillmentType());
        assertEquals(REGISTRATION, claim.getValue().getFulfillmentId(), "经济身份必须是原报名，不能拿扫码 ID");
        assertEquals("COOP_ORDER", claim.getValue().getSettlementRoute());
        assertEquals(COOP_ORDER, claim.getValue().getSourceContractId());
        assertEquals(0, topicService.executeTopicSettlement(), "重复结算 CAS 未领取，原金额/claim/明细零新增");
        verify(coopSettlementMapper, times(4)).insertCoopSettlement(any());
        verify(coopDetailMapper, times(1)).insertIgnoreDetail(any());
        verify(claimMapper, times(1)).insertClaim(any());
        verify(topicMemberService, never()).addBalance(any(), any());
        assertEquals(Collections.<SettlementDetail>emptyList(), capturedSettlementDetails());
        verify(evidenceMapper, times(2)).selectCashBasisBlockingRefundIdsForUpdate(TOPIC);
        verify(evidenceMapper).selectTopicCashRowsForUpdate(TOPIC);
        verify(topicPointsAwardService, times(1)).awardPublisherSettlement(PUBLISHER, TOPIC);
    }

    private CmsRegistration registration() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(REGISTRATION);
        registration.setOwnerType(1L);
        registration.setOwnerId(TOPIC);
        registration.setMemberId(PLAYER);
        registration.setPaymentStatus(2);
        registration.setRegistrationStatus(2); // 已付订单的真实状态即报名成功
        registration.setVerificationStatus(0);
        registration.setPayableAmount(new BigDecimal("100.00"));
        registration.setFeePlatformRate(new BigDecimal("0.10"));
        registration.setTicketId(801L);
        registration.setOrderNum(1L);
        registration.setPurchaseKind(1);
        return registration;
    }

    private CmsTopic topic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(PUBLISHER);
        topic.setProductType(ProductType.ORIENTEERING);
        topic.setDelFlag(0);
        topic.setLifecycle(3);
        topic.setSellingStartedAt(new Date(0));
        topic.setFeePlatformRate(new BigDecimal("0.10"));
        topic.setFeeGuaranteeBaseRate(new BigDecimal("0.05"));
        topic.setFeeGuaranteeStepRate(new BigDecimal("0.02"));
        topic.setFeeGuaranteeMaxMerchant(5);
        return topic;
    }

    private CoopOrder merchantOrder() {
        CoopOrder order = new CoopOrder();
        order.setId(COOP_ORDER);
        order.setTopicId(TOPIC);
        order.setFromId(PUBLISHER);
        order.setStatus(0);
        order.setDelFlag(0);
        order.setTermsVersion("v1");
        order.setRoleSnapshot("SUPPLY");
        order.setScopeTypeSnapshot("TOPIC");
        order.setScopeIdSnapshot(TOPIC);
        order.setEligiblePurchaseKindsSnapshot("GUIDED_TICKET");
        order.setFulfillmentEvidenceRuleSnapshot("REGISTRATION_SCAN");
        order.setTermsSnapshot("{\"shareMode\":1,\"compensationMode\":\"SALES_PERCENT\",\"shareRate\":30}");
        order.setToType("merchant");
        order.setToId(MERCHANT);
        order.setShareMode(1);
        order.setShareRate(new BigDecimal("30"));
        return order;
    }

    /** Real reader/evidence/claim services; only persistence boundaries use controlled fixture rows. */
    private CoopSettlementClaimServiceImpl installOriginalCoopEvidence(CmsRegistration registration,
            CmsTopicMapper topics, CoopOrderMapper orders, CoopInviteMapper invites) {
        evidenceMapper = mock(CoopSettlementEvidenceMapper.class);
        com.chengyinhub.business.service.impl.OriginalCloseRefundHistoryFixture.install(topicService, TOPIC, Collections.emptyList());
        when(evidenceMapper.countPaidActivityRegistrations(TOPIC)).thenReturn(0);
        when(evidenceMapper.selectTopicCashRowsForUpdate(TOPIC)).thenReturn(List.of(registration));
        when(evidenceMapper.selectCashBasisBlockingRefundIdsForUpdate(TOPIC)).thenReturn(Collections.emptyList());
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        when(activities.selectActiveActivityIdsByTopicId(TOPIC)).thenReturn(Collections.emptyList());
        frozenTicket = new OmsTicket(); frozenTicket.setId(registration.getTicketId());
        frozenTicket.setOwnerType(1L); frozenTicket.setOwnerId(TOPIC); frozenTicket.setDelFlag(0);
        frozenTicket.setMode(1); frozenTicket.setSubType("guided"); frozenTicket.setStartTime(new Date(0));
        OmsTicketMapper tickets = mock(OmsTicketMapper.class);
        when(tickets.selectOmsTicketList(any())).thenReturn(List.of(frozenTicket));
        when(evidenceMapper.selectTopicEvidenceCandidatesForUpdate(any(), any(), any())).thenAnswer(call -> {
            assertNotNull(originalScan, "没有实际核销原回执就没有正向结算证据");
            CoopSettlementCandidate candidate = new CoopSettlementCandidate();
            candidate.setRegistrationId(originalScan.getRegistrationId());
            candidate.setOwnerType(registration.getOwnerType()); candidate.setOwnerId(registration.getOwnerId());
            candidate.setSourceTopicId(TOPIC); candidate.setOrderQuantity(registration.getOrderNum());
            candidate.setRegistrationStatus(registration.getRegistrationStatus());
            candidate.setPaymentStatus(registration.getPaymentStatus());
            candidate.setVerificationStatus(registration.getVerificationStatus()); candidate.setPurchaseKind("GUIDED_TICKET");
            candidate.setEligibleSalesSnapshot(registration.getPayableAmount()); candidate.setScopeCovered(true);
            candidate.setTicketOwnerMatches(true); candidate.setFulfillmentStartInclusive(frozenTicket.getStartTime());
            candidate.setFulfillmentEndExclusive(frozenTicket.getEndTime());
            candidate.setMerchantScanPresent(MERCHANT.equals(originalScan.getMemberId())
                    && Integer.valueOf(1).equals(originalScan.getRegistrationType()));
            candidate.setMerchantScanTime(originalScan.getCreateTime());
            candidate.setVerificationTime(registration.getVerificationTime()); return List.of(candidate);
        });
        FrozenCoopOrderSettlementSnapshotReader reader = new FrozenCoopOrderSettlementSnapshotReader(
                new FailClosedCoopOrderLineupSource(orders, invites), topics, mock(CmsTopicNodeMapper.class), tickets);
        TopicSettlementEvidenceService evidence = new TopicSettlementEvidenceService(activities, registrationMapper,
                mock(com.chengyinhub.business.mapper.CoopGroupVerifyMapper.class), evidenceMapper);
        claimMapper = mock(SettlementClaimMapper.class);
        Map<String,SettlementClaim> claims = new HashMap<>();
        when(claimMapper.insertClaim(any())).thenAnswer(call -> {
            SettlementClaim requested = call.getArgument(0);
            if (claims.containsKey(requested.economicKey())) throw new org.springframework.dao.DuplicateKeyException("economic key");
            requested.setId(9001L); claims.put(requested.economicKey(), requested); return 1;
        });
        when(claimMapper.selectByEconomicKeyForUpdate(any())).thenAnswer(call -> claims.get(call.<SettlementClaim>getArgument(0).economicKey()));
        coopDetailMapper = mock(CoopSettlementDetailMapper.class);
        when(coopDetailMapper.insertIgnoreDetail(any())).thenReturn(1);
        when(coopDetailMapper.refreshOriginAggregate(any())).thenReturn(1);
        List<CoopSettlement> heads = new ArrayList<>();
        when(coopSettlementMapper.selectByTopicId(TOPIC)).thenAnswer(call -> new ArrayList<>(heads));
        when(coopSettlementMapper.insertCoopSettlement(any())).thenAnswer(call -> {
            CoopSettlement head = call.getArgument(0); head.setId(8000L + heads.size()); heads.add(head); return 1;
        });
        CoopSettlementClaimServiceImpl settlement = new CoopSettlementClaimServiceImpl(reader,
                new SnapshotFulfillmentWindowResolver(), evidence, new SettlementClaimServiceImpl(claimMapper),
                coopSettlementMapper, coopDetailMapper, mock(CoopSettlementAdjustmentMapper.class));
        ReflectionTestUtils.setField(settlement,"topicMapper",topics);
        ReflectionTestUtils.setField(topicService, "coopSettlementEvidenceMapper", evidenceMapper);
        ReflectionTestUtils.setField(topicService, "coopSettlementClaimService", settlement);
        return settlement;
    }

    private SettlementRule entryRule() {
        SettlementRule rule = new SettlementRule();
        rule.setCreatorRate(BigDecimal.ZERO);
        return rule;
    }

    /** H8:creator_rate 全并 merchant(0.70 + 0.20),发布者的对价改成 event15 积分。 */
    private SettlementRule topicRule() {
        SettlementRule rule = new SettlementRule();
        rule.setMerchantRate(new BigDecimal("0.90"));
        rule.setCreatorRate(new BigDecimal("0.00"));
        return rule;
    }

    /**
     * 取核销路径给该商家写的那笔 settlement_detail;**一笔都没写就返回 null**。
     * ⚠️ 不能用 verify(atLeastOnce()) —— 修复后正确行为就是零调用,
     * 那样会抛「Wanted but not invoked」,把「闸生效了」误报成用例失败。
     */
    private SettlementDetail merchantSettlementDetail() {
        ArgumentCaptor<SettlementDetail> captor = ArgumentCaptor.forClass(SettlementDetail.class);
        verify(settlementDetailMapper, atLeast(0)).insertAdjustment(captor.capture());
        for (SettlementDetail detail : captor.getAllValues()) {
            if ("merchant".equals(detail.getReceiverType()) && MERCHANT.equals(detail.getReceiverId())) {
                return detail;
            }
        }
        return null;
    }

    /** 核销路径落进 settlement_detail 的全部行(merchant / platform / creator 三种收款方都在内)。 */
    private List<SettlementDetail> capturedSettlementDetails() {
        ArgumentCaptor<SettlementDetail> captor = ArgumentCaptor.forClass(SettlementDetail.class);
        verify(settlementDetailMapper, atLeast(0)).insertAdjustment(captor.capture());
        return captor.getAllValues();
    }

    private CoopSettlement merchantVerifySettlement() {
        ArgumentCaptor<CoopSettlement> captor = ArgumentCaptor.forClass(CoopSettlement.class);
        verify(coopSettlementMapper, atLeastOnce()).insertCoopSettlement(captor.capture());
        List<CoopSettlement> lines = captor.getAllValues();
        for (CoopSettlement line : lines) {
            if ("MERCHANT_VERIFY".equals(line.getLineType()) && MERCHANT.equals(line.getMerchantId())) {
                return line;
            }
        }
        throw new AssertionError("未生成同一商家的 MERCHANT_VERIFY 行: " + lines);
    }

    private void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + "，实际=" + actual);
    }
}
