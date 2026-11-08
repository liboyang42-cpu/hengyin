package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.MarketingAttribution;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RevenueAccrualRequest;
import com.chengyinhub.business.domain.dto.RevenueAccrualResult;
import com.chengyinhub.business.domain.dto.RevenueReversalRequest;
import com.chengyinhub.business.domain.dto.RevenueReversalResult;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import com.chengyinhub.business.domain.marketing.MarketingRevenueRule;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.MarketingAttributionMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationLineMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMarketingRevenueAllocationService;
import com.chengyinhub.business.service.IMarketingRevenueRuleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.support.RevenueFactSource;
import com.chengyinhub.business.service.support.RevenueMoney;
import com.chengyinhub.business.service.support.RevenueSnapshotHash;
import com.chengyinhub.business.service.support.RevenueSplitCalculator;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原单收入分配实现。
 *
 * <p><b>事实来源(全部服务端读取，请求里没有金额与身份参数)</b>：
 * <ul>
 *   <li>折后实付 + 币种 ← {@link RevenueFactSource#verifiedPayment}（SDK 查单落库的不可变事实）。
 *       不再使用 {@code payment_status} 或 total/original 回退猜金额。</li>
 *   <li>退款金额/币种/原单 ← {@link RevenueFactSource#verifiedRefund}（通道确认到账）。
 *       后台批准 ≠ 通道退款成功。</li>
 *   <li>来源 ← M1 {@code marketing_attribution}；作者 ← 规则里受审合同的收款人，不是内容 owner。</li>
 * </ul>
 *
 * <p><b>幂等优先于一切</b>：进入任何解析/封顶判断之前，先按原单读既有快照。
 * 同 snapshot → 重放返回；异 snapshot → 冲突。因此改规则、退规则、换来源之后的旧重试
 * 既不会失败也不会被重新定价。
 *
 * <p><b>不碰既有账本</b>：只写自己 4 张表；{@code settlement_detail} 的费率语义、
 * H8 的 creatorRate=0 闸、退款双人审批与逐明细反向冲销全部保持不变。
 */
@Service
public class MarketingRevenueAllocationServiceImpl implements IMarketingRevenueAllocationService {

    private static final Logger log = LoggerFactory.getLogger(MarketingRevenueAllocationServiceImpl.class);

    /** cms_registration.owner_type：1=主题 2=活动（仅内容关联，非收款人）。 */
    private static final int OWNER_TOPIC = 1;
    private static final int OWNER_ACTIVITY = 2;

    private final MarketingRevenueAllocationMapper allocationMapper;
    private final MarketingRevenueAllocationLineMapper lineMapper;
    private final MarketingAttributionMapper attributionMapper;
    private final CmsRegistrationMapper registrationMapper;
    private final RefundApplicationMapper refundMapper;
    private final ICmsTopicService topicService;
    private final ICmsActivityService activityService;
    private final IMarketingRevenueRuleService ruleService;
    private final IUmsMemberService memberService;
    private final RevenueFactSource factSource;

    /** 渠道退款不可变事实的按主键回读端口(由 M8 实现)。缺失即"未验证",按可重试处理。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader channelRefundFactReader;

    public MarketingRevenueAllocationServiceImpl(MarketingRevenueAllocationMapper allocationMapper,
                                                 MarketingRevenueAllocationLineMapper lineMapper,
                                                 MarketingAttributionMapper attributionMapper,
                                                 CmsRegistrationMapper registrationMapper,
                                                 RefundApplicationMapper refundMapper,
                                                 ICmsTopicService topicService,
                                                 ICmsActivityService activityService,
                                                 IMarketingRevenueRuleService ruleService,
                                                 IUmsMemberService memberService,
                                                 RevenueFactSource factSource) {
        this.allocationMapper = allocationMapper;
        this.lineMapper = lineMapper;
        this.attributionMapper = attributionMapper;
        this.registrationMapper = registrationMapper;
        this.refundMapper = refundMapper;
        this.topicService = topicService;
        this.activityService = activityService;
        this.ruleService = ruleService;
        this.memberService = memberService;
        this.factSource = factSource;
    }

    // ------------------------------------------------------------------ 应计

    @Override
    @Transactional
    public RevenueAccrualResult accrue(RevenueAccrualRequest request) {
        if (request == null || request.getRegistrationId() == null || request.getRegistrationId() <= 0) {
            throw MarketingRevenueException.badRequest("ORDER_REQUIRED", "缺少报名单");
        }
        Long registrationId = request.getRegistrationId();

        // ① 幂等优先：既有快照先于任何规则解析/金额计算，旧重试不受规则改动影响。
        MarketingRevenueAllocation existing = allocationMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, registrationId);
        if (existing != null) {
            return replayOrConflict(existing, request);
        }

        CmsRegistration registration = registrationMapper.selectCmsRegistrationById(registrationId);
        if (registration == null) {
            throw MarketingRevenueException.notFound("ORDER_NOT_FOUND", "订单不存在");
        }

        // ② 支付事实：没有 SDK 查单落库的成功事实就不应计，不拿状态位或订单金额顶上。
        RevenueFactSource.PaymentFact payment = factSource.verifiedPayment(registration)
                .orElseThrow(() -> MarketingRevenueException.conflict("PAYMENT_FACT_MISSING",
                        "缺少已验证支付成功事实，不予应计"));

        // ③ 扣费证据：未知就是未知，不允许默认 0 把虚假净额写进账。
        if (isBlank(request.getFeeBasisRef())) {
            throw MarketingRevenueException.conflict("FEE_BASIS_MISSING",
                    "税费/支付手续费/渠道佣金缺少核算证据引用，不予应计");
        }
        long taxFen = fenOrZero(request.getTaxAmount(), "TAX_INVALID", "税费");
        long paymentFeeFen = fenOrZero(request.getPaymentFeeAmount(), "PAYMENT_FEE_INVALID", "支付手续费");
        long channelFen = fenOrZero(request.getChannelCommissionAmount(), "CHANNEL_COMMISSION_INVALID", "渠道佣金");

        Integer ownerType = requireOwnerType(registration);
        Long ownerId = registration.getOwnerId();
        // owner 只用于定位内容与规则范围；收款人由规则里的受审合同声明，见 requireContractPayee。
        requireContentExists(ownerType, ownerId);

        MarketingAttribution attribution = attributionMapper.selectByOrder(
                MarketingRevenueAllocation.ORDER_REGISTRATION, registrationId);
        Long contentOwnerMemberId = resolveContentOwnerMemberId(ownerType, ownerId);
        int scenario = deriveScenario(attribution, contentOwnerMemberId);

        MarketingRevenueRule rule = ruleService.resolveActive(ownerType, ownerId, scenario, payment.currency());
        Long payeeMemberId = requireContractPayee(rule);

        RevenueSplitCalculator.Output split = RevenueSplitCalculator.split(new RevenueSplitCalculator.Input()
                .paidFen(payment.amountFen())
                .taxFen(taxFen)
                .paymentFeeFen(paymentFeeFen)
                .channelCommissionFen(channelFen)
                .authorBaseRate(rule.getAuthorBaseRate())
                .platformBaseRate(rule.getPlatformBaseRate())
                .referralRate(scenario == MarketingRevenueRule.SCENARIO_PLATFORM_SELLS_AUTHOR_CONTENT
                        ? BigDecimal.ZERO : rule.getReferralRate())
                .referralFundingSide(scenario == MarketingRevenueRule.SCENARIO_PLATFORM_SELLS_AUTHOR_CONTENT
                        ? MarketingRevenueRule.FUNDING_NONE : rule.getReferralFundingSide())
                .currency(payment.currency()));

        Date now = DateUtils.getNowDate();
        MarketingRevenueAllocation allocation = buildAllocation(registration, attribution, rule, scenario,
                payeeMemberId, ownerType, ownerId, payment, taxFen, paymentFeeFen, channelFen,
                request.getFeeBasisRef(), split, now);
        allocation.setSnapshotHash(RevenueSnapshotHash.of(allocation));

        try {
            if (allocationMapper.insertAllocation(allocation) != 1) {
                throw new IllegalStateException("分配快照写入失败");
            }
        } catch (DuplicateKeyException raced) {
            MarketingRevenueAllocation winner = allocationMapper.selectByOrder(
                    MarketingRevenueAllocation.ORDER_REGISTRATION, registrationId);
            if (winner == null) {
                throw raced;
            }
            return replayOrConflict(winner, request);
        }

        List<MarketingRevenueAllocationLine> lines =
                buildAccrualLines(allocation, split, attribution, payeeMemberId, now);
        if (lines.isEmpty() || lineMapper.batchInsert(lines) != lines.size()) {
            throw new IllegalStateException("分配分录写入失败");
        }
        return toAccrualResult(allocation, lines, false);
    }

    // ------------------------------------------------------------------ 冲销

    @Override
    @Transactional
    public RevenueReversalResult reverse(RevenueReversalRequest request) {
        if (request == null || request.getRefundId() == null || request.getRefundId() <= 0) {
            throw MarketingRevenueException.badRequest("REFUND_ID_REQUIRED", "缺少退款单标识");
        }

        // ① 退款单是原单与金额的唯一真源；请求里的 registrationId 只能用于交叉核对。
        RefundApplication refund = refundMapper.selectRefundApplicationById(request.getRefundId());
        if (refund == null) {
            throw MarketingRevenueException.notFound("REFUND_NOT_FOUND", "退款单不存在");
        }
        if (!"registration".equals(refund.getSourceType()) || refund.getSourceId() == null) {
            throw MarketingRevenueException.badRequest("REFUND_NOT_REGISTRATION",
                    "退款单不指向报名单，不属于本域分配范围");
        }
        if (request.getRegistrationId() != null
                && !request.getRegistrationId().equals(refund.getSourceId())) {
            throw MarketingRevenueException.conflict("REFUND_ORDER_CONFLICT",
                    "退款单关联的原单与请求不一致");
        }
        Long registrationId = refund.getSourceId();
        String eventRef = "refund:" + refund.getId();

        MarketingRevenueAllocation allocation = allocationMapper.selectByOrderForUpdate(
                MarketingRevenueAllocation.ORDER_REGISTRATION, registrationId);
        if (allocation == null) {
            throw MarketingRevenueException.notFound("ALLOCATION_NOT_FOUND", "该订单没有分配快照，无法冲销");
        }

        // ② 事件幂等优先于累计封顶：同一退款事件重放必须返回同回执，
        //    而不是在全额冲销后再撞一次 "超过累计上限"。
        List<MarketingRevenueAllocationLine> thisEvent = lineMapper.selectByEvent(allocation.getId(), eventRef);
        if (!thisEvent.isEmpty()) {
            return replayReversal(allocation, refund, eventRef, thisEvent);
        }

        CmsRegistration registration = registrationMapper.selectCmsRegistrationById(registrationId);
        if (registration == null) {
            throw MarketingRevenueException.notFound("ORDER_NOT_FOUND", "退款单关联的订单不存在");
        }

        // ③ 通道事实：后台批准不等于真的退了钱。
        RevenueFactSource.RefundFact refundFact = factSource.verifiedRefund(refund, registration)
                .orElseThrow(() -> MarketingRevenueException.conflict("REFUND_FACT_MISSING",
                        "缺少已验证通道退款成功事实，不予冲销"));

        List<MarketingRevenueAllocationLine> all = linesOf(allocation.getId());
        List<MarketingRevenueAllocationLine> accrueLines = new ArrayList<>();
        Map<Long, Long> reversedByLine = new HashMap<>();
        long alreadyReversedFen = 0;
        for (MarketingRevenueAllocationLine line : all) {
            if (line.getEntryType() != null && line.getEntryType() == MarketingRevenueAllocationLine.ENTRY_ACCRUE) {
                accrueLines.add(line);
            } else {
                long reversal = line.getAmountFen() == null ? 0 : -line.getAmountFen();
                alreadyReversedFen += reversal;
                if (line.getReversalOfLineId() != null) {
                    reversedByLine.merge(line.getReversalOfLineId(), reversal, Long::sum);
                }
            }
        }
        if (accrueLines.isEmpty()) {
            throw MarketingRevenueException.conflict("ALLOCATION_EMPTY", "分配快照没有应计分录");
        }
        accrueLines.sort((a, b) -> Long.compare(
                a.getId() == null ? 0 : a.getId(), b.getId() == null ? 0 : b.getId()));

        // ④ 累计口径：本事件之前的累计退款 + 本次 = 新的累计退款额（不得超过原单实付）。
        long cumulativeBeforeFen = sumEventAmounts(all, null);
        long cumulativeAfterFen = Math.addExact(cumulativeBeforeFen, refundFact.amountFen());
        if (cumulativeAfterFen > allocation.getPaidFen()) {
            throw MarketingRevenueException.conflict("REFUND_EXCEEDS_PAID",
                    "累计退款额超过原单已验证实付，拒绝冲销");
        }

        // ⑤ 目标冲销额按"累计退款额"折算到净额口径，再整单分摊到各原分录；
        //    事件增量 = 目标 − 已冲销。
        //
        //    为什么要折算：退款上限是**实付**(paid_fen)，而各分录之和是**净额**(net_fen)，
        //    两者差着税费/手续费/渠道佣金。直接把累计退款额当分摊总量会在
        //    "退满实付但仍小于净额以外"时越界，也会在净额 < 实付时报超限。
        //    折算用整数交叉相乘后整除，全退（cumulative == paid）时目标恰等于净额总和。
        long[] weights = new long[accrueLines.size()];
        for (int i = 0; i < accrueLines.size(); i++) {
            weights[i] = accrueLines.get(i).getAmountFen() == null ? 0 : accrueLines.get(i).getAmountFen();
        }
        // 净额上限:与下方按事件冲销的路径同一个来源(分配行快照),不另算一套。
        long netFen = allocation.getNetFen() == null ? 0L : allocation.getNetFen();
        long cumulativeNetTarget = cumulativeNetTargetFen(allocation.getPaidFen(),
                allocation.getNetFen(), cumulativeAfterFen);
        long[] cumulativeTarget = RevenueMoney.allocate(cumulativeNetTarget, weights);

        Date now = DateUtils.getNowDate();
        List<MarketingRevenueAllocationLine> reversals = new ArrayList<>(accrueLines.size());
        long deltaFen = 0;
        for (int i = 0; i < accrueLines.size(); i++) {
            MarketingRevenueAllocationLine origin = accrueLines.get(i);
            long alreadyOnThisLine = reversedByLine.getOrDefault(origin.getId(), 0L);
            long delta = cumulativeTarget[i] - alreadyOnThisLine;
            if (delta <= 0) {
                continue; // 本事件没有落到这一行（例如已冲满）
            }
            if (alreadyOnThisLine + delta > Math.abs(weights[i])) {
                throw new MarketingRevenueException("REVERSAL_EXCEEDS_LINE", 409,
                        "单行冲销将超过其原应计金额");
            }
            reversals.add(reversalLine(allocation, origin, refund, refundFact, eventRef, -delta, now));
            deltaFen += delta;
        }
        if (reversals.isEmpty()) {
            throw MarketingRevenueException.conflict("REVERSAL_EMPTY", "本次退款不产生可冲销增量");
        }
        if (alreadyReversedFen + deltaFen > netFen) {
            throw new MarketingRevenueException("REVERSAL_EXCEEDS_ACCRUED", 409,
                    "累计冲销将超过原应计净额，拒绝反向分录");
        }

        if (lineMapper.batchInsert(reversals) != reversals.size()) {
            throw new IllegalStateException("冲销分录写入失败");
        }
        long reversedTotalFen = alreadyReversedFen + deltaFen;
        int status = reversedTotalFen >= netFen
                ? MarketingRevenueAllocation.STATUS_REVERSED
                : MarketingRevenueAllocation.STATUS_PARTIALLY_REVERSED;
        if (allocationMapper.updateAfterReversal(allocation.getId(), reversedTotalFen, status, now) != 1) {
            throw new IllegalStateException("分配冲销状态回写失败");
        }
        log.info("[M5] 收入分配冲销 allocationId={} refundId={} delta={}fen total={}fen status={}",
                allocation.getId(), refund.getId(), deltaFen, reversedTotalFen, status);
        MarketingRevenueAllocation updated = allocationMapper.selectById(allocation.getId());
        return toReversalResult(updated == null ? allocation : updated, refund, eventRef, reversals, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RevenueReversalResult reverseChannelRefund(long channelRefundFactId, Long registrationId) {
        if (registrationId == null || registrationId <= 0) {
            throw MarketingRevenueException.badRequest("ORDER_REQUIRED", "渠道退款未关联报名单");
        }
        com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader.Fact fact =
                requireChannelRefundFact(channelRefundFactId);
        if (fact.registrationId != null && !fact.registrationId.equals(registrationId)) {
            throw MarketingRevenueException.conflict("CHANNEL_FACT_ORDER_CONFLICT",
                    "渠道退款事实关联的报名单与任务不一致");
        }
        if (!fact.registrationIds.isEmpty() && !fact.registrationIds.contains(registrationId)) {
            throw MarketingRevenueException.conflict("CHANNEL_FACT_ORDER_CONFLICT",
                    "报名单不在该渠道退款事实的覆盖范围内");
        }
        MarketingRevenueAllocation allocation = allocationMapper.selectByOrderForUpdate(
                MarketingRevenueAllocation.ORDER_REGISTRATION, registrationId);
        if (allocation == null) {
            // 退款先到、应计因费用未核算还没产生：保留可重试，费用补齐后同一任务会再算一次。
            throw MarketingRevenueException.notFound("ALLOCATION_NOT_FOUND",
                    "该订单尚无分配快照，冲销任务保留待重试");
        }
        return reverseChannelEvent(fact, allocation, channelAmountFen(fact), false);
    }

    /**
     * 按退款事实的**原注册单范围**逐条冲销（渠道队列消费入口）。
     *
     * <p>一条渠道退款事实可能覆盖多作品包的多行注册单；唯一键 {@code (task_kind, ref_id)}
     * 只允许一条任务，所以这里按范围展开。
     *
     * <p>整笔全退 ⇒ 每行按各自 {@code paid_fen} 冲销（各自冲满自己的净额），
     * 不把订单总额在每行重复计一次；任一行尚无分配快照则整体抛出
     * {@code ALLOCATION_NOT_FOUND} 让事务回滚并保留待重试（不会只冲一半）。
     *
     * <p>返回的 {@code reversedAmount} 是范围内各行**累计**已冲销金额之和（仅用于回执展示），
     * 幂等/无事可做的行按 0 计入，不虚增。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RevenueReversalResult reverseChannelRefundFact(long channelRefundFactId) {
        com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader.Fact fact =
                requireChannelRefundFact(channelRefundFactId);
        if (!fact.fullRefund) {
            throw MarketingRevenueException.conflict("PARTIAL_CHANNEL_REFUND_UNSUPPORTED",
                    "渠道部分退款未定义撤权与冲销口径，保留待人工处理");
        }
        if (fact.registrationIds.isEmpty()) {
            throw MarketingRevenueException.conflict("CHANNEL_FACT_SCOPE_EMPTY",
                    "渠道退款事实没有可冲销的注册行范围，保留待人工核对");
        }
        RevenueReversalResult summary = null;
        BigDecimal summed = BigDecimal.ZERO;
        boolean allReplayed = true;
        for (Long registrationId : fact.registrationIds) {
            if (registrationId == null || registrationId <= 0) {
                throw MarketingRevenueException.conflict("CHANNEL_FACT_SCOPE_INVALID",
                        "渠道退款事实的注册行范围不合法");
            }
            MarketingRevenueAllocation allocation = allocationMapper.selectByOrderForUpdate(
                    MarketingRevenueAllocation.ORDER_REGISTRATION, registrationId);
            if (allocation == null) {
                throw MarketingRevenueException.notFound("ALLOCATION_NOT_FOUND",
                        "注册单尚无分配快照，冲销任务保留待重试 registrationId=" + registrationId);
            }
            long eventAmountFen = allocation.getPaidFen() == null ? 0L : allocation.getPaidFen();
            RevenueReversalResult one = reverseChannelEvent(fact, allocation, eventAmountFen, true);
            if (one.getReversedAmount() != null) {
                summed = summed.add(one.getReversedAmount());
            }
            allReplayed = allReplayed && one.isReplayed();
            summary = one;
        }
        if (summary == null) {
            throw MarketingRevenueException.conflict("CHANNEL_FACT_SCOPE_EMPTY",
                    "渠道退款事实没有可冲销的注册行范围，保留待人工核对");
        }
        summary.setReversedAmount(summed);
        summary.setReplayed(allReplayed);
        return summary;
    }

    private com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader.Fact requireChannelRefundFact(
            long channelRefundFactId) {
        if (channelRefundFactId <= 0) {
            throw MarketingRevenueException.badRequest("CHANNEL_FACT_REQUIRED", "缺少渠道退款事实标识");
        }
        if (channelRefundFactReader == null) {
            // provider 未接入：不按调用方声称的金额入账，留给重试。
            throw MarketingRevenueException.conflict("CHANNEL_FACT_UNVERIFIED",
                    "渠道退款事实回读端口未启用，暂不冲销");
        }
        com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader.Fact fact =
                channelRefundFactReader.requireVerifiedById(channelRefundFactId);
        if (fact == null) {
            throw MarketingRevenueException.conflict("CHANNEL_FACT_UNVERIFIED",
                    "查不到已确认的渠道退款事实，暂不冲销");
        }
        if (!fact.confirmed) {
            throw MarketingRevenueException.conflict("CHANNEL_FACT_UNCONFIRMED",
                    "渠道退款事实尚未确认完成，暂不冲销");
        }
        if (fact.currency == null || !"CNY".equals(fact.currency)) {
            throw MarketingRevenueException.conflict("CHANNEL_FACT_CURRENCY_INVALID",
                    "渠道退款币种与收入分配口径不一致");
        }
        if (fact.amount == null || fact.amount.signum() <= 0) {
            throw MarketingRevenueException.conflict("CHANNEL_FACT_AMOUNT_INVALID",
                    "渠道退款金额非正，无法冲销");
        }
        return fact;
    }

    private long channelAmountFen(com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader.Fact fact) {
        try {
            return fact.amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException notIntegralFen) {
            throw MarketingRevenueException.conflict("CHANNEL_FACT_AMOUNT_INVALID",
                    "渠道退款金额无法换算为整数分");
        }
    }

    /**
     * 单条分配上的渠道冲销。金额一律来自服务端回读事实（或整笔全退时该行的实付），
     * 事件键 {@code channel-refund:<factId>} 保证同一退款重放不双记。
     *
     * @param clampToRemaining true = 全退场景，允许把"该行已冲部分之外剩余可退额"作为事件额，
     *                         避免历史分笔退款后整笔全退被误判为超额
     */
    private RevenueReversalResult reverseChannelEvent(
                                                      com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader.Fact fact,
                                                      MarketingRevenueAllocation allocation,
                                                      long eventAmountFen,
                                                      boolean clampToRemaining) {
        String eventRef = "channel-refund:" + fact.factId;
        List<MarketingRevenueAllocationLine> all = linesOf(allocation.getId());
        List<MarketingRevenueAllocationLine> thisEvent = lineMapper.selectByEvent(allocation.getId(), eventRef);
        if (!thisEvent.isEmpty()) {
            return toReversalResultOfChannel(allocation, eventRef, thisEvent, true);
        }
        List<MarketingRevenueAllocationLine> accrueLines = new ArrayList<>();
        Map<Long, Long> reversedByLine = new HashMap<>();
        long alreadyReversedFen = 0;
        for (MarketingRevenueAllocationLine line : all) {
            if (line.getEntryType() != null && line.getEntryType() == MarketingRevenueAllocationLine.ENTRY_ACCRUE) {
                accrueLines.add(line);
            } else {
                long reversal = line.getAmountFen() == null ? 0 : -line.getAmountFen();
                alreadyReversedFen += reversal;
                if (line.getReversalOfLineId() != null) {
                    reversedByLine.merge(line.getReversalOfLineId(), reversal, Long::sum);
                }
            }
        }
        if (accrueLines.isEmpty()) {
            throw MarketingRevenueException.conflict("ALLOCATION_EMPTY", "分配快照没有应计分录");
        }
        accrueLines.sort((a, b) -> Long.compare(
                a.getId() == null ? 0 : a.getId(), b.getId() == null ? 0 : b.getId()));

        long cumulativeBeforeFen = sumEventAmounts(all, null);
        long requestedFen = eventAmountFen;
        long paidFen = allocation.getPaidFen() == null ? 0L : allocation.getPaidFen();
        long netFen = allocation.getNetFen() == null ? 0L : allocation.getNetFen();
        if (clampToRemaining) {
            long remainingFen = Math.max(0L, paidFen - cumulativeBeforeFen);
            requestedFen = Math.min(eventAmountFen, remainingFen);
        }
        if (clampToRemaining && requestedFen == 0L) {
            // 该行已无剩余可退额：若净额已冲满，说明本事件无事可做（幂等完成，不重复记）；
            // 否则是实付/净额口径异常，必须显式失败待人工，不能静默认作已处理。
            if (alreadyReversedFen >= netFen) {
                RevenueReversalResult noop =
                        toReversalResultOfChannel(allocation, eventRef, new ArrayList<>(), true);
                noop.setReversedAmount(BigDecimal.ZERO);
                return noop;
            }
            throw MarketingRevenueException.conflict("CHANNEL_FACT_AMOUNT_INVALID",
                    "该行实付为 0 但净额未冲销，退款事实金额与分配口径不一致");
        }
        long cumulativeAfterFen = Math.addExact(cumulativeBeforeFen, requestedFen);
        if (cumulativeAfterFen > paidFen) {
            throw MarketingRevenueException.conflict("REFUND_EXCEEDS_PAID",
                    "累计退款额超过原单已验证实付，拒绝冲销");
        }
        long[] weights = new long[accrueLines.size()];
        for (int i = 0; i < accrueLines.size(); i++) {
            weights[i] = accrueLines.get(i).getAmountFen() == null ? 0 : accrueLines.get(i).getAmountFen();
        }
        long cumulativeNetTarget = cumulativeNetTargetFen(paidFen, netFen, cumulativeAfterFen);
        long[] cumulativeTarget = RevenueMoney.allocate(cumulativeNetTarget, weights);

        Date now = DateUtils.getNowDate();
        List<MarketingRevenueAllocationLine> reversals = new ArrayList<>(accrueLines.size());
        long deltaFen = 0;
        for (int i = 0; i < accrueLines.size(); i++) {
            MarketingRevenueAllocationLine origin = accrueLines.get(i);
            long alreadyOnThisLine = reversedByLine.getOrDefault(origin.getId(), 0L);
            long delta = cumulativeTarget[i] - alreadyOnThisLine;
            if (delta <= 0) {
                continue;
            }
            if (alreadyOnThisLine + delta > Math.abs(weights[i])) {
                throw new MarketingRevenueException("REVERSAL_EXCEEDS_LINE", 409,
                        "单行冲销将超过其原应计金额");
            }
            reversals.add(reversalLineForChannel(allocation, origin, fact.factId,
                    requestedFen, fact.currency, eventRef, -delta, now));
            deltaFen += delta;
        }
        if (reversals.isEmpty()) {
            throw MarketingRevenueException.conflict("REVERSAL_EMPTY", "本次渠道退款不产生可冲销增量");
        }
        if (alreadyReversedFen + deltaFen > netFen) {
            throw new MarketingRevenueException("REVERSAL_EXCEEDS_ACCRUED", 409,
                    "累计冲销将超过原应计净额，拒绝反向分录");
        }
        if (lineMapper.batchInsert(reversals) != reversals.size()) {
            throw new IllegalStateException("渠道冲销分录写入失败");
        }
        long reversedTotalFen = alreadyReversedFen + deltaFen;
        int status = reversedTotalFen >= netFen
                ? MarketingRevenueAllocation.STATUS_REVERSED
                : MarketingRevenueAllocation.STATUS_PARTIALLY_REVERSED;
        if (allocationMapper.updateAfterReversal(allocation.getId(), reversedTotalFen, status, now) != 1) {
            throw new IllegalStateException("渠道冲销状态回写失败");
        }
        MarketingRevenueAllocation updated = allocationMapper.selectById(allocation.getId());
        return toReversalResultOfChannel(updated == null ? allocation : updated, eventRef, reversals, false);
    }

    /** 渠道冲销回执：没有 RefundApplication，refundId 留空，由 eventRef 标识。 */
    private RevenueReversalResult toReversalResultOfChannel(MarketingRevenueAllocation allocation,
                                                            String eventRef,
                                                            List<MarketingRevenueAllocationLine> thisEvent,
                                                            boolean replayed) {
        RevenueReversalResult result = new RevenueReversalResult();
        result.setAllocationId(allocation.getId());
        result.setOrderType(allocation.getOrderType());
        result.setOrderId(allocation.getOrderId());
        result.setEventRef(eventRef);
        result.setReversedAmount(allocation.getReversedAmount());
        result.setStatus(allocation.getStatus());
        result.setReplayed(replayed);
        long eventAmountFen = 0;
        List<RevenueReversalResult.Line> views = new ArrayList<>(thisEvent.size());
        for (MarketingRevenueAllocationLine line : thisEvent) {
            RevenueReversalResult.Line view = new RevenueReversalResult.Line();
            view.setRole(line.getLineRole());
            view.setRecipientMemberId(line.getRecipientMemberId());
            view.setAmountFen(line.getAmountFen());
            view.setAmount(line.getAmount());
            views.add(view);
            if (line.getEventAmountFen() != null) {
                eventAmountFen = Math.max(eventAmountFen, line.getEventAmountFen());
            }
        }
        result.setLines(views);
        result.setEventRefundAmount(RevenueMoney.toAmount(eventAmountFen));
        return result;
    }

    // ------------------------------------------------------------------ 查询/状态

    @Override
    public MarketingRevenueAllocation getByOrder(Integer orderType, Long orderId) {
        if (orderType == null || orderId == null || orderId <= 0) {
            throw MarketingRevenueException.badRequest("ORDER_REQUIRED", "缺少订单标识");
        }
        return allocationMapper.selectByOrder(orderType, orderId);
    }

    @Override
    public List<MarketingRevenueAllocationLine> linesOf(Long allocationId) {
        if (allocationId == null || allocationId <= 0) {
            return new ArrayList<>();
        }
        List<MarketingRevenueAllocationLine> lines = lineMapper.selectByAllocationId(allocationId);
        return lines == null ? new ArrayList<>() : lines;
    }

    @Override
    public List<MarketingRevenueAllocation> listByAuthor(Long authorMemberId) {
        if (authorMemberId == null || authorMemberId <= 0) {
            throw MarketingRevenueException.badRequest("AUTHOR_REQUIRED", "缺少作者标识");
        }
        List<MarketingRevenueAllocation> rows = allocationMapper.selectByAuthor(authorMemberId);
        return rows == null ? new ArrayList<>() : rows;
    }

    /**
     * 只把"已过冻结期"的应计推进为可结算。时间由 DB 时钟决定，无调用方传参；
     * 不涉及任何打款事实，因此不会把财务状态凭 id/任意时间升级成已打款。
     */
    @Override
    @Transactional
    public int advanceSettleable() {
        return allocationMapper.markSettleableDue();
    }

    // ------------------------------------------------------------------ internals

    private MarketingRevenueAllocation buildAllocation(CmsRegistration registration,
                                                       MarketingAttribution attribution,
                                                       MarketingRevenueRule rule,
                                                       int scenario,
                                                       Long payeeMemberId,
                                                       int ownerType,
                                                       Long ownerId,
                                                       RevenueFactSource.PaymentFact payment,
                                                       long taxFen,
                                                       long paymentFeeFen,
                                                       long channelFen,
                                                       String feeBasisRef,
                                                       RevenueSplitCalculator.Output split,
                                                       Date now) {
        MarketingRevenueAllocation allocation = new MarketingRevenueAllocation();
        allocation.setOrderType(MarketingRevenueAllocation.ORDER_REGISTRATION);
        allocation.setOrderId(registration.getId());
        allocation.setAllocationKey("registration:" + registration.getId());
        allocation.setRuleId(rule.getId());
        allocation.setRuleKey(rule.getRuleKey());
        allocation.setRuleVersion(rule.getVersion());
        allocation.setScenario(scenario);
        if (attribution != null) {
            allocation.setAttributionId(attribution.getId());
            allocation.setSharerMemberId(attribution.getSharerMemberId());
            allocation.setCampaignKey(attribution.getCampaignKey());
        }
        allocation.setAuthorMemberId(payeeMemberId);
        allocation.setContractRef(rule.getContractRef());
        allocation.setOwnerType(ownerType);
        allocation.setOwnerId(ownerId);
        allocation.setCurrency(payment.currency());
        allocation.setPaidFen(payment.amountFen());
        allocation.setPaymentFactHash(payment.factHash());
        // 原价/折扣/积分只作事实快照；净额起点是已验证实付，不能再扣一遍折扣。
        allocation.setOriginalAmountFen(snapshotFen(registration.getOriginalAmount()));
        allocation.setDiscountFen(snapshotFen(registration.getDiscountAmount()));
        allocation.setPointDeductFen(snapshotFen(registration.getPointDeductAmount()));
        allocation.setTaxFen(taxFen);
        allocation.setPaymentFeeFen(paymentFeeFen);
        allocation.setChannelCommissionFen(channelFen);
        allocation.setFeeBasisRef(feeBasisRef.trim());
        allocation.setNetFen(split.getNetFen());
        allocation.setReferralFundingSide(split.getReferralFundingSide());
        allocation.setStatus(MarketingRevenueAllocation.STATUS_ACCRUED);
        int holdDays = rule.getHoldDays() == null ? 0 : rule.getHoldDays();
        allocation.setSettleableAt(new Date(now.getTime() + holdDays * 24L * 60L * 60L * 1000L));
        allocation.setReversedFen(0L);
        allocation.setCreateTime(now);
        allocation.setUpdateTime(now);
        return allocation;
    }

    private List<MarketingRevenueAllocationLine> buildAccrualLines(MarketingRevenueAllocation allocation,
                                                                  RevenueSplitCalculator.Output split,
                                                                  MarketingAttribution attribution,
                                                                  Long payeeMemberId,
                                                                  Date now) {
        List<MarketingRevenueAllocationLine> lines = new ArrayList<>(3);
        lines.add(accrualLine(allocation, MarketingRevenueAllocationLine.ROLE_AUTHOR, payeeMemberId,
                MarketingRevenueRule.FUNDING_NONE, split.getAuthorFen(), split.getAuthorBaseRate(),
                "accrue:author", now));
        if (split.getReferrerFen() > 0) {
            Long sharer = attribution == null ? null : attribution.getSharerMemberId();
            if (sharer == null) {
                throw MarketingRevenueException.conflict("REFERRER_REQUIRED",
                        "推荐场景缺少分享者事实，拒绝应计");
            }
            lines.add(accrualLine(allocation, MarketingRevenueAllocationLine.ROLE_REFERRER, sharer,
                    split.getReferralFundingSide(), split.getReferrerFen(), split.getReferralRate(),
                    "accrue:referrer", now));
        }
        // 平台份额不挂任何会员账：recipientMemberId 必须为 null。
        lines.add(accrualLine(allocation, MarketingRevenueAllocationLine.ROLE_PLATFORM, null,
                MarketingRevenueRule.FUNDING_NONE, split.getPlatformFen(), BigDecimal.ZERO,
                "accrue:platform", now));
        return lines;
    }

    private MarketingRevenueAllocationLine accrualLine(MarketingRevenueAllocation allocation,
                                                       String role,
                                                       Long recipientMemberId,
                                                       int fundingSide,
                                                       long amountFen,
                                                       BigDecimal rate,
                                                       String entryKey,
                                                       Date now) {
        MarketingRevenueAllocationLine line = new MarketingRevenueAllocationLine();
        line.setAllocationId(allocation.getId());
        line.setEntryType(MarketingRevenueAllocationLine.ENTRY_ACCRUE);
        line.setLineRole(role);
        line.setRecipientMemberId(recipientMemberId);
        line.setFundingSide(fundingSide);
        line.setBasisFen(allocation.getNetFen());
        line.setRate(rate == null ? BigDecimal.ZERO : rate);
        line.setAmountFen(amountFen);
        line.setCurrency(allocation.getCurrency());
        line.setEntryKey(entryKey);
        line.setSourceFact(truncate("payment-fact:" + allocation.getPaymentFactHash(), 96));
        line.setCreateTime(now);
        return line;
    }

    /** 渠道退款分录：不复用 {@link RefundApplication}，也不伪造 refund id。 */
    private MarketingRevenueAllocationLine reversalLineForChannel(MarketingRevenueAllocation allocation,
                                                                  MarketingRevenueAllocationLine origin,
                                                                  long channelFactId,
                                                                  long eventAmountFen,
                                                                  String currency,
                                                                  String eventRef,
                                                                  long negativeAmountFen,
                                                                  Date now) {
        MarketingRevenueAllocationLine line = new MarketingRevenueAllocationLine();
        line.setAllocationId(allocation.getId());
        line.setEntryType(MarketingRevenueAllocationLine.ENTRY_REVERSE);
        line.setLineRole(origin.getLineRole());
        line.setRecipientMemberId(origin.getRecipientMemberId());
        line.setFundingSide(origin.getFundingSide());
        line.setBasisFen(origin.getBasisFen());
        line.setRate(origin.getRate());
        line.setAmountFen(negativeAmountFen);
        line.setCurrency(allocation.getCurrency());
        line.setEntryKey("reverse:" + eventRef + ":" + origin.getLineRole());
        line.setEventRef(eventRef);
        line.setEventAmountFen(eventAmountFen);
        line.setEventCurrency(currency);
        line.setEventOrderType(allocation.getOrderType());
        line.setEventOrderId(allocation.getOrderId());
        line.setReversalOfLineId(origin.getId());
        line.setSourceFact(truncate("channel-refund:" + channelFactId, 96));
        line.setCreateTime(now);
        return line;
    }

    private MarketingRevenueAllocationLine reversalLine(MarketingRevenueAllocation allocation,
                                                        MarketingRevenueAllocationLine origin,
                                                        RefundApplication refund,
                                                        RevenueFactSource.RefundFact refundFact,
                                                        String eventRef,
                                                        long negativeAmountFen,
                                                        Date now) {
        MarketingRevenueAllocationLine line = new MarketingRevenueAllocationLine();
        line.setAllocationId(allocation.getId());
        line.setEntryType(MarketingRevenueAllocationLine.ENTRY_REVERSE);
        line.setLineRole(origin.getLineRole());
        line.setRecipientMemberId(origin.getRecipientMemberId());
        line.setFundingSide(origin.getFundingSide());
        line.setBasisFen(origin.getBasisFen());
        line.setRate(origin.getRate());
        line.setAmountFen(negativeAmountFen);
        line.setCurrency(allocation.getCurrency());
        line.setEntryKey("reverse:" + eventRef + ":" + origin.getLineRole());
        line.setEventRef(eventRef);
        line.setEventAmountFen(refundFact.amountFen());
        line.setEventCurrency(refundFact.currency());
        line.setEventOrderType(allocation.getOrderType());
        line.setEventOrderId(allocation.getOrderId());
        line.setReversalOfLineId(origin.getId());
        line.setSourceFact(truncate("refund:" + refund.getId(), 96));
        line.setCreateTime(now);
        return line;
    }

    /**
     * 重放或冲突。这里刻意<b>不</b>解析当前规则：比对的是既有快照摘要，
     * 因此规则被改/退役/来源变化都不会让旧重试失败或被重新定价。
     *
     * <p>区分两种调用形态：
     * <ul>
     *   <li><b>带扣费 payload</b>（有 feeBasisRef 或任一扣费金额）：重算摘要比对，
     *       异 payload 一律 409，防止有人用改后的扣费重写快照。</li>
     *   <li><b>无 payload</b>（队列消费者只带 registrationId）：没有可比对的新参数，
     *       直接返回原快照即"幂等重放"。否则用 0 扣费重算摘要必然与原快照不符，
     *       会让任务永远失败 —— 那是实现 bug，不是业务冲突。</li>
     * </ul>
     */
    private RevenueAccrualResult replayOrConflict(MarketingRevenueAllocation existing,
                                                  RevenueAccrualRequest request) {
        if (existing.getPaymentFactHash() == null || existing.getPaidFen() == null) {
            // 旧快照缺少事实身份，无法安全比较 → 视为冲突而不是静默重放。
            throw MarketingRevenueException.conflict("ACCRUAL_LEGACY_SNAPSHOT",
                    "既有分配快照缺少支付事实身份，需人工核对");
        }
        if (!carriesFeePayload(request)) {
            return toAccrualResult(existing, linesOf(existing.getId()), true);
        }
        String expected = RevenueSnapshotHash.of(expectedShape(existing, request));
        if (!existing.getSnapshotHash().equals(expected)) {
            throw MarketingRevenueException.conflict("ACCRUAL_CONFLICT",
                    "该订单已按不同参数应计，不允许改写原分配快照");
        }
        return toAccrualResult(existing, linesOf(existing.getId()), true);
    }

    /** 请求是否携带可比较的扣费参数（证据引用或任一分项）。 */
    private static boolean carriesFeePayload(RevenueAccrualRequest request) {
        return !isBlank(request.getFeeBasisRef())
                || request.getTaxAmount() != null
                || request.getPaymentFeeAmount() != null
                || request.getChannelCommissionAmount() != null;
    }

    /**
     * 把本次请求的"可比较字段"套到既有快照的冻结身份上，算出它应当对应的摘要。
     * 只有请求侧的扣费分项与证据变了，摘要才会变 —— 这正是我们要拒绝的情形。
     */
    private MarketingRevenueAllocation expectedShape(MarketingRevenueAllocation existing,
                                                     RevenueAccrualRequest request) {
        if (existing.getPaymentFactHash() == null || existing.getPaidFen() == null) {
            // 旧快照缺少事实身份，无法安全比较 → 视为冲突而不是静默重放。
            throw MarketingRevenueException.conflict("ACCRUAL_LEGACY_SNAPSHOT",
                    "既有分配快照缺少支付事实身份，需人工核对");
        }
        MarketingRevenueAllocation probe = new MarketingRevenueAllocation();
        probe.setOrderType(existing.getOrderType());
        probe.setOrderId(existing.getOrderId());
        probe.setAllocationKey(existing.getAllocationKey());
        probe.setRuleKey(existing.getRuleKey());
        probe.setRuleVersion(existing.getRuleVersion());
        probe.setScenario(existing.getScenario());
        probe.setAttributionId(existing.getAttributionId());
        probe.setSharerMemberId(existing.getSharerMemberId());
        probe.setCampaignKey(existing.getCampaignKey());
        probe.setAuthorMemberId(existing.getAuthorMemberId());
        probe.setContractRef(existing.getContractRef());
        probe.setOwnerType(existing.getOwnerType());
        probe.setOwnerId(existing.getOwnerId());
        probe.setCurrency(existing.getCurrency());
        probe.setPaymentFactHash(existing.getPaymentFactHash());
        probe.setPaidFen(existing.getPaidFen());
        probe.setOriginalAmountFen(existing.getOriginalAmountFen());
        probe.setDiscountFen(existing.getDiscountFen());
        probe.setPointDeductFen(existing.getPointDeductFen());
        probe.setTaxFen(fenOrZero(request.getTaxAmount(), "TAX_INVALID", "税费"));
        probe.setPaymentFeeFen(fenOrZero(request.getPaymentFeeAmount(), "PAYMENT_FEE_INVALID", "支付手续费"));
        probe.setChannelCommissionFen(fenOrZero(request.getChannelCommissionAmount(),
                "CHANNEL_COMMISSION_INVALID", "渠道佣金"));
        probe.setFeeBasisRef(isBlank(request.getFeeBasisRef()) ? null : request.getFeeBasisRef().trim());
        probe.setNetFen(existing.getPaidFen()
                - probe.getTaxFen() - probe.getPaymentFeeFen() - probe.getChannelCommissionFen());
        probe.setReferralFundingSide(existing.getReferralFundingSide());
        return probe;
    }

    /**
     * 累计退款额 → 累计应冲销净额。整数交叉相乘后整除（向下取整），
     * 全退时恰好等于净额；部分退款时不会超过净额，也不会因浮点产生尾差。
     */
    static long cumulativeNetTargetFen(long paidFen, long netFen, long cumulativeRefundFen) {
        if (paidFen <= 0 || netFen <= 0 || cumulativeRefundFen <= 0) {
            return 0L;
        }
        if (cumulativeRefundFen >= paidFen) {
            return netFen; // 退满实付 → 冲满净额
        }
        return java.math.BigInteger.valueOf(netFen)
                .multiply(java.math.BigInteger.valueOf(cumulativeRefundFen))
                .divide(java.math.BigInteger.valueOf(paidFen))
                .longValueExact();
    }

    private RevenueReversalResult replayReversal(MarketingRevenueAllocation allocation,
                                                 RefundApplication refund,
                                                 String eventRef,
                                                 List<MarketingRevenueAllocationLine> thisEvent) {
        // 同 refundId 换金额/币种/原单 → 明确拒绝，而不是当成新事件再冲一次。
        long eventAmountFen = -1L;
        String eventCurrency = null;
        Integer eventOrderType = null;
        Long eventOrderId = null;
        for (MarketingRevenueAllocationLine line : thisEvent) {
            long amount = line.getEventAmountFen() == null ? -1L : line.getEventAmountFen();
            if (eventAmountFen >= 0 && eventAmountFen != amount) {
                throw MarketingRevenueException.conflict("REVERSAL_EVENT_CORRUPT",
                        "同一退款事件的分录金额不一致，需人工核对");
            }
            eventAmountFen = amount;
            if (eventCurrency != null && !eventCurrency.equals(line.getEventCurrency())) {
                throw MarketingRevenueException.conflict("REVERSAL_EVENT_CONFLICT",
                        "同一退款事件的币种不一致，拒绝重放");
            }
            eventCurrency = line.getEventCurrency();
            if (eventOrderType != null && !eventOrderType.equals(line.getEventOrderType())) {
                throw MarketingRevenueException.conflict("REVERSAL_EVENT_CONFLICT",
                        "同一退款事件的原单不一致，拒绝重放");
            }
            eventOrderType = line.getEventOrderType();
            if (eventOrderId != null && !eventOrderId.equals(line.getEventOrderId())) {
                throw MarketingRevenueException.conflict("REVERSAL_EVENT_CONFLICT",
                        "同一退款事件的原单不一致，拒绝重放");
            }
            eventOrderId = line.getEventOrderId();
        }
        long currentRefundFen = refund.getRefundAmount() == null
                ? -1L : RevenueMoney.toFen(refund.getRefundAmount(), "REFUND_AMOUNT_INVALID", "退款金额");
        if (currentRefundFen != eventAmountFen) {
            throw MarketingRevenueException.conflict("REVERSAL_EVENT_CONFLICT",
                    "同一退款单的金额已变化，拒绝重放");
        }
        if (eventCurrency != null && refund.getRefundNo() != null
                && !eventCurrency.equals(allocation.getCurrency())) {
            throw MarketingRevenueException.conflict("REVERSAL_EVENT_CONFLICT",
                    "同一退款单的币种与原分配不一致，拒绝重放");
        }
        if (eventOrderType != null && !eventOrderType.equals(allocation.getOrderType())) {
            throw MarketingRevenueException.conflict("REVERSAL_EVENT_CONFLICT", "同一退款事件的原单不一致");
        }
        if (eventOrderId != null && !eventOrderId.equals(allocation.getOrderId())) {
            throw MarketingRevenueException.conflict("REVERSAL_EVENT_CONFLICT", "同一退款事件的原单不一致");
        }
        return toReversalResult(allocation, refund, eventRef, thisEvent, true);
    }

    /** 历史退款事件累计额：只统计反向分录里各事件已记录的 event_amount_fen（同事件只算一次）。 */
    private long sumEventAmounts(List<MarketingRevenueAllocationLine> all, String excludeEventRef) {
        Map<String, Long> perEvent = new HashMap<>();
        for (MarketingRevenueAllocationLine line : all) {
            if (line.getEntryType() == null || line.getEntryType() != MarketingRevenueAllocationLine.ENTRY_REVERSE) {
                continue;
            }
            if (line.getEventRef() == null) {
                continue;
            }
            if (excludeEventRef != null && excludeEventRef.equals(line.getEventRef())) {
                continue;
            }
            long amount = line.getEventAmountFen() == null ? 0L : line.getEventAmountFen();
            perEvent.merge(line.getEventRef(), amount, Math::max);
        }
        long total = 0;
        for (long amount : perEvent.values()) {
            total = Math.addExact(total, amount);
        }
        return total;
    }

    private RevenueAccrualResult toAccrualResult(MarketingRevenueAllocation allocation,
                                                 List<MarketingRevenueAllocationLine> lines,
                                                 boolean replayed) {
        RevenueAccrualResult result = new RevenueAccrualResult();
        result.setAllocationId(allocation.getId());
        result.setOrderType(allocation.getOrderType());
        result.setOrderId(allocation.getOrderId());
        result.setRuleId(allocation.getRuleId());
        result.setRuleKey(allocation.getRuleKey());
        result.setRuleVersion(allocation.getRuleVersion());
        result.setScenario(allocation.getScenario());
        result.setCurrency(allocation.getCurrency());
        result.setPaidAmount(allocation.getPaidAmount());
        result.setNetAmount(allocation.getNetAmount());
        result.setContractRef(allocation.getContractRef());
        result.setAuthorMemberId(allocation.getAuthorMemberId());
        result.setStatus(allocation.getStatus());
        result.setReplayed(replayed);
        List<RevenueAccrualResult.Line> views = new ArrayList<>(lines.size());
        long author = 0;
        long referrer = 0;
        long platform = 0;
        for (MarketingRevenueAllocationLine line : lines) {
            if (line.getEntryType() == null || line.getEntryType() != MarketingRevenueAllocationLine.ENTRY_ACCRUE) {
                continue;
            }
            RevenueAccrualResult.Line view = new RevenueAccrualResult.Line();
            view.setRole(line.getLineRole());
            view.setRecipientMemberId(line.getRecipientMemberId());
            view.setAmountFen(line.getAmountFen());
            view.setAmount(line.getAmount());
            view.setRate(line.getRate());
            view.setFundingSide(line.getFundingSide());
            views.add(view);
            long amount = line.getAmountFen() == null ? 0L : line.getAmountFen();
            if (MarketingRevenueAllocationLine.ROLE_AUTHOR.equals(line.getLineRole())) {
                author += amount;
            } else if (MarketingRevenueAllocationLine.ROLE_REFERRER.equals(line.getLineRole())) {
                referrer += amount;
            } else if (MarketingRevenueAllocationLine.ROLE_PLATFORM.equals(line.getLineRole())) {
                platform += amount;
            }
        }
        result.setLines(views);
        result.setAuthorAmount(RevenueMoney.toAmount(author));
        result.setReferrerAmount(RevenueMoney.toAmount(referrer));
        result.setPlatformAmount(RevenueMoney.toAmount(platform));
        return result;
    }

    private RevenueReversalResult toReversalResult(MarketingRevenueAllocation allocation,
                                                   RefundApplication refund,
                                                   String eventRef,
                                                   List<MarketingRevenueAllocationLine> thisEvent,
                                                   boolean replayed) {
        RevenueReversalResult result = new RevenueReversalResult();
        result.setAllocationId(allocation.getId());
        result.setOrderType(allocation.getOrderType());
        result.setOrderId(allocation.getOrderId());
        result.setRefundId(refund.getId());
        result.setEventRef(eventRef);
        result.setReversedAmount(allocation.getReversedAmount());
        result.setStatus(allocation.getStatus());
        result.setReplayed(replayed);
        long eventAmountFen = 0;
        List<RevenueReversalResult.Line> views = new ArrayList<>(thisEvent.size());
        for (MarketingRevenueAllocationLine line : thisEvent) {
            RevenueReversalResult.Line view = new RevenueReversalResult.Line();
            view.setRole(line.getLineRole());
            view.setRecipientMemberId(line.getRecipientMemberId());
            view.setAmountFen(line.getAmountFen());
            view.setAmount(line.getAmount());
            views.add(view);
            if (line.getEventAmountFen() != null) {
                eventAmountFen = Math.max(eventAmountFen, line.getEventAmountFen());
            }
        }
        result.setLines(views);
        result.setEventRefundAmount(RevenueMoney.toAmount(eventAmountFen));
        return result;
    }

    private int deriveScenario(MarketingAttribution attribution, Long contentOwnerMemberId) {
        if (attribution == null || attribution.getSharerMemberId() == null) {
            return MarketingRevenueRule.SCENARIO_PLATFORM_SELLS_AUTHOR_CONTENT;
        }
        return contentOwnerMemberId != null
                && attribution.getSharerMemberId().equals(contentOwnerMemberId)
                ? MarketingRevenueRule.SCENARIO_AUTHOR_OWN_CODE
                : MarketingRevenueRule.SCENARIO_REFERRER_RECOMMENDS;
    }

    private Integer requireOwnerType(CmsRegistration registration) {
        Long ownerType = registration.getOwnerType();
        if (ownerType == null || registration.getOwnerId() == null || registration.getOwnerId() <= 0) {
            throw MarketingRevenueException.conflict("ORDER_OWNER_MISSING", "订单缺少归属主体");
        }
        if (ownerType != OWNER_TOPIC && ownerType != OWNER_ACTIVITY) {
            throw MarketingRevenueException.conflict("ORDER_OWNER_UNSUPPORTED", "订单归属类型不在分配范围");
        }
        return ownerType.intValue();
    }

    /** 内容必须真实存在：否则连"卖的是什么"都不成立。 */
    private void requireContentExists(int ownerType, Long ownerId) {
        Object content = ownerType == OWNER_TOPIC
                ? topicService.selectCmsTopicById(ownerId)
                : activityService.selectCmsActivityById(ownerId);
        if (content == null) {
            throw MarketingRevenueException.conflict("CONTENT_NOT_FOUND", "订单归属内容不存在");
        }
    }

    /**
     * 只用于判断"是不是作者自带码"这个场景，<b>不作为收款人</b>。
     * 收款人由 {@link #requireContractPayee} 从受审合同读取。
     */
    private Long resolveContentOwnerMemberId(int ownerType, Long ownerId) {
        if (ownerType == OWNER_TOPIC) {
            com.chengyinhub.business.domain.CmsTopic topic = topicService.selectCmsTopicById(ownerId);
            return topic == null ? null : topic.getMemberId();
        }
        com.chengyinhub.business.domain.CmsActivity activity = activityService.selectCmsActivityById(ownerId);
        return activity == null ? null : activity.getMemberId();
    }

    /**
     * 收款人必须是规则里受审合同声明的权利人，且该会员真实存在且可用。
     *
     * <p>三道都要过：
     * <ol>
     *   <li>{@code contract_ref} 非空 —— 有受审合同来源；</li>
     *   <li>{@code payee_member_id} 非空 —— 合同点名了收款人（内容 owner 不算，只是内容归属）；</li>
     *   <li>该会员存在、status=1、del_flag=0 —— 不是一个写错的 id。</li>
     * </ol>
     * 任一不过即拒，不产生分录。
     */
    private Long requireContractPayee(MarketingRevenueRule rule) {
        if (isBlank(rule.getContractRef())) {
            throw MarketingRevenueException.conflict("AUTHOR_CONTRACT_MISSING",
                    "规则缺少受审交易合同，不能给真实订单授钱");
        }
        Long payeeMemberId = rule.getPayeeMemberId();
        if (payeeMemberId == null || payeeMemberId <= 0) {
            throw MarketingRevenueException.conflict("AUTHOR_CONTRACT_MISSING",
                    "规则未声明合同收款人，内容 owner 不能等同授权收款权利人");
        }
        UmsMember payee = memberService.selectUmsMemberById(payeeMemberId);
        boolean usable = payee != null
                && Long.valueOf(1L).equals(payee.getStatus())
                && Integer.valueOf(0).equals(payee.getDelFlag());
        if (!usable) {
            throw MarketingRevenueException.conflict("PAYEE_MEMBER_UNUSABLE",
                    "合同收款人账号不存在或不可用");
        }
        return payeeMemberId;
    }

    private static long fenOrZero(BigDecimal amount, String reasonCode, String label) {
        return amount == null ? 0L : RevenueMoney.toFen(amount, reasonCode, label);
    }

    /** 订单侧金额只作历史快照，允许为负(退款单会出现)，取绝对值后转分。 */
    private static long snapshotFen(BigDecimal amount) {
        if (amount == null) {
            return 0L;
        }
        return amount.abs().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
