package com.chengyinhub.business.service;

import java.util.Collections;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationScan;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 赏金核销钩子的**接线**:两条核销路径都必须触发,而不是只有一条。
 *
 * <p><b>为什么单独一个类</b>:核销有两条路径,而赏金只认「某商家核销了某张票」这一个信号:
 * <ul>
 *   <li>{@code verifyAndSettle} —— ①GUIDED_TICKET / ②SELF_PLAY_PASS 的整单核销</li>
 *   <li>{@code redeemExploreChapterAndRecord} —— ③EXPLORE_PASS 的按章核销(PR #95 新增)</li>
 * </ul>
 * 两条都汇到 {@code writeScanLedger},钩子就挂在那个**唯一收口点**上。
 *
 * <p><b>这个类是为一个真实事故写的</b>(2026-07-17 rebase):本分支原先把钩子挂在
 * {@code verifyAndSettle} 上,而 #95 在此期间新增了 ③ 按章核销这条路 ⇒ <b>③ 商家永远不触发赏金,
 * 且零告警</b> —— 偏偏 ③(探索通行证/商家权益兑换)正是这个冷启动功能最该覆盖的那类商家。
 *
 * <p><b>更要命的是:当时没有任何测试会因此变红。</b> 负控实测:把钩子从收口点整个删掉,
 * {@code ExploreRedeemRecordTest} 8 条<b>照样全绿</b>(它只注了哑 mock,不断言赏金被调)。
 * 「挂错层」本身不可怕,可怕的是挂错了没人知道 —— 所以这两条断言必须存在。
 *
 * <p>负控实测(2026-07-17):
 * <ul>
 *   <li>删掉 {@code writeScanLedger} 末尾的 {@code onVerification} ⇒ 本类两条全红</li>
 *   <li>把钩子挪回 {@code verifyAndSettle} 内(只覆盖 ①/②)⇒ {@code exploreChapterRedeem_*} 红</li>
 * </ul>
 */
class ReferralBountyHookWiringTest {

    private static final Long REG = 5001L;
    private static final Long MERCHANT = 77L;
    private static final Long PAYEE = 88L;
    private static final Long TOPIC = 900L;
    private static final Long CH1 = 61L;

    private CmsRegistrationMapper regMapper;
    private CmsRegistrationScanMapper scanMapper;
    private IPerkLedgerService perkSvc;
    private ISettlementEntryService settlementSvc;
    private MerchantReferralBountyService bountySvc;
    private CmsRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        regMapper = mock(CmsRegistrationMapper.class);
        scanMapper = mock(CmsRegistrationScanMapper.class);
        perkSvc = mock(IPerkLedgerService.class);
        when(perkSvc.findReplayableChapterRedemptionIdForUpdate(any(), any(), any(), any())).thenReturn(null);
        settlementSvc = mock(ISettlementEntryService.class);
        bountySvc = mock(MerchantReferralBountyService.class);

        service = new CmsRegistrationServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "growthService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IGrowthService.class));
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        com.chengyinhub.business.mapper.CmsActivityMapper activities = mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        com.chengyinhub.business.domain.CmsActivity activity = new com.chengyinhub.business.domain.CmsActivity();
        activity.setDelFlag(0);
        activity.setId(800L);
        activity.setTopicId(TOPIC);
        // 探店日票缺结束时间按已定规则暂禁新使用；本类验赏金钩子，给未到期的结束时间。
        activity.setEndDate(new java.util.Date(System.currentTimeMillis() + 86_400_000L));
        when(activities.selectCmsActivityByIdForUpdate(800L)).thenReturn(activity);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "cmsRegistrationScanMapper", scanMapper);
        ReflectionTestUtils.setField(service, "perkLedgerService", perkSvc);
        ReflectionTestUtils.setField(service, "settlementEntryService", settlementSvc);
        ReflectionTestUtils.setField(service, "merchantReferralBountyService", bountySvc);
        // [③应付] 收口方法第④步会计提平台应付;本类只测赏金钩子,哑 mock 即可。
        // 台账口径见 MerchantPayableServiceTest,接线断言见 ExploreRedeemRecordTest。
        ReflectionTestUtils.setField(service, "merchantPayableService",
                mock(IMerchantPayableService.class));
        BusinessAuditEventMapper auditMapper = mock(BusinessAuditEventMapper.class);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);

        when(scanMapper.selectCmsRegistrationScanList(any()))
                .thenReturn(Collections.<CmsRegistrationScan>emptyList());
    }

    private CmsRegistration guidedTicket() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(1L);
        r.setPurchaseKind(com.chengyinhub.common.constant.PurchaseKind.GUIDED_TICKET);
        return r;
    }

    private CmsRegistration explorePass() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setOwnerType(2L);
        r.setOwnerId(800L);
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setMemberId(901L);
        r.setPurchaseKind(com.chengyinhub.common.constant.PurchaseKind.EXPLORE_PASS);
        return r;
    }

    /** ①/② 整单核销 ⇒ 赏金要收到信号,且带的是**核销那个商家**的 id。 */
    @Test
    void wholeOrderVerify_notifiesBounty() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(1);

        service.verifyAndSettle(guidedTicket(), MERCHANT, PAYEE, 1);

        verify(bountySvc).onVerification(MERCHANT);
    }

    /**
     * ★ ③ 按章核销 ⇒ 赏金**同样**要收到信号。
     *
     * <p>这条就是那个事故的守门人:钩子挂 {@code verifyAndSettle} 时,这条路一个信号都不发。
     */
    @Test
    void exploreChapterRedeem_notifiesBounty() {
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenAnswer(invocation -> explorePass());
        when(perkSvc.resolveRedeemableChapters(REG, TOPIC, MERCHANT))
                .thenReturn(Collections.singletonList(CH1));
        when(perkSvc.redeemChapter(REG, CH1, MERCHANT, MERCHANT)).thenReturn(5L);

        service.redeemExploreChapterAndRecord(explorePass(), CH1, TOPIC, MERCHANT, MERCHANT, 1);

        verify(bountySvc).onVerification(MERCHANT);
    }

    /**
     * 核销 CAS 没抢到(别人已核销)⇒ 不该通知赏金 —— 这次核销压根没成立。
     *
     * <p>否则「同一张票被反复扫」会一直给赏金重算的机会;虽然 tryActivate 自己幂等,
     * 但白跑查询,且语义上「没核销成」不该产生「核销了」的信号。
     */
    @Test
    void wholeOrderVerify_casLost_doesNotNotifyBounty() {
        when(regMapper.verifyIfNotVerified(REG, MERCHANT)).thenReturn(0);
        CmsRegistration verified = guidedTicket();
        verified.setPaymentStatus(2);
        verified.setRegistrationStatus(2);
        verified.setVerificationStatus(1);
        when(regMapper.selectCmsRegistrationByIdForUpdate(REG)).thenReturn(verified);

        service.verifyAndSettle(guidedTicket(), MERCHANT, PAYEE, 1);

        verify(bountySvc, org.mockito.Mockito.never()).onVerification(anyLong());
    }
}
