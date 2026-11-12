package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MarketingOrderAdjustment;
import com.chengyinhub.business.domain.MarketingPromotionRefundFact;
import com.chengyinhub.business.domain.dto.RefundSettledFact;
import com.chengyinhub.business.mapper.MarketingPromotionRefundFactMapper;
import com.chengyinhub.business.service.IMarketingPromotionService;
import com.chengyinhub.business.service.IRefundSettledConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 退款已真实到账 → 撤销促销资格。M4 在「退款到账」广播上的唯一 consumer。
 *
 * <p><b>为什么挂在到账广播而不是退款审批</b>:审批通过、待人工、PROCESSING 都不代表钱回到了顾客账上;
 * 只有 {@code updatePayoutTerminal} CAS 成 PAYOUT_SUCCESS 之后构造的 {@link RefundSettledFact}
 * 才是资金事实。早于它撤销资格 = "钱还没退到就失去优惠",而退款本身还可能失败。
 *
 * <p><b>先解析订单再算累计</b>:组合/多票订单的促销快照写在首单上,退款却可能落在同一
 * {@code parent_order_no} 的另一张报名上。直接拿被退 id 查快照会"永远查不到 → 永远不撤销"。
 * 解析只认两条既有事实(同 parent_order_no、同 member_id),命中多条视为数据异常并拒绝 ——
 * 不猜 id,也不把不同订单合并统计。
 *
 * <p><b>部分退款不撤销</b>(按既有冻结口径):退款链本就按比例冲销
 * ({@code refundRatio = refundAmount / paidAmount} 夹取到 [0,1]),单笔部分退款后订单仍成立、
 * 仍可继续申请剩余金额。因此只在<b>累计实退 ≥ 原支付总额</b>时撤销 ——
 * 一次 50% 不是整单退;反过来,两次 50% 各自都不是全额,必须累计。
 *
 * <p><b>幂等</b>:到账事实按 {@code refund-settled:{registrationId}:{refundApplicationId}} 唯一写入,
 * 重投走 {@code on duplicate key update id = id}(不改行、不报错);撤销是 "status 1 → 2" 条件更新。
 *
 * <p><b>与本域无关的单静默返回</b>:解析不到促销成交订单 = 这单从未用过促销。抛异常会让一笔
 * 合法退款的回调整笔回滚 —— 契约明确要求静默。
 */
