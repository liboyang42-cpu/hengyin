package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MerchantPayable;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.MerchantPayableMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.service.impl.MerchantPayableServiceImpl;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ③ 计酬档平台应付台账(施工图 §5/§9)。
 *
 * <p><b>这套测试真正守的是什么</b>:③ 开卖那一刻起,每次计酬核销都在无声丢账 ——
 * 核销成功、redemption 有流水,**没有任何地方记着平台欠这家店多少钱**,且零告警。
 *
 * <p>⚠️ <b>「快照」在这套底子上能测到哪、测不到哪,说清楚</b>(§9 要求「真改再查,不能 mock」,
 * 而本仓没有 SpringBootTest/H2/testcontainers,纯 mock 单测):
 * <ul>
 *   <li><b>测不到</b>:「改库里 offer 的 per_head_fee → 台账行纹丝不动」。mock 之下这条是**恒真**的
 *       (根本没有库),写出来是自欺 —— 那正是本项目栽过的假绿本体。</li>
 *   <li><b>能测到、且是同一件事的因</b>:<b>结算/冲正路径永不读 live offer</b>
 *       ({@link #settlementAndReversalNeverReadLiveOffer})。追溯改写只可能经由「事后再查一次
 *       merchant_chapter_offer」发生;把这条路堵死,快照就不可能被改写。
 *       这条负控真能红:在 reverseOnRedemption 里加一行 offerMapper.selectById 就红。</li>
 *   <li><b>仍需人验</b>:真库上「落账 → 改价 → 查台账行不变」。属于上线前的一次性验证,
 *       不是每次 CI 都跑的东西。</li>
 * </ul>
 *
 * <p>⚠️ 负控已实测(注入→红→撤销→绿),每条都单独跑过:
 * <ul>
 *   <li>把 platformPays 判据改成恒 true ⇒ {@link #perkRedemption_doesNotAccrue} /
 *       {@link #trafficRedemption_doesNotAccrue} 红</li>
 *   <li>把 isPlatformOwned 判据删掉 ⇒ {@link #thirdPartyTopic_doesNotAccrue} 红</li>
 *   <li>把幂等预检删掉 ⇒ {@link #accrue_isIdempotentPerRedemption} 红</li>
 *   <li>冲正改成 update 原行(或用 live fee 重算)⇒ {@link #reverse_appendsReversalWithOriginalSnapshot} 红</li>
 *   <li>在冲正里加一行 offerMapper.selectById ⇒ {@link #settlementAndReversalNeverReadLiveOffer} 红</li>
 *   <li>把「只能结过完的月」闸删掉 ⇒ {@link #generateBatches_refusesCurrentMonth} 红</li>
 * </ul>
 */
class MerchantPayableServiceTest {

    private static final Long RED = 5L;
    private static final Long TOPIC = 700L;
    private static final Long CH1 = 101L;
    private static final Long MERCHANT = 500L;
    private static final Long OFFER = 7L;
    private static final BigDecimal FEE = new BigDecimal("15.00");
    private static final String AMOUNT_DIRECTION_HINT = "金额方向";
    private static final String FROZEN_HINT = "结算处理中";
    private static final String VOUCHER_HINT = "打款凭证";

    private MerchantPayableMapper payableMapper;
    private MerchantPayableBatchMapper batchMapper;
    private RedemptionMapper redemptionMapper;
    private MerchantChapterOfferMapper offerMapper;
    private IProductRoleService productRoleService;
    private ISettlementClaimService claimService;
    private MerchantPayableServiceImpl service;

    @BeforeEach
    void setUp() {
        payableMapper = mock(MerchantPayableMapper.class);
        batchMapper = mock(MerchantPayableBatchMapper.class);
        redemptionMapper = mock(RedemptionMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        productRoleService = mock(IProductRoleService.class);

        service = new MerchantPayableServiceImpl();
        ReflectionTestUtils.setField(service, "clubDirectReceivableService",
                mock(com.chengyinhub.business.service.impl.ClubDirectMerchantReceivableService.class));
        ReflectionTestUtils.setField(service, "payableMapper", payableMapper);
        ReflectionTestUtils.setField(service, "batchMapper", batchMapper);
        ReflectionTestUtils.setField(service, "redemptionMapper", redemptionMapper);
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        claimService = mock(ISettlementClaimService.class);
        when(claimService.claim(any(SettlementClaim.class))).thenAnswer(invocation -> {
            SettlementClaim claim = invocation.getArgument(0);
            claim.setId(42L);
            return claim;
        });
        ReflectionTestUtils.setField(service, "settlementClaimService", claimService);

        // 默认场景 = 计酬档 + ③-自营 = **该计提**。各用例只改自己要测的那一个变量,
        // 这样「没落账」的原因永远只有一个,不会几道闸互相接住(相邻闸抛同型异常时,
        // 拆了一道另一道会接住 ⇒ 27 条全绿的假象,本项目栽过)。
        when(redemptionMapper.selectById(RED)).thenReturn(redemption(OFFER));
        when(offerMapper.selectById(OFFER)).thenReturn(offer(TermsMode.REVSHARE, FEE));
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
    }

    private Redemption redemption(Long offerId) {
        Redemption r = new Redemption();
        r.setId(RED);
        r.setRegistrationId(900L);
        r.setChapterId(CH1);
        r.setTopicId(TOPIC);
        r.setMerchantId(MERCHANT);
        r.setOfferId(offerId);
        r.setStatus("ACTIVE");
        return r;
    }

    private MerchantChapterOffer offer(String termsMode, BigDecimal fee) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(OFFER);
        o.setTopicId(TOPIC);
        o.setChapterId(CH1);
        o.setMerchantId(MERCHANT);
        o.setTermsMode(termsMode);
        o.setPerHeadFee(fee);
        return o;
    }

    private MerchantPayable captureInserted() {
        ArgumentCaptor<MerchantPayable> c = ArgumentCaptor.forClass(MerchantPayable.class);
        verify(payableMapper).insertPayable(c.capture());
        return c.getValue();
    }

    // ------------------------------------------------- 正控

    /** ★ 计酬档核销 ⇒ 落一行 ACCRUAL,金额与费率都取**核销这一刻**那个 offer 的值。 */
    @Test
    void revshareRedemption_accruesWithFeeSnapshot() {
        service.accrueOnRedemption(RED);

        MerchantPayable p = captureInserted();
        assertEquals(RED, p.getRedemptionId());
        assertEquals(TOPIC, p.getTopicId());
        assertEquals(CH1, p.getChapterId(), "③ 的应付粒度是章 —— 一票跨N章,各章各付各的");
        assertEquals(MERCHANT, p.getMerchantMemberId());
        assertEquals(0, FEE.compareTo(p.getPerHeadFeeSnapshot()), "费率必须当场冻结");
        assertEquals(0, FEE.compareTo(p.getAmount()), "MVP:固定人头费 × 1人次");
        assertEquals(MerchantPayable.ENTRY_ACCRUAL, p.getEntryType());
        assertEquals("accrual:" + RED, p.getIdempotencyKey());
        assertEquals(Long.valueOf(42L), p.getClaimId(), "ACCRUAL 必须引用 CHAPTER_OFFER claim");
        assertEquals("ORIGIN", p.getEventKey(), "原始计提 eventKey 固定为 ORIGIN");
        ArgumentCaptor<SettlementClaim> claim = ArgumentCaptor.forClass(SettlementClaim.class);
        verify(claimService).claim(claim.capture());
        assertEquals("CHAPTER_OFFER", claim.getValue().getSettlementRoute(), "只有 REVSHARE 走付费路线");
        assertEquals("REDEMPTION", claim.getValue().getFulfillmentType(), "章节 claim 直接履约事实是 redemption");
        assertEquals(RED, claim.getValue().getFulfillmentId(), "fulfillmentId 必须是 redemption.id");
    }

    @Test
    void clubDirectRedemptionNeverEntersPlatformPayableOrPlatformClaim() {
        MerchantChapterOffer direct = offer(TermsMode.REVSHARE, FEE);
        direct.setPayerClubId(61L);
        when(offerMapper.selectById(OFFER)).thenReturn(direct);
        service.accrueOnRedemption(RED);
        com.chengyinhub.business.service.impl.ClubDirectMerchantReceivableService clubLedger =
                (com.chengyinhub.business.service.impl.ClubDirectMerchantReceivableService)
                        ReflectionTestUtils.getField(service, "clubDirectReceivableService");
        verify(clubLedger).accrue(any(Redemption.class), org.mockito.ArgumentMatchers.eq(direct));
        verify(payableMapper, never()).insertPayable(any());
        org.mockito.Mockito.verifyNoInteractions(claimService);
    }

    /**
     * ★ amount 是**绝对值**,方向由 entryType 承载(§4.1 范式)。
     * 谁把负号写进金额,净额公式 SUM(CASE...) 就会把它再取反一次 = 符号翻两次 = 账反了。
     */
    @Test
    void accrual_amountIsNeverNegative() {
        service.accrueOnRedemption(RED);

        assertTrue(captureInserted().getAmount().signum() >= 0, "金额不带正负,方向只由 entryType 表达");
    }

    // ------------------------------------------------- 闸负控

    /**
     * 🔴 权益档零现金 ⇒ 不计提。
     *
     * <p>⚠️ <b>fixture 刻意给 PERK 带上人头费</b>,尽管 enroll 会拒这种数据(「权益档不谈人头费」)。
     * 理由是负控精度:传 null 的话,拆掉 platformPays 闸后是**下游那道 fee==null 闸**把它接住
     * ⇒ 测试照样红,但红的原因与本条要证的事无关 ⇒ 这条就不再证明 platformPays 在干活。
     * 带上费用后,能拦住它的**只剩** platformPays 一道 —— 拆了它这条才会以正确的原因红。
     * (相邻多道闸抛同型异常时互相接住,本项目栽过。)
     */
    @Test
    void perkRedemption_doesNotAccrue() {
        when(offerMapper.selectById(OFFER)).thenReturn(offer(TermsMode.PERK, FEE));

        service.accrueOnRedemption(RED);

        ArgumentCaptor<SettlementClaim> claim = ArgumentCaptor.forClass(SettlementClaim.class);
        verify(claimService).claim(claim.capture());
        assertEquals("NONE", claim.getValue().getSettlementRoute(), "PERK 零现金仍占 NONE claim");

        verify(payableMapper, never()).insertPayable(any());
    }

    /** 🔴 引流档零结算 ⇒ 不计提。fixture 同样刻意带费用,理由见 {@link #perkRedemption_doesNotAccrue}。 */
    @Test
    void trafficRedemption_doesNotAccrue() {
        when(offerMapper.selectById(OFFER)).thenReturn(offer(TermsMode.TRAFFIC, FEE));

        service.accrueOnRedemption(RED);

        ArgumentCaptor<SettlementClaim> claim = ArgumentCaptor.forClass(SettlementClaim.class);
        verify(claimService).claim(claim.capture());
        assertEquals("NONE", claim.getValue().getSettlementRoute(), "TRAFFIC 零现金仍占 NONE claim");
        verify(payableMapper, never()).insertPayable(any());
    }

    /**
     * 🔴 D21:计酬档 MVP 仅 ③-自营。第三方的计酬 = 第三方间分钱 = 二清,必须等收付通(P0-43)。
     * 把 isPlatformOwned 那道闸删掉,这条必红。
     */
    @Test
    void thirdPartyTopic_doesNotAccrue() {
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(false);

        service.accrueOnRedemption(RED);

        verify(payableMapper, never()).insertPayable(any());
    }

    /**
     * ★ 档位坏了 ⇒ **抛**,不是静默跳过。
     *
     * <p>这条与上面两条「不计提」刻意分开:platformPays(null) 返 false,
     * 若不单独判 isValid,「档位坏了」就会被静默当成「这档本来就不入账」
     * ⇒ 商家在真核销、平台不记账、且零告警。
     * ⚠️ 断言错误文案指明是哪道闸 —— 相邻的闸都抛 IllegalStateException,
     * 只断言「抛了」的话,拆掉这道闸会被另一道接住而测试照样绿。
     */
    @Test
    void corruptTermsMode_throwsLoudlyInsteadOfSilentlySkipping() {
        when(offerMapper.selectById(OFFER)).thenReturn(offer("BOGUS", FEE));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.accrueOnRedemption(RED));
        assertTrue(e.getMessage().contains("档位非法"), "必须指明是档位坏了,别让它混进「这档不入账」");
        verify(payableMapper, never()).insertPayable(any());
    }

    /** ★ 计酬档没有人头费 = 算不出该付多少 ⇒ 抛,别猜。 */
    @Test
    void revshareWithoutFee_throws() {
        when(offerMapper.selectById(OFFER)).thenReturn(offer(TermsMode.REVSHARE, null));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.accrueOnRedemption(RED));
        assertTrue(e.getMessage().contains("人头费"));
    }

    /** ★ 核销流水查不到 ⇒ 抛。静默 return 会把「核销成功但没入账」变成零告警的丢账。 */
    @Test
    void missingRedemption_throws() {
        when(redemptionMapper.selectById(RED)).thenReturn(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.accrueOnRedemption(RED));
        assertTrue(e.getMessage().contains("核销流水不存在"));
    }

    // ------------------------------------------------- 幂等

    /**
     * ★ 同一笔核销重复计提 ⇒ 不再插。
     *
     * <p>⚠️ 这不是可有可无的优化:redeemChapter 的请求级幂等命中时会**带着原 redemptionId 早返回**,
     * 扫码重试就会拿同一个 id 走到这里。没有这层预检就会撞 uk_payable_idem 报 1062
     * ⇒ 整个事务回滚 ⇒ **把本该成功的幂等重试变成 500**。删掉预检,这条必红。
     */
    @Test
    void accrue_isIdempotentPerRedemption() {
        MerchantPayable existing = new MerchantPayable();
        existing.setIdempotencyKey("accrual:" + RED);
        when(payableMapper.selectByIdempotencyKey("accrual:" + RED)).thenReturn(existing);

        service.accrueOnRedemption(RED);

        verify(payableMapper, never()).insertPayable(any());
    }

    @Test
    void accrue_nullRedemptionId_isNoOp() {
        service.accrueOnRedemption(null);

        verify(payableMapper, never()).insertPayable(any());
    }

    // ------------------------------------------------- 冲正

    /**
     * ★ 撤销核销 ⇒ **追加 REVERSAL 新行**,金额用**被冲那笔的快照**,原行不动。
     *
     * <p>用 live 值重算是资损:计提时 ¥15、商家改成 ¥50、撤销时按 ¥50 冲
     * ⇒ 账上凭空多出 -35。改成 update 原行、或改成读 live fee,这条都会红。
     */
    @Test
    void reverse_appendsReversalWithOriginalSnapshot() {
        MerchantPayable accrual = new MerchantPayable();
        accrual.setRedemptionId(RED);
        accrual.setTopicId(TOPIC);
        accrual.setChapterId(CH1);
        accrual.setMerchantMemberId(MERCHANT);
        accrual.setPerHeadFeeSnapshot(FEE);
        accrual.setAmount(FEE);
        accrual.setEntryType(MerchantPayable.ENTRY_ACCRUAL);
        accrual.setCreateTime(new Date());
        when(payableMapper.selectByIdempotencyKey("accrual:" + RED)).thenReturn(accrual);

        // 商家把价改成 ¥50 —— 冲正**绝不能**认这个数
        when(offerMapper.selectById(OFFER)).thenReturn(offer(TermsMode.REVSHARE, new BigDecimal("50.00")));

        service.reverseOnRedemption(RED);

        MerchantPayable rev = captureInserted();
        assertEquals(MerchantPayable.ENTRY_REVERSAL, rev.getEntryType());
        assertEquals(0, FEE.compareTo(rev.getAmount()), "冲正金额必须是被冲那笔的快照 ¥15,不是 live 的 ¥50");
        assertEquals(0, FEE.compareTo(rev.getPerHeadFeeSnapshot()));
        assertEquals("reversal:" + RED, rev.getIdempotencyKey());
        assertEquals(MERCHANT, rev.getMerchantMemberId());
    }

    /** ★ 没计提过就没什么可冲的(权益档/引流档的核销本来就不入账)—— 空转,不抛。 */
    @Test
    void reverse_withoutAccrual_isNoOp() {
        when(payableMapper.selectByIdempotencyKey("accrual:" + RED)).thenReturn(null);

        service.reverseOnRedemption(RED);

        verify(payableMapper, never()).insertPayable(any());
    }

    /** ★ 重复冲正 ⇒ 不再插。冲两次 = 应付被冲成负数 = 平台反倒“欠”出一笔钱。 */
    @Test
    void reverse_isIdempotent() {
        MerchantPayable accrual = new MerchantPayable();
        accrual.setAmount(FEE);
        accrual.setPerHeadFeeSnapshot(FEE);
        accrual.setMerchantMemberId(MERCHANT);
        accrual.setCreateTime(new Date());
        when(payableMapper.selectByIdempotencyKey("accrual:" + RED)).thenReturn(accrual);
        MerchantPayable done = new MerchantPayable();
        when(payableMapper.selectByIdempotencyKey("reversal:" + RED)).thenReturn(done);

        service.reverseOnRedemption(RED);

        verify(payableMapper, never()).insertPayable(any());
    }

    /**
     * ★★ <b>快照不可被追溯改写的真防线</b>。
     *
     * <p>§9 要的「真改 offer 再查台账行不变」在纯 mock 下是恒真的(没有库),写出来是自欺。
     * 但那件事的**因**是可测的:追溯改写只可能经由「事后再查一次 merchant_chapter_offer」发生。
     * ⇒ 断言结算与冲正路径**一次都不碰 offerMapper**,这条路就堵死了。
     *
     * <p>🔴 负控:在 reverseOnRedemption 或 netPayable 里加一行 offerMapper.selectById(...) ⇒ 本条红。
     */
    @Test
    void settlementAndReversalNeverReadLiveOffer() {
        MerchantPayable accrual = new MerchantPayable();
        accrual.setAmount(FEE);
        accrual.setPerHeadFeeSnapshot(FEE);
        accrual.setMerchantMemberId(MERCHANT);
        accrual.setCreateTime(new Date());
        when(payableMapper.selectByIdempotencyKey("accrual:" + RED)).thenReturn(accrual);
        when(payableMapper.sumNetByMerchantAndPeriod(anyLong(), anyString())).thenReturn(FEE);

        service.reverseOnRedemption(RED);
        service.netPayable(MERCHANT, "2026-06");

        verify(offerMapper, never()).selectById(anyLong());
    }

    // ------------------------------------------------- 净额

    /** ★ 该月一行都没有时 SUM 返 NULL(不是 0)—— 别让 NULL 流进后面的运算。 */
    @Test
    void netPayable_nullSum_becomesZero() {
        when(payableMapper.sumNetByMerchantAndPeriod(MERCHANT, "2026-06")).thenReturn(null);

        assertEquals(0, BigDecimal.ZERO.compareTo(service.netPayable(MERCHANT, "2026-06")));
    }

    /**
     * ★ 净额**可能为负**(本月冲正了上月已付的账 = 商家反欠平台),别夹到 0 ——
     * 夹了就是把账做平成假的。
     */
    @Test
    void netPayable_negativeIsPreserved() {
        when(payableMapper.sumNetByMerchantAndPeriod(MERCHANT, "2026-06")).thenReturn(new BigDecimal("-15.00"));

        assertEquals(0, new BigDecimal("-15.00").compareTo(service.netPayable(MERCHANT, "2026-06")),
                "负净额是真实状态,不能夹到 0");
    }

    // ------------------------------------------------- 月结批次

    private static String ym(int monthsFromNow) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, monthsFromNow);
        return new java.text.SimpleDateFormat("yyyy-MM").format(c.getTime());
    }

    /**
     * 🔴 ★ 只能结**已经过完**的月。
     *
     * <p>批次成员是由 (商家, create_time 所在自然月) 推导的 —— 月中生成会让之后落进该月的台账行
     * **无处可去**:它们不属于任何批次,永远不会被打款,且零告警。
     * 这道闸是那个推导能成立的前提。删掉它,本条必红。
     */
    @Test
    void generateBatches_refusesCurrentMonth() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.generateMonthlyBatches(ym(0)));
        assertTrue(e.getMessage().contains("只能结已过完的月份"));
        verify(batchMapper, never()).insertBatch(any());
    }

    @Test
    void generateBatches_refusesFutureMonth() {
        assertThrows(IllegalStateException.class, () -> service.generateMonthlyBatches(ym(1)));
        verify(batchMapper, never()).insertBatch(any());
    }

    /** ★ 生成的批次:金额 = 冻结的净额;状态 = 待结;发票轴独立起步于未开票。 */
    @Test
    void generateBatches_freezesNetAmountAndStartsPending() {
        String period = ym(-1);
        when(payableMapper.selectMerchantIdsByPeriod(period)).thenReturn(Arrays.asList(MERCHANT));
        when(batchMapper.selectByMerchantAndPeriod(MERCHANT, period)).thenReturn(null);
        when(payableMapper.sumNetByMerchantAndPeriod(MERCHANT, period)).thenReturn(new BigDecimal("45.00"));

        List<MerchantPayableBatch> created = service.generateMonthlyBatches(period);

        assertEquals(1, created.size());
        ArgumentCaptor<MerchantPayableBatch> c = ArgumentCaptor.forClass(MerchantPayableBatch.class);
        verify(batchMapper).insertBatch(c.capture());
        MerchantPayableBatch b = c.getValue();
        assertEquals(MERCHANT, b.getMerchantMemberId());
        assertEquals(period, b.getPeriodYm());
        assertEquals(0, new BigDecimal("45.00").compareTo(b.getAmountTotal()));
        assertEquals(MerchantPayableBatch.STATUS_PENDING, b.getStatus());
        assertEquals(MerchantPayableBatch.INVOICE_NONE, b.getInvoiceStatus(),
                "发票是独立的一条轴,起步必须是未开票 —— 混进主状态机就表达不了「已打款但未开票」");
    }

    /** ★ 已生成过的商家跳过(不是报错):运营重复点「生成上月批次」是常态,不该因为一家已有就整批拒。 */
    @Test
    void generateBatches_skipsAlreadyGenerated() {
        String period = ym(-1);
        when(payableMapper.selectMerchantIdsByPeriod(period)).thenReturn(Arrays.asList(MERCHANT));
        when(batchMapper.selectByMerchantAndPeriod(MERCHANT, period)).thenReturn(new MerchantPayableBatch());

        List<MerchantPayableBatch> created = service.generateMonthlyBatches(period);

        assertTrue(created.isEmpty());
        verify(batchMapper, never()).insertBatch(any());
    }

    /**
     * 🔴 ★ 并发/重复点击:pre-check 说「没生成」但 insert 撞 uk_batch_merchant_period
     * (另一次运行在 check 与 insert 之间已插入同 (商家,期) 批次)⇒ 跳过那一家、其余照常生成,
     * <b>别让 DuplicateKeyException 冒出去把整批 @Transactional 回滚成 500</b>。
     * 「先查后插」是读-判-写竞态,库层的 uk 才是真防线(见 mapper 注释)。
     *
     * <p>🔴 负控:把 insertBatch 外的 {@code try/catch(DuplicateKeyException)} 删掉 ⇒ 本条抛异常红。
     */
    @Test
    void generateBatches_concurrentUniqueViolation_skipsInsteadOfFailingWholeRun() {
        String period = ym(-1);
        Long merchantA = 501L, merchantB = 502L;
        when(payableMapper.selectMerchantIdsByPeriod(period)).thenReturn(Arrays.asList(merchantA, merchantB));
        when(batchMapper.selectByMerchantAndPeriod(anyLong(), anyString())).thenReturn(null); // 两家都过了 pre-check
        when(payableMapper.sumNetByMerchantAndPeriod(anyLong(), anyString())).thenReturn(new BigDecimal("10.00"));
        // A 正常插入;B 撞 uk(并发已被别的运行插过)
        when(batchMapper.insertBatch(any())).thenAnswer(inv -> {
            MerchantPayableBatch b = inv.getArgument(0);
            if (merchantB.equals(b.getMerchantMemberId())) {
                throw new DuplicateKeyException("Duplicate entry for key 'uk_batch_merchant_period'");
            }
            return 1;
        });

        List<MerchantPayableBatch> created = service.generateMonthlyBatches(period);

        assertEquals(1, created.size(), "撞 uk 的那家被跳过,其余照常生成 —— 不是整批 500");
        assertEquals(merchantA, created.get(0).getMerchantMemberId(), "成功生成的只应含没撞 uk 的那家");
    }

    /**
     * 🔴 ★ 冲正的红票判定按<b>被冲那笔 create_time</b>用 SQL date_format 定位批次
     * ({@code selectByMerchantAndAccrualTime}),与批次成员分桶(SQL date_format)<b>同源</b>;
     * 绝不用 Java {@code periodOf}(SimpleDateFormat + JVM 时区)+ selectByMerchantAndPeriod ——
     * 那会让月边界那笔算出与 SQL 不同的月 ⇒ 查不到批次 ⇒ 漏「已开票批次需红字发票」提醒。
     *
     * <p>🔴 负控:把冲正里改回 {@code selectByMerchantAndPeriod(mid, periodOf(createTime))} ⇒ 本条红。
     */
    @Test
    void reverse_locatesBatchByAccrualTimeInSql_notJavaPeriod() {
        Date accrualTime = new Date();
        MerchantPayable accrual = new MerchantPayable();
        accrual.setAmount(FEE);
        accrual.setPerHeadFeeSnapshot(FEE);
        accrual.setMerchantMemberId(MERCHANT);
        accrual.setCreateTime(accrualTime);
        when(payableMapper.selectByIdempotencyKey("accrual:" + RED)).thenReturn(accrual);

        service.reverseOnRedemption(RED);

        verify(batchMapper).selectByMerchantAndAccrualTime(MERCHANT, accrualTime);
        verify(batchMapper, never()).selectByMerchantAndPeriod(anyLong(), anyString());
    }

    // ------------------------------------------------- 批次状态流转(四个 CAS)

    /**
     * ★★ <b>返回 0 行必须变成 false,不能被吞成 true</b>。
     *
     * <p>这四个 update 都带前置状态条件。返回 0 <b>不是「没事发生」,是「你以为的前置状态不成立」</b>:
     * <ul>
     *   <li>confirm 返 0 = 该批次已确认过或已打款;</li>
     *   <li><b>markPaid 返 0 = 没对账就想记打款</b> —— SQL 里的 {@code status='CONFIRMED'} 前置是
     *       「先对账再打款」的<b>唯一强制点</b>;</li>
     *   <li>markInvoiceRed 返 0 = 没开过蓝票,谈不上红冲。</li>
     * </ul>
     * 谁把 {@code > 0} 改成恒 true(或让 service 吞掉 0),运营就会看到「打款已记录」而实际什么都没变
     * ⇒ 一笔钱在账上永远停在待结,且零告警。本组四条就是守这个的。
     */
    @Test
    void batchTransitions_zeroRowsMeansFalse() {
        MerchantPayableBatch confirmed = new MerchantPayableBatch();
        confirmed.setId(1L);
        confirmed.setStatus(MerchantPayableBatch.STATUS_CONFIRMED);
        confirmed.setConfirmedBy("someone-else"); // ≠ "op",不触发双人复核异常,单纯测 CAS 0 行
        confirmed.setAmountTotal(FEE);
        when(batchMapper.selectById(1L)).thenReturn(confirmed);
        when(batchMapper.confirm(anyLong(), anyString())).thenReturn(0);
        when(batchMapper.markPaid(anyLong(), anyString(), anyString())).thenReturn(0);
        when(batchMapper.markInvoiceIssued(anyLong(), anyString())).thenReturn(0);
        when(batchMapper.markInvoiceRed(anyLong(), anyString())).thenReturn(0);

        assertFalse(service.confirmBatch(1L, "op"), "0 行 = 该批次已不是待结态,必须报失败");
        assertFalse(service.markBatchPaid(1L, "op", "V1"), "0 行 = 没对账就想记打款,必须报失败");
        assertFalse(service.markBatchInvoiceIssued(1L, "INV1"), "0 行 = 已开过票,必须报失败");
        assertFalse(service.markBatchInvoiceRed(1L, "RED1"), "0 行 = 没开过蓝票,必须报失败");
    }

    /** ★ 1 行 = 前置状态成立、置位成功。 */
    @Test
    void batchTransitions_oneRowMeansTrue() {
        MerchantPayableBatch confirmed = new MerchantPayableBatch();
        confirmed.setId(1L);
        confirmed.setStatus(MerchantPayableBatch.STATUS_CONFIRMED);
        confirmed.setConfirmedBy("someone-else"); // ≠ "op",打款人与确认人为不同人,双人复核放行
        confirmed.setAmountTotal(FEE);
        when(batchMapper.selectById(1L)).thenReturn(confirmed);
        when(batchMapper.confirm(anyLong(), anyString())).thenReturn(1);
        when(batchMapper.markPaid(anyLong(), anyString(), anyString())).thenReturn(1);
        when(batchMapper.markInvoiceIssued(anyLong(), anyString())).thenReturn(1);
        when(batchMapper.markInvoiceRed(anyLong(), anyString())).thenReturn(1);

        assertTrue(service.confirmBatch(1L, "op"));
        assertTrue(service.markBatchPaid(1L, "op", "V1"));
        assertTrue(service.markBatchInvoiceIssued(1L, "INV1"));
        assertTrue(service.markBatchInvoiceRed(1L, "RED1"));
    }

    @Test
    void batchProgression_zeroAmountRejectsConfirmPaidAndBlueInvoice() {
        MerchantPayableBatch batch = payableBatch(BigDecimal.ZERO, 0);
        when(batchMapper.selectById(1L)).thenReturn(batch);

        assertProgressionGuard(() -> service.confirmBatch(1L, "ops"), AMOUNT_DIRECTION_HINT);
        assertProgressionGuard(() -> service.markBatchPaid(1L, "finance2", "V1"), AMOUNT_DIRECTION_HINT);
        assertProgressionGuard(() -> service.markBatchInvoiceIssued(1L, "INV1"), AMOUNT_DIRECTION_HINT);
        verify(batchMapper, never()).confirm(anyLong(), anyString());
        verify(batchMapper, never()).markPaid(anyLong(), anyString(), anyString());
        verify(batchMapper, never()).markInvoiceIssued(anyLong(), anyString());
    }

    @Test
    void batchProgression_negativeAmountRejectsConfirmPaidAndBlueInvoice() {
        MerchantPayableBatch batch = payableBatch(new BigDecimal("-0.01"), 0);
        when(batchMapper.selectById(1L)).thenReturn(batch);

        assertProgressionGuard(() -> service.confirmBatch(1L, "ops"), AMOUNT_DIRECTION_HINT);
        assertProgressionGuard(() -> service.markBatchPaid(1L, "finance2", "V1"), AMOUNT_DIRECTION_HINT);
        assertProgressionGuard(() -> service.markBatchInvoiceIssued(1L, "INV1"), AMOUNT_DIRECTION_HINT);
        verify(batchMapper, never()).confirm(anyLong(), anyString());
        verify(batchMapper, never()).markPaid(anyLong(), anyString(), anyString());
        verify(batchMapper, never()).markInvoiceIssued(anyLong(), anyString());
    }

    @Test
    void batchProgression_frozenBatchRejectsWithoutLeakingReason() {
        MerchantPayableBatch batch = payableBatch(FEE, 1);
        batch.setFrozenReason("内部风控: 投诉人手机号 13800000000");
        when(batchMapper.selectById(1L)).thenReturn(batch);

        IllegalStateException confirm = assertProgressionGuard(() -> service.confirmBatch(1L, "ops"), FROZEN_HINT);
        IllegalStateException paid = assertProgressionGuard(() -> service.markBatchPaid(1L, "finance2", "V1"), FROZEN_HINT);
        IllegalStateException invoice = assertProgressionGuard(() -> service.markBatchInvoiceIssued(1L, "INV1"), FROZEN_HINT);
        assertFalse(confirm.getMessage().contains(batch.getFrozenReason()), "冻结拒绝不能透传内部 frozenReason");
        assertFalse(paid.getMessage().contains(batch.getFrozenReason()), "冻结拒绝不能透传内部 frozenReason");
        assertFalse(invoice.getMessage().contains(batch.getFrozenReason()), "冻结拒绝不能透传内部 frozenReason");
        verify(batchMapper, never()).confirm(anyLong(), anyString());
        verify(batchMapper, never()).markPaid(anyLong(), anyString(), anyString());
        verify(batchMapper, never()).markInvoiceIssued(anyLong(), anyString());
    }

    @Test
    void markBatchPaid_rejectsBlankVoucherBeforePaidFact() {
        assertProgressionGuard(() -> service.markBatchPaid(1L, "finance2", null), VOUCHER_HINT);
        assertProgressionGuard(() -> service.markBatchPaid(1L, "finance2", "   "), VOUCHER_HINT);
        verify(batchMapper, never()).markPaid(anyLong(), anyString(), anyString());
    }

    // ===== P块:markBatchPaid 双人复核(paidBy ≠ confirmedBy) =====

    @Test
    void markBatchPaid_sameOperatorAsConfirmer_throws() {
        MerchantPayableBatch confirmed = new MerchantPayableBatch();
        confirmed.setId(1L);
        confirmed.setStatus(MerchantPayableBatch.STATUS_CONFIRMED);
        confirmed.setConfirmedBy("finance1");
        confirmed.setAmountTotal(FEE);
        when(batchMapper.selectById(1L)).thenReturn(confirmed);

        assertThrows(IllegalStateException.class, () -> service.markBatchPaid(1L, "finance1", "V1"),
                "对账确认人与打款人是同一人,必须拦 —— 否则单账号可自审自批打款");
        verify(batchMapper, never()).markPaid(anyLong(), anyString(), anyString());
    }

    @Test
    void markBatchPaid_distinctOperator_succeeds() {
        MerchantPayableBatch confirmed = new MerchantPayableBatch();
        confirmed.setId(1L);
        confirmed.setStatus(MerchantPayableBatch.STATUS_CONFIRMED);
        confirmed.setConfirmedBy("finance1");
        confirmed.setAmountTotal(FEE);
        when(batchMapper.selectById(1L)).thenReturn(confirmed);
        when(batchMapper.markPaid(1L, "finance2", "V1")).thenReturn(1);

        assertTrue(service.markBatchPaid(1L, "finance2", "V1"));
    }

    @Test
    void markBatchPaid_batchNotFound_returnsFalse() {
        when(batchMapper.selectById(1L)).thenReturn(null);

        assertFalse(service.markBatchPaid(1L, "finance2", "V1"));
        verify(batchMapper, never()).markPaid(anyLong(), anyString(), anyString());
    }

    // ===== P块冻结:暂停对账确认/打款,不动金额 =====

    @Test
    void freezeBatch_recordsReasonAndOperator() {
        MerchantPayableBatch b = new MerchantPayableBatch();
        b.setId(1L);
        b.setStatus(MerchantPayableBatch.STATUS_PENDING);
        when(batchMapper.selectById(1L)).thenReturn(b);
        when(batchMapper.freeze(any())).thenReturn(1);

        boolean ok = service.freezeBatch(1L, "riskops", "疑似虚假核销");

        assertTrue(ok);
        ArgumentCaptor<MerchantPayableBatch> cap = ArgumentCaptor.forClass(MerchantPayableBatch.class);
        verify(batchMapper).freeze(cap.capture());
        assertEquals(1L, cap.getValue().getId());
        assertEquals("疑似虚假核销", cap.getValue().getFrozenReason());
        assertEquals("riskops", cap.getValue().getFrozenBy());
    }

    @Test
    void freezeBatch_notFound_throws() {
        when(batchMapper.selectById(1L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.freezeBatch(1L, "riskops", "疑似虚假核销"));
        verify(batchMapper, never()).freeze(any());
    }

    @Test
    void unfreezeBatch_notFrozen_returnsFalse() {
        MerchantPayableBatch b = new MerchantPayableBatch();
        b.setId(1L);
        when(batchMapper.selectById(1L)).thenReturn(b);
        when(batchMapper.unfreeze(1L)).thenReturn(0);

        assertFalse(service.unfreezeBatch(1L, "riskops"));
    }

    private MerchantPayableBatch payableBatch(BigDecimal amountTotal, Integer frozen) {
        MerchantPayableBatch batch = new MerchantPayableBatch();
        batch.setId(1L);
        batch.setStatus(MerchantPayableBatch.STATUS_CONFIRMED);
        batch.setInvoiceStatus(MerchantPayableBatch.INVOICE_NONE);
        batch.setConfirmedBy("finance1");
        batch.setAmountTotal(amountTotal);
        batch.setFrozen(frozen);
        return batch;
    }

    private IllegalStateException assertProgressionGuard(Executable action, String expectedHint) {
        IllegalStateException e = assertThrows(IllegalStateException.class, action,
                "批次推进必须被" + expectedHint + "守卫拒绝");
        assertTrue(e.getMessage() != null && e.getMessage().contains(expectedHint),
                "错误文案必须指向" + expectedHint + "守卫,实际: " + e.getMessage());
        return e;
    }

    /**
     * ★★ <b>本服务只记账,不打款</b>。
     *
     * <p>markBatchPaid 记的是「钱已经从对公账户打出去了」这个<b>事实</b>,不是「把钱打出去」这个<b>动作</b>。
     * 商家在 ③ 里是<b>供应商</b>,走对公打款 + 开票,<b>不是余额</b>。
     * 这里一旦出现 addBalance / 转账,就是在新造第三个打款方 —— 那正是 PR#78 刚根治掉的病
     * (它把打款能力收口到系统1,实测 SettlementEntryServiceImpl 内 addBalance 命中数 = 0)。
     *
     * <p>⚠️ 这条守的是**依赖面**:本类只有台账/批次/核销/供给/角色五个 mapper,
     * 没有任何能加余额的东西。谁要接打款,得先往这里注入一个新依赖 —— 那时这条会红,
     * 逼他停下来读一遍 PR#78 为什么存在。
     *
     * <p>⚠️ 判据是**集合相等**,不是列表相等:{@code getDeclaredFields()} 的 javadoc 明写
     * 「are not sorted and are not in any particular order」。按顺序断言在 HotSpot 上恰好能过,
     * 但换 JVM、甚至只是有人调了下字段声明顺序就会红 —— 而它红的时候传达的信息是
     * 「你新造了打款方」,完全是误导。<b>守卫红错原因比不红更坏。</b>
     */
    @Test
    void service_hasNoPayoutCapability() {
        java.util.Set<String> injectable = new java.util.HashSet<String>();
        for (java.lang.reflect.Field f : MerchantPayableServiceImpl.class.getDeclaredFields()) {
            if (f.isSynthetic() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            injectable.add(f.getType().getSimpleName());
        }
        java.util.Set<String> directDependencies = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : com.chengyinhub.business.service.impl.ClubDirectMerchantReceivableService.class.getDeclaredFields())
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) directDependencies.add(f.getType().getSimpleName());
        assertEquals(new java.util.HashSet<>(Arrays.asList("ClubDirectMerchantReceivableMapper", "ClubMapper",
                "ISettlementClaimService", "ITopicOperationsScopeAuthorizer")), directDependencies,
                "俱乐部直付记录也不得接入平台钱包或转账能力");
        assertEquals(
                new java.util.HashSet<String>(Arrays.asList(
                        "MerchantPayableMapper", "MerchantPayableBatchMapper",
                        "RedemptionMapper", "MerchantChapterOfferMapper", "IProductRoleService",
                        "ISettlementClaimService", "ClubDirectMerchantReceivableService")),
                injectable,
                "本服务只记账不打款。多出来的依赖若能加余额/转账 = 新造第三个打款方(PR#78 刚根治掉的病)。"
                        + "真要加依赖,先读 IMerchantPayableService 类注释的三条边界,再改这条断言。");
    }
}
