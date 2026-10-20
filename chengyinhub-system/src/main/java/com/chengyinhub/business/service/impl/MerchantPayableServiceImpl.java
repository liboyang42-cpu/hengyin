package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.MerchantPayable;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.MerchantPayableMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.service.IMerchantPayableService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ISettlementClaimService;
import com.chengyinhub.business.service.support.MakerCheckerGuard;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.utils.StringUtils;

/**
 * ③ 计酬档平台应付台账实现(施工图 §5)。
 *
 * <p>⚠️ 本类<b>只记账,不打款</b>。打款回系统1(PR#78 已把打款能力收口到那里),
 * 这里出现任何 {@code addBalance} 都是在新造第三个打款方 —— 那正是 #78 刚根治掉的病。
 */
@Service
public class MerchantPayableServiceImpl implements IMerchantPayableService {

    private static final Logger logger = LoggerFactory.getLogger(MerchantPayableServiceImpl.class);

    /** 自然月格式,同时是批次归属的推导口径(见 MerchantPayable 类注释) */
    private static final String PERIOD_PATTERN = "yyyy-MM";

    private static final String NON_POSITIVE_BATCH_MESSAGE = "批次金额方向非法: amount_total 必须大于 0 才能确认/打款/开蓝票";
    private static final String FROZEN_BATCH_MESSAGE = "批次结算处理中,暂不可确认/打款/开票";
    private static final String PAY_VOUCHER_REQUIRED_MESSAGE = "打款凭证号不能为空: PAID 必须同时落 paidAt 与 payVoucherNo";

    @Autowired
    private MerchantPayableMapper payableMapper;

    @Autowired
    private MerchantPayableBatchMapper batchMapper;

    @Autowired
    private RedemptionMapper redemptionMapper;

    @Autowired
    private MerchantChapterOfferMapper offerMapper;

    @Autowired
    private IProductRoleService productRoleService;

    @Autowired
    private ISettlementClaimService settlementClaimService;

    @Autowired
    private ClubDirectMerchantReceivableService clubDirectReceivableService;

    static String accrualKey(Long redemptionId) {
        return "accrual:" + redemptionId;
    }

