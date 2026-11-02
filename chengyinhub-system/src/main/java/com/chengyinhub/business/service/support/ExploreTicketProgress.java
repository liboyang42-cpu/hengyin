package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.common.constant.PurchaseKind;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ③ 探索票的「还剩几家」读面(P-03,2026-11-02)。
 *
 * <p>verification_status 对 ③ 只表示「被核销过至少一次」(退款闸用它),不是「用完」。
 * 票夹、我的、订单页要的是:必选章全部核销,或场次结束,才算这张票用完。这里只读,不改任何事实。
 */
public final class ExploreTicketProgress {
    private ExploreTicketProgress() { }

    public static boolean isExplorePass(CmsRegistration row) {
        return row != null && Integer.valueOf(PurchaseKind.EXPLORE_PASS).equals(row.getPurchaseKind());
    }

    /** 已失效(2)的章不计入;返回 null 表示没有权益清单,调用方保持原口径。 */
    public static Map<String, Object> of(List<RegistrationEntitlement> entitlements, boolean completed, Date endDate, Date now) {
        if (entitlements == null || entitlements.isEmpty()) return null;
        int required = 0;
        int redeemed = 0;
        for (RegistrationEntitlement e : entitlements) {
            if (e == null || e.getStatus() == null) continue;
            if (e.getStatus() == 0 || e.getStatus() == 1) required++;
            if (e.getStatus() == 1) redeemed++;
        }
        if (required == 0) return null;
        boolean allRedeemed = completed || redeemed >= required;
        boolean ended = endDate != null && now != null && !now.before(endDate);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("redeemed", redeemed);
        out.put("required", required);
        out.put("completed", allRedeemed);
        out.put("ended", ended);
        out.put("active", !allRedeemed && !ended);
        return out;
    }

    /** 订单列表「已完成」口径:③ 有读面时按读面;其余照旧(首次扫码成功或已核销)。 */
    public static boolean listCompleted(CmsRegistration row) {
        Map<String, Object> progress = row.getExploreProgress();
        if (isExplorePass(row) && progress != null) {
            return !Boolean.TRUE.equals(progress.get("active"));
        }
        return (Integer.valueOf(2).equals(row.getPaymentStatus()) && row.getFirstSuccessfulScanAt() != null)
                || Integer.valueOf(1).equals(row.getVerificationStatus());
    }
}
