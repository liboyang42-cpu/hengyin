package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.CmsRegistration;
import java.math.BigDecimal;
import java.util.*;

/** 同一批当前锁读报名的旧主题现金口径；不扩展 ACTIVITY、退款净额或履约资格。 */
public final class TopicSettlementCashBasis {
    private BigDecimal totalSales = BigDecimal.ZERO;
    private BigDecimal verifiedSales = BigDecimal.ZERO;
    private final Set<Long> verifiedMembers = new HashSet<>();
    private final List<Long> verifiedRegistrationIds = new ArrayList<>();

    public static TopicSettlementCashBasis fromLockedRows(Long topicId, List<CmsRegistration> rows) {
        if (topicId == null || rows == null) throw invalid("CURRENT_CASH_ROWS_REQUIRED");
        TopicSettlementCashBasis basis = new TopicSettlementCashBasis();
        Set<Long> seen = new HashSet<>();
        for (CmsRegistration row : rows) {
            if (row == null || row.getId() == null || !seen.add(row.getId())
                    || !(Long.valueOf(1).equals(row.getOwnerType()) && topicId.equals(row.getOwnerId())
                        || Long.valueOf(2).equals(row.getOwnerType()) && topicId.equals(row.getTopicId()))) {
                throw invalid("CURRENT_CASH_ROW_IDENTITY_CONFLICT");
            }
            if (Integer.valueOf(1).equals(row.getPaymentStatus())) {
                throw invalid("PAYMENT_CONFIRMATION_UNRESOLVED");
            }
            if (Integer.valueOf(2).equals(row.getPaymentStatus()) && Integer.valueOf(1).equals(row.getRegistrationStatus())) {
                // Inspect the full locked set before cash/verification filters. A later legitimate SUCCESS can repair 2/1 to 2/2.
                throw invalid("PAID_REGISTRATION_HALF_STATE_UNRESOLVED:registrationId=" + row.getId());
            }
            if (!Integer.valueOf(2).equals(row.getPaymentStatus())) continue;
            // 已删活动壳的已付资金也不能被经典主题基数吞掉；未核销不会绕过此闸门。
            if (Long.valueOf(2).equals(row.getOwnerType())) throw invalid("ACTIVITY_CASH_BASIS_UNSUPPORTED");
            boolean cash = Integer.valueOf(2).equals(row.getRegistrationStatus());
            boolean verified = Integer.valueOf(1).equals(row.getVerificationStatus());
            if (!cash && !verified) continue;
            BigDecimal amount = row.getPayableAmount();
            if (amount == null || amount.signum() < 0) throw invalid("CURRENT_PAID_AMOUNT_REQUIRED");
            // 保持旧 SUM 的状态口径；本类不把“核销”改成商家履约资格。
            if (cash) basis.totalSales = basis.totalSales.add(amount);
            if (verified) {
                basis.verifiedSales = basis.verifiedSales.add(amount);
                basis.verifiedRegistrationIds.add(row.getId());
                if (row.getMemberId() != null) basis.verifiedMembers.add(row.getMemberId());
            }
        }
        Collections.sort(basis.verifiedRegistrationIds);
        return basis;
    }

    public BigDecimal getTotalSales() { return totalSales; }
    public BigDecimal getVerifiedSales() { return verifiedSales; }
    public int getVerifiedHeads() { return verifiedMembers.size(); }
    public List<Long> getVerifiedRegistrationIds() { return Collections.unmodifiableList(verifiedRegistrationIds); }
    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("MANUAL_REPAIR:" + reason);
    }
}
