package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-14 ③ 按章核销的**留痕收口** —— redeem + 台账 + 置位,三步同一事务。
 *
 * <p><b>为什么 ③ 也必须留痕</b>:核销发生了,商家侧就该查得到、玩家票夹就该看得见。
 * 初版只调了裸 redeemChapter ⇒ redemption 表有每章流水,但
 * {@code cms_registration_scan} 台账没有、{@code verification_status} 不置位
 * ⇒ 商家「我核销过谁」查不到、玩家票夹永远显示未核销。
 *
 * <p><b>四件事</b>(与 verifyAndSettle 的分工;第 ④ 件的入账口径与它完全不同):
 * <ul>
 *   <li>✅ 台账:每笔核销都写(幂等键 (regId, memberId, type=1) ⇒ 同一商家一张票一行)</li>
 *   <li>✅ verification_status:**首次核销就置**(拍板:玩家只要核销一个就不能退款)——
 *       商家已把权益端出去了,钱就不能退。它是三个退款入口共用的唯一判据
 *       (RefundPolicy.verifiedVerdict)⇒ 置在这里三个闸自动全生效,不必改 6 个调用点。
 *       ⚠️ 语义是报名级布尔「这张票被核销过」不是「核销完了」;
 *       ⚠️ 与 ApiVerifyController.issueDynCode 的 ③ 出码判据(hasPendingEntitlements)**绑死** ——
 *       那处不改就会在核销完第 1 章后再也出不了码,②~⑤ 章核销不了。别只改一边。</li>
 *   <li>✅ <b>计酬档记平台应付台账</b>({@code accrueOnRedemption})。
 *       ⚠️ 这**不是** recordPaidRegistration —— 那个是「整单票款给单一 payee」,③ 照搬 =
 *       最后扫的那家白拿全额 = 资损。两者必须同时成立:应付台账要调、recordPaidRegistration 永不调。
 *       台账自身的口径(两道闸/费率快照/幂等)由 {@link MerchantPayableServiceTest} 测,
 *       本类只管「收口方法有没有把它调起来」。</li>
 * </ul>
 *
 * <p>⚠️ 负控已实测(注入→红→撤销→绿):
 * <ul>
 *   <li>删掉 writeScanLedger 调用 ⇒ redeem_writesScanLedger 红</li>
 *   <li>把 verifyIfNotVerified 调用删掉 ⇒ firstRedeem_verifiesImmediatelySoRefundIsBlocked 红</li>
 *   <li>加上 recordPaidRegistration ⇒ redeem_neverRecordsSettlement 红</li>
 *   <li>删掉 accrueOnRedemption 调用 ⇒ redeem_accruesPlatformPayable 红</li>
 * </ul>
 */
class ExploreRedeemRecordTest {

    private static final Long REG = 900L;
    private static final Long TOPIC = 700L;
    private static final Long CH1 = 101L;
    private static final Long MERCHANT = 500L;
    private static final Long OPERATOR = 501L;

    private CmsRegistrationMapper regMapper;
    private com.chengyinhub.business.mapper.CmsActivityMapper activityMapper;
    private CmsRegistrationScanMapper scanMapper;
    private IPerkLedgerService perkSvc;
    private ISettlementEntryService settlementSvc;
    private IMerchantPayableService payableSvc;
    private ITopicCompletionAwardService completionAwardService;
    private RedemptionMapper redemptionMapper;
    private BusinessAuditEventMapper auditMapper;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        regMapper = mock(CmsRegistrationMapper.class);
        activityMapper = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setDelFlag(0);
        activity.setTopicId(TOPIC);
        activity.setEndDate(new java.util.Date(System.currentTimeMillis() + 86400000L));
        when(activityMapper.selectCmsActivityByIdForUpdate(800L)).thenReturn(activity);
        scanMapper = mock(CmsRegistrationScanMapper.class);
        perkSvc = mock(IPerkLedgerService.class);
        settlementSvc = mock(ISettlementEntryService.class);
        payableSvc = mock(IMerchantPayableService.class);
        completionAwardService = mock(ITopicCompletionAwardService.class);
        redemptionMapper = mock(RedemptionMapper.class);
        auditMapper = mock(BusinessAuditEventMapper.class);

