package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;

/**
 * 原单分配快照的规范摘要。
 *
 * <p>用途：同一原单重复事件读到既有行时，比较摘要判断「同键同 payload → 重放」还是
 * 「同键异 payload → 冲突」。摘要覆盖原单、来源事实、支付事实摘要、规则冻结
 * (ruleKey+version，不含可变 ruleId)、合同收款人、净额分项与实付。
 *
 * <p>关键在于摘要**不参与规则解析**：重试时先比摘要，绝不读当前 ACTIVE 规则，
 * 所以改规则/退规则/换来源都不会让旧重试失败或被重新定价。
 */
public final class RevenueSnapshotHash {

    private RevenueSnapshotHash() {
    }

    public static String of(MarketingRevenueAllocation a) {
        StringBuilder sb = new StringBuilder(320);
        sb.append("v2");
        sb.append('|').append(a.getOrderType());
        sb.append('|').append(a.getOrderId());
        sb.append('|').append(a.getAllocationKey());
        sb.append('|').append(a.getRuleKey());
        sb.append('|').append(a.getRuleVersion());
        sb.append('|').append(a.getScenario());
        sb.append('|').append(nz(a.getAttributionId()));
        sb.append('|').append(nz(a.getSharerMemberId()));
        sb.append('|').append(a.getCampaignKey() == null ? "" : a.getCampaignKey());
        sb.append('|').append(a.getAuthorMemberId());
        sb.append('|').append(a.getContractRef() == null ? "" : a.getContractRef());
        sb.append('|').append(a.getOwnerType());
        sb.append('|').append(a.getOwnerId());
        sb.append('|').append(a.getCurrency());
        sb.append('|').append(a.getPaymentFactHash() == null ? "" : a.getPaymentFactHash());
        sb.append('|').append(fen(a.getPaidFen()));
        sb.append('|').append(fen(a.getOriginalAmountFen()));
        sb.append('|').append(fen(a.getDiscountFen()));
        sb.append('|').append(fen(a.getPointDeductFen()));
        sb.append('|').append(fen(a.getTaxFen()));
        sb.append('|').append(fen(a.getPaymentFeeFen()));
        sb.append('|').append(fen(a.getChannelCommissionFen()));
        sb.append('|').append(a.getFeeBasisRef() == null ? "" : a.getFeeBasisRef());
        sb.append('|').append(fen(a.getNetFen()));
        sb.append('|').append(a.getReferralFundingSide());
        return sha256Hex(sb.toString());
    }

    public static String sha256Hex(String value) {
        return com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.sha256(value);
    }

    private static String fen(Long value) {
        return value == null ? "0" : value.toString();
    }

    private static String nz(Long value) {
        return value == null ? "" : value.toString();
    }
}
