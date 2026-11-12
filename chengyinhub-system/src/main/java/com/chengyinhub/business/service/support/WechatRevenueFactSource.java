package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.WechatVerifiedPaymentFactMapper;
import com.chengyinhub.business.service.IChannelPurchasePaymentFactReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
/**
 * 生产实现：只读既有 {@code wx_verified_payment_fact}（SDK 查单后持久化的不可变事实）；
 * 微信事实不存在时，按 {@code cms_registration.parent_order_no} 回读**渠道已验证购买事实**。
 *
 * <p><b>为什么需要渠道分支</b>：OTA 渠道成交没有 {@code wx_verified_payment_fact} 记录，
 * 也不允许伪造一条。此前只有微信分支，导致渠道注册单应计恒为 {@code PAYMENT_FACT_MISSING}，
 * 退款冲销任务只能永久待重试。
 *
 * <p><b>列语义按既有 fact service/mapper 实证</b>（{@code WechatVerifiedPaymentFactService.columns()}
 * 与 {@code checked()}），不按字段名猜：
 * <ul>
 *   <li>溯源三列必须同时成立：{@code verification_outcome='VERIFIED'}、
 *       {@code verification_version=1}、{@code verification_method='SDK_QUERY_ORIGINAL_ORDER'}。
 *       不带这三列的旧行一律不采信。</li>
 *   <li>{@code fact_hash} 必须非空且等于行内规范摘要（由既有 service 写入时算好），
 *       否则无法做快照幂等，视同无事实。</li>
 *   <li><b>{@code original_payment_state} 记录的是"原支付"的交易状态</b>。
 *       REFUND 事实由 {@code WechatPaymentSdkGateway} 用 {@code original.getTradeState().name()} 写入，
 *       所以一笔成功支付退出来的 REFUND 事实带的是 {@code SUCCESS} 而不是 {@code REFUND}。
 *       因此本类<b>不</b>用该列判退款 —— 只用于确认原支付当时是成功的。</li>
 * </ul>
 *
 * <p><b>渠道分支的金额口径</b>：渠道验证事实是**整单**金额；每个注册行只能按自己在
 * {@code cms_registration.payable_amount} 上冻结的分摊额入账，且必须先证明
 * 「同 parent_order_no 全部行的分摊之和 == 渠道已验证总额」，否则视为事实不完整、
 * 返回 empty（该单继续 FEE/PAYMENT 待核，而不是拿总额重复入账）。
 *
 * <p>拒绝的替代口径（都不构成资金事实）：{@code cms_registration.payment_status} 本身、
 * total/original 回退金额、{@code refund_application.status=1}（后台批准）、HTTP 请求里的金额。
 */
@Component
public class WechatRevenueFactSource implements RevenueFactSource {

    /** 与 RefundApplication.PAYOUT_SUCCESS 对齐：原路确认到账。 */
    private static final int PAYOUT_SUCCESS = 1;
    private static final int VERIFICATION_VERSION = WechatVerifiedPaymentFact.VERIFICATION_VERSION;
    private static final String VERIFICATION_METHOD = WechatVerifiedPaymentFact.VERIFICATION_METHOD;
    /** 原支付交易状态：只有成功的原支付才可能产生可退的退款事实。 */
    private static final String ORIGINAL_PAYMENT_SUCCESS = "SUCCESS";

    /** 渠道已验证购买事实金额的币种口径与收入分配一致。 */
    private static final String REVENUE_CURRENCY = "CNY";
    /** 与既有 wx fact 同口径：摘要必须是 64 位 hex，否则无法做快照幂等。 */
    private static final String FACT_HASH_PATTERN = "[0-9a-f]{64}";
    /** cms_registration.payment_status：2 = 已支付。退款后为 4，不得再应计。 */
    private static final int PAYMENT_STATUS_PAID = 2;

    private final WechatVerifiedPaymentFactMapper factMapper;
    /** 渠道已验证购买事实回读（M8 实现）。缺失 = 渠道分支关闭，只走微信事实。 */
    private final IChannelPurchasePaymentFactReader channelPayments;
    /** 用于证明「同 parent_order_no 各行的分摊之和 == 渠道已验证总额」。 */
    private final CmsRegistrationMapper registrationMapper;