        service = new CmsRegistrationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "growthService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IGrowthService.class));
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationScanMapper", scanMapper);
        // [赏金] writeScanLedger 末尾会调 onVerification(核销是赏金唯一认的信号,收口点在那);
        //        本类不测赏金,哑 mock 即可。赏金自身判定见 MerchantReferralBountyTest。
        ReflectionTestUtils.setField(service, "merchantReferralBountyService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(service, "perkLedgerService", perkSvc);
        ReflectionTestUtils.setField(service, "settlementEntryService", settlementSvc);
        ReflectionTestUtils.setField(service, "merchantPayableService", payableSvc);
        ReflectionTestUtils.setField(service, "topicCompletionAwardService", completionAwardService);
        ReflectionTestUtils.setField(service, "redemptionMapper", redemptionMapper);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);

        when(scanMapper.selectCmsRegistrationScanList(any()))
                .thenReturn(Collections.<CmsRegistrationScan>emptyList());
        when(perkSvc.redeemChapter(REG, CH1, MERCHANT, MERCHANT)).thenReturn(5L);
        // [P0-14] 收口方法会在**事务内**复核 chapterId 属于候选集(防商家直接打任意 chapterId 进来)
        when(perkSvc.resolveRedeemableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(java.util.Collections.singletonList(CH1));
        Redemption storedRedemption = new Redemption();
        storedRedemption.setCreateTime(new java.util.Date());
        when(redemptionMapper.selectById(5L)).thenReturn(storedRedemption);
    }

