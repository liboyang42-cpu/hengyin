package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.enums.SettlementDetailState;
import com.chengyinhub.business.domain.vo.ClubSessionFeeRegistration;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.service.ISettlementClaimService;
import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 俱乐部场次「按核销付费」的商家费(第十二轮 k5,2026-11-05 业主拍板)。
 *
 * <p>规则:商家对一场俱乐部场次扫过团码({@code coop_group_verify}),且它在该主题的生效合作单是
 * 按核销付费(冻结条款 shareMode=2 / FIXED_PER_FULFILLMENT),场次结算时按「冻结的 fixed_fee × 参加人数」
 * 付给商家,从主理人的份额里出;平台仍是留存销售额的 3%,不变。扫码本身不动钱。
 *
 * <p>参加人数:本场报名 已付(payment_status=2)、未退(registration_status=2,且没有任何金额的已批准退款)、
 * 已参加(本场名册的到场事实 C-10:最新到场更正优先,否则领队签到、票被核销或有节点进度)、结算时没有因客诉暂扣。暂扣的那一单在客诉结案补结时再付。
 * 有逐座核销事实的多人单按座位数,否则按整单人数。
 *
 * <p>账本:每张计费报名把支付时落的主理人分账行(receiver_type=merchant)减去商家费,再给商家落一条
 * {@value #RECEIVER_TYPE} 行(自己的 settlement_claim),两行合计不变。结算后退款时既有冲销
 * ({@code RefundServiceImpl.allocateClawback})就按比例把商家那份也冲回,分毫不差。
 *
 * <p>拒结(告警 + 抛出,回滚本场领取,下轮重试仍告警):分成条款(shareMode=1)的商家扫了团码;
 * 冻结条款与合作单列不一致;商家费合计超过主理人份额;某张报名的商家费超过该报名的主理人分账行;
 * 主理人分账行缺失。引流(shareMode=0)不付钱。
 */
@Component
public class ClubSessionMerchantFee {

    private static final Logger logger = LoggerFactory.getLogger(ClubSessionMerchantFee.class);

    /** 余额流水 event_type:俱乐部场次结算付给合作商家的核销费(event_id = 活动 id)。 */
    public static final long EVENT_TYPE_SESSION_MERCHANT_FEE = 95L;
    /** settlement_detail.receiver_type:合作商家的场次核销费;不能用 merchant(那是场次收款人)。 */
    public static final String RECEIVER_TYPE = "coop_merchant";

    @Autowired
    private CoopOrderMapper coopOrderMapper;

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    @Autowired
    private ISettlementClaimService settlementClaimService;

    @Autowired(required = false)
    private AlarmService alarmService;

    /** 一张报名对一家商家的费用。 */
    public static final class RegistrationFee {
        private final Long registrationId;
        private final int heads;
        private final BigDecimal amount;

        RegistrationFee(Long registrationId, int heads, BigDecimal amount) {
            this.registrationId = registrationId;
            this.heads = heads;
            this.amount = amount;
        }

        public Long getRegistrationId() { return registrationId; }
        public int getHeads() { return heads; }
        public BigDecimal getAmount() { return amount; }
    }

    /** 一家商家(一张合作单)在本场的费用。 */
    public static final class Charge {
        private final Long orderId;
        private final Long merchantMemberId;
        private final BigDecimal fee;
        private final List<RegistrationFee> registrations;

        Charge(Long orderId, Long merchantMemberId, BigDecimal fee, List<RegistrationFee> registrations) {
            this.orderId = orderId;
            this.merchantMemberId = merchantMemberId;
            this.fee = fee;
            this.registrations = registrations;
        }

        public Long getOrderId() { return orderId; }
        public Long getMerchantMemberId() { return merchantMemberId; }
        public BigDecimal getFee() { return fee; }
        public List<RegistrationFee> getRegistrations() { return registrations; }

        public int heads() {
            int n = 0;
            for (RegistrationFee r : registrations) n += r.heads;
            return n;
        }

        public BigDecimal total() {
            BigDecimal sum = BigDecimal.ZERO.setScale(2);
            for (RegistrationFee r : registrations) sum = sum.add(r.amount);
            return sum;
        }
    }

    /** 本场(或补结的那一单)要付的商家费。 */
    public static final class Plan {
        private final List<Charge> charges;

        Plan(List<Charge> charges) { this.charges = charges; }

        public List<Charge> getCharges() { return charges; }

        public boolean isEmpty() { return total().signum() == 0; }

        public BigDecimal total() {
            BigDecimal sum = BigDecimal.ZERO.setScale(2);
            for (Charge c : charges) sum = sum.add(c.total());
            return sum;
        }

        /** 按商家合计(同一商家一场只有一行团码记录,这里只是稳妥地按收款人归并)。 */
        public Map<Long, BigDecimal> byMerchant() {
            Map<Long, BigDecimal> out = new LinkedHashMap<>();
            for (Charge c : charges) {
                BigDecimal amount = c.total();
                if (amount.signum() <= 0) continue;
                out.merge(c.merchantMemberId, amount, BigDecimal::add);
            }
            return out;
        }
    }

    private static final Plan EMPTY = new Plan(Collections.emptyList());

    /** 没有商家费的场次(非俱乐部场次)。 */
    public static Plan emptyPlan() {
        return EMPTY;
    }

    /**
     * 算出本场要付的商家费,不写任何东西。
     *
     * @param activity       场次
     * @param payeeMemberId  场次收款人(俱乐部当前主理人)
     * @param registrationId 空 = 整场(不含暂扣单);非空 = 客诉结案补结的那一单
     */
    public Plan plan(CmsActivity activity, Long payeeMemberId, Long registrationId) {
        if (activity == null || activity.getId() == null || activity.getClubId() == null
                || activity.getClubId() <= 0 || activity.getTopicId() == null) {
            return EMPTY;
        }
        List<CoopGroupVerify> scans = settlementDetailMapper.selectGroupVerifiesByActivity(activity.getId());
        if (scans == null || scans.isEmpty()) {
            return EMPTY;
        }
        Map<Long, CoopOrder> effective = new LinkedHashMap<>();
        List<CoopOrder> orders = coopOrderMapper.selectEffectiveOrdersByTopicId(activity.getTopicId());
        if (orders != null) {
            for (CoopOrder order : orders) {
                if (order != null && order.getId() != null) effective.put(order.getId(), order);
            }
        }
        List<ClubSessionFeeRegistration> registrations = null;
        List<Charge> charges = new ArrayList<>();
        for (CoopGroupVerify scan : scans) {
            CoopOrder order = scan == null ? null : effective.get(scan.getCoopOrderId());
            if (order == null || !"merchant".equals(order.getToType())
                    || !Objects.equals(order.getToId(), scan.getMerchantMemberId())
                    || !Objects.equals(order.getTopicId(), activity.getTopicId())
                    || !Objects.equals(scan.getTopicId(), activity.getTopicId())) {
                // 合作单已失效(取消/作废)或不是本主题的商家单:条款不再适用,不付钱。
                logger.info("俱乐部场次商家费:团码记录对应的合作单不在生效商家单里,不计费 activityId={} scanId={} orderId={}",
                        activity.getId(), scan == null ? null : scan.getId(), scan == null ? null : scan.getCoopOrderId());
                continue;
            }
            int mode = frozenShareMode(activity, order);
            if (mode == 0) {
                continue; // 引流:不付钱
            }
            if (mode == 1) {
                throw refuse(activity, "CLUB_SESSION_PERCENT_TERMS_UNSUPPORTED",
                        "商家 " + order.getToId() + " 的合作单 " + order.getId()
                                + " 是按销售额分成(shareMode=1),俱乐部场次没有分成规则,本场不结算,请人工处理");
            }
            BigDecimal fee = frozenFixedFee(activity, order);
            if (fee.signum() == 0) {
                continue;
            }
            Long merchant = order.getToId();
            if (merchant == null || merchant <= 0) {
                throw refuse(activity, "MERCHANT_PAYEE_MISSING", "合作单 " + order.getId() + " 没有商家收款人");
            }
            if (merchant.equals(payeeMemberId)) {
                // 主理人自己就是这家商家:钱从自己给自己,不记两条账。
                logger.info("俱乐部场次商家费:商家即场次收款人,不另付 activityId={} orderId={} memberId={}",
                        activity.getId(), order.getId(), merchant);
                continue;
            }
            if (registrations == null) {
                registrations = settlementDetailMapper.selectFeeRegistrationsForUpdate(activity.getId(), registrationId);
                if (registrations == null) registrations = Collections.emptyList();
            }
            List<RegistrationFee> fees = new ArrayList<>();
            for (ClubSessionFeeRegistration reg : registrations) {
                if (reg == null || reg.getRegistrationId() == null) continue;
                int heads = reg.heads();
                fees.add(new RegistrationFee(reg.getRegistrationId(), heads,
                        fee.multiply(BigDecimal.valueOf(heads)).setScale(2, RoundingMode.UNNECESSARY)));
            }
            charges.add(new Charge(order.getId(), merchant, fee, fees));
        }
        return charges.isEmpty() ? EMPTY : new Plan(charges);
    }

    /**
     * 本场主理人份额够不够付商家费。不够就拒结本场(告警),不编规则。
     */
    public void requireWithinPayeeShare(CmsActivity activity, Plan plan, BigDecimal payeeShare) {
        BigDecimal total = plan == null ? BigDecimal.ZERO : plan.total();
        BigDecimal share = payeeShare == null ? BigDecimal.ZERO : payeeShare;
        if (total.compareTo(share) > 0) {
            throw refuse(activity, "MERCHANT_FEE_EXCEEDS_PRINCIPAL_SHARE",
                    "商家核销费合计 " + total + " 超过主理人份额 " + share + ",本场不结算,请人工处理");
        }
    }

    /**
     * 写账本:每张计费报名的主理人分账行减去商家费,商家落一条 {@value #RECEIVER_TYPE} 行(待结算,随本场一起置已结算)。
     * 在场次结算事务内调用;任何一步不成立都抛出,连同本场领取一起回滚。
     */
    public void writeLedger(CmsActivity activity, Plan plan) {
        if (plan == null) return;
        for (Charge charge : plan.getCharges()) {
            for (RegistrationFee reg : charge.getRegistrations()) {
                if (reg.getAmount().signum() <= 0) continue;
                List<SettlementDetail> lines = settlementDetailMapper.selectPayeeLinesForUpdate(reg.getRegistrationId());
                if (lines == null || lines.size() != 1) {
                    throw refuse(activity, "PRINCIPAL_LEDGER_LINE_UNPROVEN",
                            "报名 " + reg.getRegistrationId() + " 的主理人分账行有 " + (lines == null ? 0 : lines.size())
                                    + " 条(应为 1 条),商家费记不进账,本场不结算");
                }
                SettlementDetail line = lines.get(0);
                if (charge.getMerchantMemberId().equals(line.getReceiverId())) {
                    throw refuse(activity, "MERCHANT_IS_LEDGER_PAYEE",
                            "报名 " + reg.getRegistrationId() + " 的分账收款人就是商家 " + charge.getMerchantMemberId()
                                    + ",本场不结算,请人工处理");
                }
                if (!Integer.valueOf(SettlementDetailState.PENDING.getCode()).equals(line.getStatus())
                        || settlementDetailMapper.reducePayeeLine(line.getId(), reg.getAmount()) != 1) {
                    throw refuse(activity, "MERCHANT_FEE_EXCEEDS_PRINCIPAL_LINE",
                            "报名 " + reg.getRegistrationId() + " 的商家核销费 " + reg.getAmount()
                                    + " 超过该报名的主理人分账 " + line.getSettlementAmount()
                                    + "(或该行已结算),本场不结算,请人工处理");
                }
                SettlementClaim claim = settlementClaimService.claim(SettlementClaim.request("ACTIVITY",
                        activity.getId(), "REGISTRATION", reg.getRegistrationId(), "MERCHANT",
                        charge.getMerchantMemberId(), "MERCHANT_PARTICIPATION", "COOP_ORDER", "COOP_ORDER",
                        charge.getOrderId()));
                SettlementDetail detail = new SettlementDetail();
                detail.setClaimId(claim.getId());
                detail.setEntryKind("ORIGIN");
                detail.setEventKey("ORIGIN");
                detail.setBatchId(0L);
                detail.setMerchantId(line.getMerchantId());
                detail.setSourceType("registration");
                detail.setSourceId(reg.getRegistrationId());
                detail.setReceiverType(RECEIVER_TYPE);
                detail.setReceiverId(charge.getMerchantMemberId());
                detail.setGrossAmount(reg.getAmount());
                detail.setCommissionAmount(BigDecimal.ZERO);
                detail.setSettlementAmount(reg.getAmount());
                detail.setStatus(SettlementDetailState.PENDING.getCode());
                detail.setIdempotencyKey("registration:" + reg.getRegistrationId() + ":" + RECEIVER_TYPE + ":"
                        + charge.getMerchantMemberId());
                if (settlementDetailMapper.insertAdjustment(detail) != 1) {
                    throw refuse(activity, "MERCHANT_FEE_LINE_ALREADY_WRITTEN",
                            "报名 " + reg.getRegistrationId() + " 已有商家 " + charge.getMerchantMemberId()
                                    + " 的核销费行,不重复记账,本场不结算");
                }
            }
        }
    }

    private int frozenShareMode(CmsActivity activity, CoopOrder order) {
        JSONObject terms;
        try {
            terms = order.getTermsSnapshot() == null ? null : JSON.parseObject(order.getTermsSnapshot());
        } catch (RuntimeException e) {
            terms = null;
        }
        Integer frozen = terms == null ? null : terms.getInteger("shareMode");
        if (frozen == null || !frozen.equals(order.getShareMode())) {
            throw refuse(activity, "FROZEN_TERMS_UNPROVEN",
                    "合作单 " + order.getId() + " 的冻结条款与合作单不一致(冻结 shareMode=" + frozen
                            + ",合作单 " + order.getShareMode() + "),本场不结算");
        }
        String compensation = terms.getString("compensationMode");
        if (compensation != null && !(frozen == 0 && "NONE".equals(compensation)
                || frozen == 1 && "SALES_PERCENT".equals(compensation)
                || frozen == 2 && "FIXED_PER_FULFILLMENT".equals(compensation))) {
            throw refuse(activity, "FROZEN_TERMS_UNPROVEN",
                    "合作单 " + order.getId() + " 的冻结条款 shareMode=" + frozen + " 与 compensationMode="
                            + compensation + " 不一致,本场不结算");
        }
        if (frozen < 0 || frozen > 2) {
            throw refuse(activity, "FROZEN_TERMS_UNPROVEN", "合作单 " + order.getId() + " 的 shareMode=" + frozen + " 无法识别");
        }
        return frozen;
    }

    private BigDecimal frozenFixedFee(CmsActivity activity, CoopOrder order) {
        BigDecimal frozen;
        try {
            frozen = JSON.parseObject(order.getTermsSnapshot()).getBigDecimal("fixedFee");
        } catch (RuntimeException e) {
            frozen = null;
        }
        BigDecimal column = order.getFixedFee();
        if (frozen == null || column == null || frozen.compareTo(column) != 0 || frozen.signum() < 0
                || frozen.stripTrailingZeros().scale() > 2) {
            throw refuse(activity, "FROZEN_FIXED_FEE_UNPROVEN",
                    "合作单 " + order.getId() + " 的冻结单价与合作单不一致(冻结 " + frozen + ",合作单 " + column
                            + "),本场不结算");
        }
        return frozen.setScale(2, RoundingMode.UNNECESSARY);
    }

    private ServiceException refuse(CmsActivity activity, String code, String detail) {
        String message = "CLUB_SESSION_MERCHANT_FEE_REFUSED:" + code + " activityId="
                + (activity == null ? null : activity.getId()) + " " + detail;
        logger.error("【结算告警】{}", message);
        if (alarmService != null) {
            try {
                alarmService.alarm("俱乐部场次商家核销费拒结", message);
            } catch (Exception e) {
                logger.error("[ALARM] 俱乐部场次商家核销费告警投递失败", e);
            }
        }
        return new ServiceException(message);
    }
}