    /**
     * 只读微信事实（渠道分支关闭）。保留给不需要渠道来源的装配/测试。
     */
    public WechatRevenueFactSource(WechatVerifiedPaymentFactMapper factMapper) {
        this(factMapper, null, null);
    }

    @Autowired
    public WechatRevenueFactSource(WechatVerifiedPaymentFactMapper factMapper,
                                   IChannelPurchasePaymentFactReader channelPayments,
                                   CmsRegistrationMapper registrationMapper) {
        this.factMapper = factMapper;
        this.channelPayments = channelPayments;
        this.registrationMapper = registrationMapper;
    }

    @Override
    public Optional<PaymentFact> verifiedPayment(CmsRegistration registration) {
        if (registration == null || blank(registration.getRegistrationNo())) {
            return Optional.empty();
        }
        String expectedTransaction = registration.getTransactionId();
        for (Map<String, Object> row : factsOf(registration.getRegistrationNo())) {
            if (!WechatVerifiedPaymentFact.Kind.PAYMENT.name().equals(text(row, "fact_kind"))) {
                continue;
            }
            if (!trusted(row)) {
                continue;
            }
            long amountFen = fen(row, "amount_fen");
            long totalFen = fen(row, "total_fen");
            String currency = text(row, "currency");
            // 支付事实必须自洽：金额=总额、币种 CNY、且与原单落库的交易号一致。
            if (amountFen <= 0 || amountFen != totalFen || !"CNY".equals(currency)) {
                continue;
            }
            String transactionId = text(row, "transaction_id");
            if (!blank(expectedTransaction) && !expectedTransaction.equals(transactionId)) {
                continue; // 通道交易号与原单不符 → 不是这单的事实
            }
            return Optional.of(new PaymentFact(amountFen, currency,
                    text(row, "fact_hash"), transactionId));
        }
        // 微信事实不存在：只有"没有微信交易号"的单才允许走渠道来源。
        // 有交易号的单若微信事实缺失，说明事实还没落库/被回滚，不能拿渠道事实顶替。
        if (!blank(expectedTransaction)) {
            return Optional.empty();
        }
        return channelPayment(registration);
    }

    /**
     * 渠道成交的已验证付款事实。
     *
     * <p>三条硬约束（任一不成立都返回 empty，让应计继续待核，绝不猜）：
     * <ol>
     *   <li>行必须是**已支付**状态（退款后 4 / 待支付 0/1 都不应计）；</li>
     *   <li>渠道验证事实必须是 VERIFIED 且金额>0、币种 CNY、摘要 64 位 hex；</li>
     *   <li>同 {@code parent_order_no} 全部行的 {@code payable_amount} 之和必须等于渠道已验证总额 ——
     *       否则分摊与事实不一致，返回 empty 而不是按总额重复入账。</li>
     * </ol>
     * 返回的金额是**本行自己**冻结的分摊额，不是整单金额。
     */
    private Optional<PaymentFact> channelPayment(CmsRegistration registration) {
        if (channelPayments == null || registrationMapper == null) {
            return Optional.empty();
        }
        if (!Integer.valueOf(PAYMENT_STATUS_PAID).equals(registration.getPaymentStatus())) {
            return Optional.empty();
        }
        String parentOrderNo = registration.getParentOrderNo();
        if (blank(parentOrderNo)) {
            return Optional.empty();
        }
        IChannelPurchasePaymentFactReader.PaymentFact fact = channelPayments.verifiedPaymentOf(parentOrderNo);
        if (fact == null || fact.amountFen <= 0) {
            return Optional.empty();
        }
        if (!REVENUE_CURRENCY.equals(fact.currency)) {
            return Optional.empty();
        }
        if (blank(fact.factHash) || !fact.factHash.matches(FACT_HASH_PATTERN)) {
            return Optional.empty();
        }
        List<CmsRegistration> siblings = registrationMapper.selectByParentOrderNo(parentOrderNo);
        if (siblings == null || siblings.isEmpty()) {
            return Optional.empty();
        }
        long ownFen = -1L;
        long sumFen = 0L;
        for (CmsRegistration sibling : siblings) {
            if (sibling == null || sibling.getPayableAmount() == null) {
                return Optional.empty();
            }
            long fen;
            try {
                fen = sibling.getPayableAmount().movePointRight(2).longValueExact();
            } catch (ArithmeticException fractionalCent) {
                return Optional.empty();
            }
            if (fen < 0) {
                return Optional.empty();
            }
            try {
                sumFen = Math.addExact(sumFen, fen);
            } catch (ArithmeticException overflow) {
                return Optional.empty();
            }
            if (registration.getId() != null && registration.getId().equals(sibling.getId())) {
                ownFen = fen;
            }
        }
        if (ownFen <= 0 || sumFen != fact.amountFen) {
            return Optional.empty();
        }
        // transactionId 只作可读来源标记；渠道事实的可信锚是 factHash + 整单金额守恒。
        return Optional.of(new PaymentFact(ownFen, REVENUE_CURRENCY, fact.factHash,
                "channel:" + fact.providerKey + ":" + fact.channelOrderNo));
    }