    static String reversalKey(Long redemptionId) {
        return "reversal:" + redemptionId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void accrueOnRedemption(Long redemptionId) {
        if (redemptionId == null) {
            return;
        }

        Redemption r = redemptionMapper.selectById(redemptionId);
        if (r == null) {
            // 调用方刚拿到这个 id(要么是新插的,要么是幂等命中的旧流水)⇒ 查不到 = 真出事了。
            // 不静默 return:那会让「核销成功但没入账」变成零告警的丢账。
            throw new IllegalStateException("应付计提失败:核销流水不存在 redemptionId=" + redemptionId);
        }

        MerchantChapterOffer offer = r.getOfferId() == null ? null : offerMapper.selectById(r.getOfferId());
        if (offer == null) {
            // ③ 的核销必然命中某个 offer(redeemChapter 的 findLiveOffer 拿不到就已经抛了)。
            // 走到这儿 = offer 被删了 / offerId 没落库 ⇒ 算不出该付多少钱,别猜。
            throw new IllegalStateException("应付计提失败:核销流水没有对应的商家供给 redemptionId=" + redemptionId);
        }

        // 档位非法 = 数据损坏(enroll 的 isValid 校验 + DDL 的 ck_offer_terms 双重保证不该发生)。
        // ⚠️ 必须与下面的 platformPays 分开判:platformPays(null) 返 false,
        //    混在一起就会把「档位坏了」静默当成「这档本来就不入账」⇒ 商家白干且零告警。
        if (!TermsMode.isValid(offer.getTermsMode())) {
            throw new IllegalStateException("应付计提失败:供给档位非法 offerId=" + offer.getId()
                    + " termsMode=" + offer.getTermsMode());
        }

        if (offer.getPayerClubId() != null) {
            clubDirectReceivableService.accrue(r, offer);
            return;
        }
        String route = TermsMode.platformPays(offer.getTermsMode()) ? "CHAPTER_OFFER" : "NONE";
        SettlementClaim claim = settlementClaimService.claim(SettlementClaim.request(
                "TOPIC_EDITION", r.getTopicId(), "REDEMPTION", r.getId(),
                "MERCHANT", r.getMerchantId(), "CHAPTER_SUPPLY", route,
                "MERCHANT_CHAPTER_OFFER", offer.getId()));

        // ★ 闸一:只有计酬档要平台掏钱。权益档零现金、引流档零结算 —— 它们正常占 NONE claim,不入账。
        //    (TermsMode.platformPays 在此之前是生产零调用方,本方法给它接上第一个。)
        if (!TermsMode.platformPays(offer.getTermsMode())) {
            return;
        }

        // ★ 闸二:D21 —— 计酬档 MVP **仅 ③-自营**。第三方的计酬 = 第三方间分钱 = 二清,须等收付通。
        //
        // ⚠️ 正常不可达:enroll 已经用同一个 isPlatformOwned 拦过一次,REVSHARE 的 offer 只可能
        //    挂在自营主题上。可达的唯一路径是**入驻之后运营改判了内容槽**(P0-35 允许)。
        // ⚠️ 所以这里 return 之前必须 warn:此时商家在真核销、平台却不记账 = 商家白干。
        //    静默跳过就是本项目反复栽的那个「零告警」病。
        if (!productRoleService.isPlatformOwned(r.getTopicId())) {
            logger.warn("[③应付] 计酬档核销但主题非自营,不计提 —— 多半是入驻后内容槽被改判(P0-35)。"
                    + "商家正在真核销却拿不到钱,需人工处置。redemptionId={} topicId={} merchantId={}",
                    redemptionId, r.getTopicId(), r.getMerchantId());
            return;
        }

        if (r.getChapterId() == null) {
            // ③ 的应付粒度是「章」(一票跨 N 章,各章各付各的)。没有章 = 算不出这笔该记给谁的哪一段。
            throw new IllegalStateException("应付计提失败:③ 核销流水缺章节 redemptionId=" + redemptionId);
        }

        BigDecimal fee = offer.getPerHeadFee();
        if (fee == null) {
            // enroll 的「请填写每人次的计酬金额」+ DDL 的 ck_offer_terms 双重保证不该发生。
            throw new IllegalStateException("应付计提失败:计酬档供给没有人头费 offerId=" + offer.getId());
        }

        String key = accrualKey(redemptionId);
        // ⚠️ 这层预检不是「读-判-写竞态」的偷懒版,它有个非它不可的理由:
        //    redeemChapter 的请求级幂等命中时会**带着原 redemptionId 早返回**(它不重插流水),
        //    于是扫码重试会拿同一个 id 走到这里。没有这层就会撞 uk_payable_idem 报 1062
        //    ⇒ 整个事务回滚 ⇒ 把本该成功的幂等重试变成 500。
        //    真正的并发防线仍是 uk_payable_idem —— 两个并发同 key 时一个赢、另一个 1062 回滚,
        //    与 redeemChapter 靠 uk_redemption_idem_active 兜的那条路同型。
        if (payableMapper.selectByIdempotencyKey(key) != null) {
            return;
        }

        MerchantPayable p = new MerchantPayable();
        p.setClaimId(claim.getId());
        p.setEventKey("ORIGIN");
        p.setRedemptionId(redemptionId);
        p.setTopicId(r.getTopicId());
        p.setChapterId(r.getChapterId());
        p.setMerchantMemberId(r.getMerchantId());
        // ★ 本任务的核心价值就在这一行:把**核销这一刻**的费率冻住。
        //   不存快照而在结算时 join merchant_chapter_offer 现算 ⇒ 商家改价会追溯改写全部历史应付
        //   ⇒ 与 P0-28「拍板4:分账与定价同冻结」是同一个病,本项目已经栽过一次。
        p.setPerHeadFeeSnapshot(fee);
        // MVP 形态:固定人头费 × 1 人次(一笔核销 = 一个玩家在一家店的一章)。
        // ⚠️ 绝对值,不带正负 —— 方向由 entryType 承载。
        p.setAmount(fee);
        p.setEntryType(MerchantPayable.ENTRY_ACCRUAL);
        p.setIdempotencyKey(key);
        p.setCreateTime(new Date());
        payableMapper.insertPayable(p);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reverseOnRedemption(Long redemptionId) {
        if (redemptionId == null) {
            return;
        }

        clubDirectReceivableService.reverse(redemptionId);
        String accrualKey = accrualKey(redemptionId);
        MerchantPayable accrual = payableMapper.selectByIdempotencyKey(accrualKey);
        if (accrual == null) {
            // 没计提过就没什么可冲的 —— 权益档/引流档的核销本来就不入账,撤销它们走到这儿是正常的。
            return;
        }

        String key = reversalKey(redemptionId);
        if (payableMapper.selectByIdempotencyKey(key) != null) {
            // 已冲过。reverseRedemption 自己也有 CAS 兜重复撤销,这里是第二道。
            return;
        }

        MerchantPayable rev = new MerchantPayable();
        rev.setClaimId(accrual.getClaimId());
        rev.setEventKey("REDEMPTION_REVERSED:" + redemptionId);
        rev.setRedemptionId(redemptionId);
        rev.setTopicId(accrual.getTopicId());
        rev.setChapterId(accrual.getChapterId());
        rev.setMerchantMemberId(accrual.getMerchantMemberId());
        // ★ 用**被冲那笔的快照**,不是 live 值:商家中途改价不该影响冲正的金额,
        //   否则「计提 ¥5 / 冲正 ¥50」会把账冲出一个 -45 的窟窿。
        rev.setPerHeadFeeSnapshot(accrual.getPerHeadFeeSnapshot());
        rev.setAmount(accrual.getAmount());
        rev.setEntryType(MerchantPayable.ENTRY_REVERSAL);
        rev.setIdempotencyKey(key);
        rev.setCreateTime(new Date());
        // ⚠️ insert 一条新行,**绝不 update 原行** —— 原行记的是「这笔核销当时发生过、费率是 X」,
        //    那是历史事实。改了它,已开票批次的金额就与发票对不上,且再也查不出发生过什么。
        payableMapper.insertPayable(rev);

        // ⚠️ 已开票批次的冲正,税务上必须走**红字发票**(法定,不是流程偏好)。
        //    本任务只留位与状态机,不实现税务对接 —— 红字发票由运营人工开具并回填 red_invoice_no。
        //    ⚠️ 判据看的是**被冲那笔所属批次**的发票状态,不是当前月:
        //       7 月的账 8 月才被撤销,要红冲的是 7 月那张票。
        // ⚠️ 用 SQL date_format 算被冲那笔的所属月,与批次成员的分桶口径(selectMerchantIdsByPeriod /
        //    sumNet 都是 date_format(create_time))**同源**;别用 Java periodOf(SimpleDateFormat + JVM
        //    时区)—— 它跟 MySQL 会话时区对月边界的那笔可能算出不同月,导致这里查不到批次、漏红冲提醒。
        MerchantPayableBatch batch = batchMapper.selectByMerchantAndAccrualTime(
                accrual.getMerchantMemberId(), accrual.getCreateTime());
        if (batch != null && MerchantPayableBatch.INVOICE_ISSUED.equals(batch.getInvoiceStatus())) {
            logger.warn("[③应付] 冲正命中**已开票**批次,需人工开红字发票并回填 —— "
                            + "batchId={} periodYm={} merchantMemberId={} redemptionId={} 冲正金额={}",
                    batch.getId(), batch.getPeriodYm(), accrual.getMerchantMemberId(),
                    redemptionId, accrual.getAmount());
        }
    }

    @Override
    public BigDecimal netPayable(Long merchantMemberId, String periodYm) {
        BigDecimal net = payableMapper.sumNetByMerchantAndPeriod(merchantMemberId, periodYm);
        // 该月一行都没有时 SUM 返 NULL(不是 0)—— 别让它流进后面的 BigDecimal 运算。
        return net == null ? BigDecimal.ZERO : net;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<MerchantPayableBatch> generateMonthlyBatches(String periodYm) {
        // ⚠️ 只能结**已经过完**的月份。批次成员由 (商家, create_time 所在自然月) 推导,
        //    月中生成会让之后落进该月的台账行**无处可去** —— 它们不属于任何批次,永远不会被打款,
        //    且零告警。这道闸是那个推导能成立的前提,别放宽。
        String current = periodOf(new Date());
        if (periodYm == null || periodYm.compareTo(current) >= 0) {
            throw new IllegalStateException("只能结已过完的月份,当前是 " + current + ",不能结 " + periodYm);
        }

        List<MerchantPayableBatch> created = new ArrayList<>();
        for (Long merchantMemberId : payableMapper.selectMerchantIdsByPeriod(periodYm)) {
            if (batchMapper.selectByMerchantAndPeriod(merchantMemberId, periodYm) != null) {
                // 已生成过 —— 跳过而不是报错:运营重复点「生成上月批次」是常态,
                // 不该因为其中一家已有批次就把整批拒掉。库层的 uk_batch_merchant_period 是真防线。
                continue;
            }
            MerchantPayableBatch b = new MerchantPayableBatch();
            b.setMerchantMemberId(merchantMemberId);
            b.setPeriodYm(periodYm);
            // 冻结净额:这是实际要打出去的那个数。⚠️ 可能为负(本月冲正了上月已付的账),别夹到 0。
            b.setAmountTotal(netPayable(merchantMemberId, periodYm));
            b.setStatus(MerchantPayableBatch.STATUS_PENDING);
            b.setInvoiceStatus(MerchantPayableBatch.INVOICE_NONE);
            b.setCreateTime(new Date());
            // 先查后插是读-判-写竞态:并发/重复点击时两次运行都过了上面的「已存在?」判断,
            // 第二次 insert 撞 uk_batch_merchant_period。库层是真防线(见 mapper 注释),这里把它
            // 当「已被另一次生成」跳过,而不是让异常冒出去把整批 @Transactional 回滚成 500。
            try {
                batchMapper.insertBatch(b);
            } catch (DuplicateKeyException e) {
                logger.warn("[③应付] 月结批次并发命中 uk 冲突,跳过已存在批次 —— merchantMemberId={} periodYm={}",
                        merchantMemberId, periodYm);
                continue;
            }
            created.add(b);
        }
        return created;
    }

    @Override
    public List<MerchantPayableBatch> listBatches(String periodYm) {
        return batchMapper.selectByPeriod(periodYm);
    }

    // ⚠️ 下面四个都是**条件置位(CAS)**,返回 false 不是「没事发生」,
    //    是「你以为的前置状态不成立」。调用方必须当失败报给用户 —— 静默当成功会让
    //    「没对账就打款了」「重复记打款把留痕冲掉」这类事零告警地过去。

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmBatch(Long batchId, String operator) {
        MerchantPayableBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            return false;
        }
        requireProgressionAllowed(batch);
        return batchMapper.confirm(batchId, operator) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markBatchPaid(Long batchId, String operator, String payVoucherNo) {
        // ⚠️ 只记事实,不转账。SQL 里的 status='CONFIRMED' 前置是「先对账再打款」的唯一强制点。
        // ★双人复核:打款人必须≠对账确认人,与 RefundServiceImpl/CashWithdrawServiceImpl 同型闸,
        //   堵「单个后台账号自审自批把钱打出去」——此前 confirm/markPaid 之间完全没有这道闸,是真缺口。
        if (StringUtils.isBlank(payVoucherNo)) {
            throw new IllegalStateException(PAY_VOUCHER_REQUIRED_MESSAGE);
        }
        MerchantPayableBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            return false;
        }
        requireProgressionAllowed(batch);
        // The SQL transition also requires CONFIRMED. Do not let a PENDING read skip
        // maker/checker validation and then succeed after a concurrent confirmation.
        if (!MerchantPayableBatch.STATUS_CONFIRMED.equals(batch.getStatus())) {
            return false;
        }
        MakerCheckerGuard.requireDistinct(batch.getConfirmedBy(), operator);
        return batchMapper.markPaid(batchId, operator, payVoucherNo) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markBatchInvoiceIssued(Long batchId, String invoiceNo) {
        MerchantPayableBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            return false;
        }
        requireProgressionAllowed(batch);
        return batchMapper.markInvoiceIssued(batchId, invoiceNo) > 0;
    }

    private void requireProgressionAllowed(MerchantPayableBatch batch) {
        if (Integer.valueOf(1).equals(batch.getFrozen())) {
            throw new IllegalStateException(FROZEN_BATCH_MESSAGE);
        }
        if (batch.getAmountTotal() == null || batch.getAmountTotal().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(NON_POSITIVE_BATCH_MESSAGE);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markBatchInvoiceRed(Long batchId, String redInvoiceNo) {
        return batchMapper.markInvoiceRed(batchId, redInvoiceNo) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean freezeBatch(Long batchId, String operator, String reason) {
        MakerCheckerGuard.requireActor(operator, "operator");
        if (batchMapper.selectById(batchId) == null) {
            throw new IllegalStateException("批次不存在");
        }
        MerchantPayableBatch b = new MerchantPayableBatch();
        b.setId(batchId);
        b.setFrozenReason(reason);
        b.setFrozenBy(operator);
        return batchMapper.freeze(b) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean unfreezeBatch(Long batchId, String operator) {
        MakerCheckerGuard.requireActor(operator, "operator");
        if (batchMapper.selectById(batchId) == null) {
            throw new IllegalStateException("批次不存在");
        }
        return batchMapper.unfreeze(batchId) > 0;
    }

    /** 把时刻映射成它所属的自然月 —— 批次归属的唯一口径,别在别处重写一遍。 */
    private static String periodOf(Date when) {
        return new SimpleDateFormat(PERIOD_PATTERN).format(when);
    }
}