    private CmsRegistration reg() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setMemberId(901L);
        r.setOwnerType(2L);
        r.setOwnerId(800L);   // ③ 的 ownerId 是 activityId
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setPurchaseKind(3);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(r);
        return r;
    }

    private ExploreChapterRedemptionResult redeem() {
        return service.redeemExploreChapterAndRecord(reg(), CH1, TOPIC, MERCHANT, MERCHANT, null);
    }

    @Test
    void cancelledActivityRejectsBeforeAnyChapterConsumption() {
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setCancelTime(new java.util.Date());
        when(activityMapper.selectCmsActivityByIdForUpdate(800L)).thenReturn(activity);
        assertEquals("活动已取消，无法使用", assertThrows(IllegalStateException.class, this::redeem).getMessage());
        verify(regMapper, never()).hasActiveManualRefundCaseForUpdate(anyLong());
        verify(perkSvc, never()).redeemChapter(anyLong(), anyLong(), anyLong(), anyLong());
        verify(scanMapper, never()).insertCmsRegistrationScan(any());
        verify(payableSvc, never()).accrueOnRedemption(anyLong());
    }

    // ------------------------------------------------- 留痕

    /** ★ 每笔核销都要写台账:商家侧要能查到「我核销过这张票」。 */
    @Test
    void redeem_writesScanLedger() {
        when(perkSvc.isExploreCompleted(REG, TOPIC)).thenReturn(false);

        assertEquals(5L, redeem().getRedemptionId().longValue());

        ArgumentCaptor<CmsRegistrationScan> c = ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanMapper).insertCmsRegistrationScan(c.capture());
        assertEquals(REG, c.getValue().getRegistrationId());
        assertEquals(MERCHANT, c.getValue().getMemberId(), "台账记的是核销的商家");
        assertEquals(Integer.valueOf(1), c.getValue().getRegistrationType(),
                "必须带 registration_type=1,否则会与报名时按节点模板插的 type=0 行混淆");
    }

    @Test
    void operatorIsRecordedOnRedemptionWithoutChangingMerchantBusinessOwnership() {
        when(perkSvc.redeemChapter(REG, CH1, MERCHANT, OPERATOR)).thenReturn(5L);
        when(perkSvc.isExploreCompleted(REG, TOPIC)).thenReturn(false);

        service.redeemExploreChapterAndRecord(
                reg(), CH1, TOPIC, MERCHANT, OPERATOR, "MERCHANT_CHECKIN", null);

        verify(perkSvc).redeemChapter(REG, CH1, MERCHANT, OPERATOR);
        verify(regMapper).verifyIfNotVerified(REG, MERCHANT);
        ArgumentCaptor<CmsRegistrationScan> scan = ArgumentCaptor.forClass(CmsRegistrationScan.class);
        verify(scanMapper).insertCmsRegistrationScan(scan.capture());
        assertEquals(MERCHANT, scan.getValue().getMemberId());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals(MERCHANT, audit.getValue().getOwnerMemberId());
        assertEquals(OPERATOR, audit.getValue().getActorMemberId());
        assertEquals("MERCHANT_CHECKIN", audit.getValue().getActorRoleCode());
        assertEquals(CH1, audit.getValue().getRelationId());
    }

    /** 台账幂等:同一商家核销该票第 2 章时不重复插(真源是 redemption 的每章流水)。 */
    @Test
    void redeem_scanLedgerIsIdempotentPerMerchant() {
        when(scanMapper.selectCmsRegistrationScanList(any()))
                .thenReturn(Collections.singletonList(new CmsRegistrationScan()));   // 已有台账
        when(perkSvc.isExploreCompleted(REG, TOPIC)).thenReturn(false);

        redeem();

        verify(scanMapper, never()).insertCmsRegistrationScan(any());
    }

    // ------------------------------------------------- 首次核销即置位(退款闸的前提)

    /**
     * ★ **首次核销就置** verification_status(拍板:玩家只要核销一个就不能退款)。
     *
     * <p>商家已经把权益端出去了,钱就不能退。verification_status 是三个退款入口共用的唯一判据
     * (RefundPolicy.verifiedVerdict 的 ③ 分支「已核销订单需人工核实」正 key 在它上面)
     * ⇒ 置位在这里,三个闸自动全部生效,不必去改 6 个调用点(那才是「闸放错层」的温床)。
     * ⚠️ 语义是**报名级布尔「这张票被核销过」**,不是「核销完了」——
     * 与 CmsTopicServiceImpl:897 的既有注释同口径。
     */
    @Test
    void firstRedeem_verifiesImmediatelySoRefundIsBlocked() {
        when(perkSvc.isExploreCompleted(REG, TOPIC)).thenReturn(false);   // 才核销 1/3 章

        redeem();

        verify(regMapper).verifyIfNotVerified(REG, MERCHANT);
    }

    /** 通关那笔同样置(CAS 返 0 无害)—— 置位与通关无关,每笔都置。 */
    @Test
    void finalRedeem_alsoVerifies() {
        when(perkSvc.isExploreCompleted(REG, TOPIC)).thenReturn(true);
        Redemption redemption = new Redemption();
        redemption.setCreateTime(new java.util.Date());
        when(redemptionMapper.selectById(5L)).thenReturn(redemption);

        redeem();

        verify(regMapper).verifyIfNotVerified(REG, MERCHANT);
        CmsRegistration expected = reg(); // reg() 内含锁读 stub，不能在 verify 参数里调用
        verify(completionAwardService).grantOnce(expected.getMemberId(), TOPIC, expected.getOwnerId(), redemption.getCreateTime());
    }

    @Test
    void finalRedeemReturnsOnlyGrantOnceEarnedXpReceipt() {
        when(perkSvc.isExploreCompleted(REG, TOPIC)).thenReturn(true);
        TopicCompletionAwardVO award = new TopicCompletionAwardVO();
        award.setFirstCompletion(true);
        award.setEarnedXp(36);
        when(completionAwardService.grantOnce(any(), eq(TOPIC), any(), any())).thenReturn(award);

        ExploreChapterRedemptionResult result = redeem();

        assertEquals(5L, result.getRedemptionId());
        assertEquals(36, result.getCompletionEarnedXp());
    }

    @Test
    void completionAwardFailurePropagatesSoWholeRedemptionTransactionCanRollback() {
        when(perkSvc.isExploreCompleted(REG, TOPIC)).thenReturn(true);
        doThrow(new IllegalStateException("award failed")).when(completionAwardService)
                .grantOnce(any(), eq(TOPIC), any(), any());

        IllegalStateException error = assertThrows(IllegalStateException.class, this::redeem);

        assertEquals("award failed", error.getMessage());
    }

    /** 第 2 章及以后 CAS 必然返 0(已置过位)⇒ **不是错误**,不该抛。 */
    @Test
    void subsequentRedeem_casReturnsZero_doesNotThrow() {
        when(perkSvc.isExploreCompleted(REG, TOPIC)).thenReturn(false);
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(0);

        assertEquals(5L, redeem().getRedemptionId().longValue());
    }

    @Test
    void activeManualRefundCaseBlocksBeforeChapterEntitlementIsConsumed() {
        when(regMapper.hasActiveManualRefundCaseForUpdate(REG)).thenReturn(true);

        IllegalStateException error = assertThrows(IllegalStateException.class, this::redeem);

        assertEquals("订单正在人工退款处理中，暂不可核销", error.getMessage());
        verify(perkSvc, never()).redeemChapter(anyLong(), anyLong(), anyLong(), anyLong());
    }

    // ------------------------------------------------- 绝不入账

    /**
     * ★ ③ **永不**调 recordPaidRegistration。
     * 它是「整单票款按 settlement_rule 给单一 payee」的口径,而 ③ 一张票跨 N 家核销
     * ⇒ 照搬 = 最后扫的那家白拿全额 = 资损。
     *
     * <p>⚠️ 这条与 {@link #redeem_accruesPlatformPayable} 是**一对**,必须同时成立:
     * ③ 要入账,但走的是平台应付台账,不是 settlement_detail。
     * 「接上了入账」不等于「可以调 recordPaidRegistration 了」——
     * 谁哪天为了省事把它接回来,这条测试必须红。
     */
    @Test
    void redeem_neverRecordsSettlement() {
        when(perkSvc.isExploreCompleted(REG, TOPIC)).thenReturn(true);   // 连通关时也不入账

        redeem();

        verify(settlementSvc, never()).recordPaidRegistration(any(), anyLong());
    }

    /**
     * ★★ <b>四步必须在同一事务里</b> —— 源码断言,因为行为测试看不见事务边界。
     *
     * <p>这条守的不是「@Transactional 这个注解在不在」,是<b>「核销提交了、应付没落」这个丢账口</b>:
     * 收口方法一旦丢掉事务(或有人把第 ④ 步挪到**无事务的 controller** 里顺序调),
     * redeem 会先提交、accrue 再抛 ⇒ 配额扣了、entitlement 用尽了、
     * <b>平台欠的钱没记上</b>,且商家再也重扫不了(幂等键已占)。零告警。
     *
     * <p>⚠️ 单测证明不了「Spring 真的开了事务」,只能证明<b>声明还在</b>。
     * 真正的事务行为由 {@link #scanLedgerThrows_propagatesSoTxRollsBack}(异常不被吞)
     * 和生产验证兜。<b>两层都不是全证,但缺一层就能骗过自己。</b>
     *
     * <p>🔴 负控:把 redeemExploreChapterAndRecord 上的 @Transactional 删掉 ⇒ 本条红。
     */
    @Test
    void redeemAndRecord_isTransactional() throws Exception {
        java.lang.reflect.Method m = CmsRegistrationServiceImpl.class.getMethod(
                "redeemExploreChapterAndRecord",
                com.chengyinhub.business.domain.CmsRegistration.class,
                Long.class, Long.class, Long.class, Long.class, Integer.class);
        org.springframework.transaction.annotation.Transactional tx =
                m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        org.junit.jupiter.api.Assertions.assertNotNull(tx,
                "四步(复核/redeem/台账/置位/应付)必须同事务 —— 丢了它,核销提交后应付再抛就是丢账,且零告警");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new Class<?>[]{Exception.class}, tx.rollbackFor(),
                "必须 rollbackFor=Exception:默认只对 RuntimeException 回滚,"
                        + "而本链路的拒绝有一半是 IllegalStateException 之外的受检异常路径");
    }

    /**
     * ★ 第 ④ 步:核销 ⇒ 计提平台应付,**用 redeemChapter 返回的那个 redemptionId**。
     *
     * <p>不测台账口径本身(两道闸/费率快照/幂等在 {@link MerchantPayableServiceTest}),
     * 只测收口方法真的把它调起来了 —— 这一步以前是**缺的**:核销成功、redemption 有流水,
     * 却没有任何地方记着平台欠这家店多少钱,且零告警。
     *
     * <p>⚠️ 断言参数是 5L(redeemChapter 的返回值)而不是 anyLong():传错 id
     * ⇒ 应付记到别人头上,而 anyLong() 看不出来。
     */
    @Test
    void redeem_accruesPlatformPayable() {
        redeem();

        verify(payableSvc).accrueOnRedemption(5L);
    }

    // ------------------------------------------------- 同事务

    /**
     * ★ 台账失败 ⇒ 异常穿透 ⇒ redeem 一并回滚(靠方法上的 @Transactional)。
     * 若三步不在同一事务:redeem 提交后台账再抛 = 配额扣了、entitlement 用尽了、台账没有,
     * 且商家再也重扫不了。单测证明不了事务本身,但能证明异常没被吞。
     */
    @Test
    void scanLedgerThrows_propagatesSoTxRollsBack() {
        when(perkSvc.isExploreCompleted(REG, TOPIC)).thenReturn(false);
        doThrow(new RuntimeException("台账写入失败")).when(scanMapper).insertCmsRegistrationScan(any());

        assertThrows(RuntimeException.class, this::redeem);
    }

    /** redeemChapter 抛(如权益不可核销)⇒ 台账不该写。 */
    @Test
    void redeemThrows_noScanLedger() {
        when(perkSvc.redeemChapter(REG, CH1, MERCHANT, MERCHANT))
                .thenThrow(new RuntimeException("该章节权益不可核销"));

        assertThrows(RuntimeException.class, this::redeem);
        verify(scanMapper, never()).insertCmsRegistrationScan(any());
        verify(regMapper, never()).verifyIfNotVerified(anyLong(), anyLong());
    }
}