@Service
public class MarketingPromotionRefundSettledConsumer implements IRefundSettledConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarketingPromotionRefundSettledConsumer.class);

    /** 促销金额一律是元;别的币种说明事实串了域,不能按分当元算。 */
    private static final String CURRENCY_CNY = "CNY";

    private final IMarketingPromotionService promotionService;
    private final MarketingPromotionRefundFactMapper refundFactMapper;

    public MarketingPromotionRefundSettledConsumer(IMarketingPromotionService promotionService,
                                                   MarketingPromotionRefundFactMapper refundFactMapper) {
        this.promotionService = promotionService;
        this.refundFactMapper = refundFactMapper;
    }

    @Override
    public void onRefundSettled(RefundSettledFact fact) {
        requireUsableFact(fact);
        Long refundedRegistrationId = fact.registrationId();

        Long promoOrderId = resolvePromoOrderId(refundedRegistrationId);
        if (promoOrderId == null) {
            // 该报名（或其 parent_order_no）没有任何促销成交快照 = 与促销无关。静默返回是契约要求。
            return;
        }
        if (fact.refundedFen() < 0) {
            throw new IllegalStateException("退款事实金额为负,拒绝处理 registrationId=" + refundedRegistrationId);
        }

        recordFact(fact, promoOrderId, refundedRegistrationId);
        long paidTotal = paidTotalOf(promoOrderId);

        long refundedTotal = refundFactMapper.sumRefundedFen(
                MarketingPromotionRefundFact.ORDER_REGISTRATION, promoOrderId);
        if (paidTotal <= 0) {
            // 总额未知时不撤销:宁可留下一张待对账的单,也不凭缺失数据撤销用户已享的优惠。
            log.warn("marketing promotion refund total unknown, kept orderId={} refundedFen={}",
                    promoOrderId, refundedTotal);
            return;
        }
        if (refundedTotal < paidTotal) {
            log.info("marketing promotion kept: partial refund orderId={} refundedFen={} paidFen={}",
                    promoOrderId, refundedTotal, paidTotal);
            return;
        }
        promotionService.revokeForOrder(
                MarketingOrderAdjustment.ORDER_REGISTRATION, promoOrderId, new Date());
        log.info("marketing promotion revoked by full refund orderId={} refundedRegistrationId={} paidFen={}",
                promoOrderId, refundedRegistrationId, paidTotal);
    }

    /**
     * 解析这笔退款对应的促销成交订单。
     *
     * <p>0 条 → 与本域无关(返回 null);1 条 → 就是它;多条 → 一单出现多份促销快照,
     * 说明建单侧写出过重复快照,这里必须拒绝而不是挑一个合并统计。
     */
    private Long resolvePromoOrderId(Long refundedRegistrationId) {
        List<Long> candidates = refundFactMapper.resolvePromoOrderIds(
                MarketingPromotionRefundFact.ORDER_REGISTRATION, refundedRegistrationId);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("同一订单命中多份促销成交快照,拒绝合并统计 registrationId="
                    + refundedRegistrationId + " candidates=" + candidates);
        }
        return candidates.get(0);
    }

    /**
     * 同一订单各笔退款事实必须报同一个原支付总额。数值不同 = 事实串单或重复支付,
     * 继续累计会算出错误的"是否退完",所以直接拒绝由回调重投/人工核对。
     */
    private long paidTotalOf(Long promoOrderId) {
        List<Long> totals = refundFactMapper.distinctTotalFen(
                MarketingPromotionRefundFact.ORDER_REGISTRATION, promoOrderId);
        if (totals == null || totals.isEmpty()) {
            return 0L;
        }
        if (totals.size() > 1) {
            throw new IllegalStateException("同一订单出现多个原支付总额,拒绝累计 orderId="
                    + promoOrderId + " totals=" + totals);
        }
        return totals.get(0) == null ? 0L : totals.get(0);
    }

    /** 到账事实先落账再判断:并发两个回调只有一个能插入,另一个走 dup-key 静默,累计口径一致。 */
    private void recordFact(RefundSettledFact fact, Long promoOrderId, Long refundedRegistrationId) {
        MarketingPromotionRefundFact row = new MarketingPromotionRefundFact();
        row.setOrderType(MarketingPromotionRefundFact.ORDER_REGISTRATION);
        row.setOrderId(promoOrderId);
        row.setRefundRegistrationId(refundedRegistrationId);
        row.setRefundApplicationId(fact.refundApplicationId());
        row.setOutRefundNo(fact.outRefundNo());
        row.setRefundedFen(Long.valueOf(fact.refundedFen()));
        row.setTotalFen(Long.valueOf(fact.totalFen()));
        // 幂等键仍用"被退那张 + 退款申请":同一笔退款重投必须命中同一行,
        // 不能因为解析出的订单 id 变化而写成两条。
        row.setIdempotencyKey(fact.idempotencyKey());
        row.setCreateTime(new Date());
        refundFactMapper.upsertRefundFact(row);
    }

    /**
     * 事实必须自证是「已校验的到账事实」:有订单 id、有退款申请 id、有幂等键、有不可变事实摘要、币种一致。
     * 缺一个都说明广播内容被拼装错了,必须响亮失败由回调重投 —— 静默跳过等于漏账。
     */
    private void requireUsableFact(RefundSettledFact fact) {
        if (fact == null) {
            throw new IllegalArgumentException("退款到账事实为空");
        }
        if (fact.registrationId() == null || fact.registrationId() <= 0) {
            throw new IllegalStateException("退款到账事实缺少报名单 id");
        }
        if (fact.refundApplicationId() == null || fact.refundApplicationId() <= 0) {
            throw new IllegalStateException("退款到账事实缺少退款申请 id");
        }
        if (fact.idempotencyKey() == null || fact.idempotencyKey().trim().isEmpty()) {
            throw new IllegalStateException("退款到账事实缺少幂等键");
        }
        if (fact.verifiedRefundFactHash() == null || fact.verifiedRefundFactHash().trim().isEmpty()) {
            throw new IllegalStateException("退款到账事实缺少不可变事实摘要");
        }
        String currency = fact.currency();
        if (currency != null && !currency.trim().isEmpty()
                && !CURRENCY_CNY.equalsIgnoreCase(currency.trim())) {
            throw new IllegalStateException("退款到账事实币种非人民币: " + currency);
        }
    }
}