    @Override
    public Optional<RefundFact> verifiedRefund(RefundApplication refund, CmsRegistration registration) {
        if (refund == null || registration == null) {
            return Optional.empty();
        }
        // 1) 通道是否真的退了钱：只有 PAYOUT_SUCCESS 才算，后台批准(status=1)不算。
        if (refund.getPayoutStatus() == null || refund.getPayoutStatus() != PAYOUT_SUCCESS) {
            return Optional.empty();
        }
        if (blank(refund.getRefundNo()) || blank(registration.getRegistrationNo())) {
            return Optional.empty();
        }
        BigDecimal refundAmount = refund.getRefundAmount();
        if (refundAmount == null || refundAmount.signum() <= 0) {
            return Optional.empty();
        }
        long expectedRefundFen;
        try {
            expectedRefundFen = refundAmount.movePointRight(2).longValueExact();
        } catch (ArithmeticException fractionalCent) {
            return Optional.empty(); // 小数分的退款金额不是合法资金事实
        }
        for (Map<String, Object> row : factsOf(registration.getRegistrationNo())) {
            if (!WechatVerifiedPaymentFact.Kind.REFUND.name().equals(text(row, "fact_kind"))) {
                continue;
            }
            if (!trusted(row)) {
                continue;
            }
            if (!refund.getRefundNo().equals(text(row, "merchant_event_no"))) {
                continue; // 退款号（out_refund_no）不符 → 不是这笔退款
            }
            String currency = text(row, "currency");
            long amountFen = fen(row, "amount_fen");
            long totalFen = fen(row, "total_fen");
            if (!"CNY".equals(currency) || amountFen != expectedRefundFen || totalFen <= 0) {
                continue;
            }
            // 原支付当时必须是成功态；注意这里**不**要求它等于 "REFUND"。
            String originalState = text(row, "original_payment_state");
            if (!ORIGINAL_PAYMENT_SUCCESS.equals(originalState)) {
                continue;
            }
            // 退款事实里的原支付交易号必须与原单一致，防止串单。
            String transactionId = text(row, "transaction_id");
            if (!blank(registration.getTransactionId())
                    && !registration.getTransactionId().equals(transactionId)) {
                continue;
            }
            return Optional.of(new RefundFact(amountFen, totalFen, currency,
                    text(row, "fact_hash"), refund.getRefundNo()));
        }
        return Optional.empty();
    }

    private List<Map<String, Object>> factsOf(String businessOutTradeNo) {
        List<Map<String, Object>> rows = factMapper.selectSuccessesByBusiness(businessOutTradeNo);
        return rows == null ? List.of() : rows;
    }

    /**
     * 溯源闸：与既有 {@code WechatVerifiedPaymentFactService.checked()} 同口径。
     * 只读本域也要自己判一次 —— 不能假设上游永远写对。
     */
    private static boolean trusted(Map<String, Object> row) {
        if (!"VERIFIED".equals(text(row, "verification_outcome"))) {
            return false;
        }
        if (fen(row, "verification_version") != VERIFICATION_VERSION) {
            return false;
        }
        if (!VERIFICATION_METHOD.equals(text(row, "verification_method"))) {
            return false;
        }
        String hash = text(row, "fact_hash");
        // 摘要是 64 位 hex；没有它就没法做快照幂等。
        return hash != null && hash.matches("[0-9a-f]{64}");
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long fen(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            return -1L;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
