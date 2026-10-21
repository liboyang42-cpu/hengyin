package com.chengyinhub.business.service.rewardpledge;

import java.util.Date;
import org.springframework.stereotype.Component;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;

/** Production adapter. One grantCouponWithReceipt, then redeemCoupon or reverseRedemption.
 * grantCouponBySource stays on reward_drop. Topic-completion grantOnce and roam delivery
 * keep their own ledgers and are not called here. */
@Component
public class SmsHistoryCouponPort implements PledgeCouponPort {
    private final ISmsCouponHistoryService history;
    private final SmsCouponMapper coupons;
    private final SmsCouponHistoryMapper histories;

    public SmsHistoryCouponPort(ISmsCouponHistoryService history, SmsCouponMapper coupons, SmsCouponHistoryMapper histories) {
        this.history = history;
        this.coupons = coupons;
        this.histories = histories;
    }

    public CouponView load(long couponId) {
        SmsCoupon coupon = coupons.selectSmsCouponById(couponId);
        if (coupon == null || coupon.getId() == null) return null;
        return new CouponView(coupon.getId(), coupon.getMemberId(), coupon.getMerchantId(), coupon.getStartTime(), coupon.getEndTime());
    }

    public Long grantOnce(long memberId, long couponId) {
        SmsCoupon coupon = coupons.selectSmsCouponById(couponId);
        if (coupon == null) return null;
        return history.grantCouponWithReceipt(memberId, coupon);
    }

    public String redeem(long historyId, long couponOwnerMemberId, long merchantId, long actorMemberId, String actorRole) {
        return history.redeemCoupon(historyId, couponOwnerMemberId, merchantId, actorMemberId, actorRole).name();
    }

    /** Same CAS statement redeemCoupon uses when the use window has ended. */
    public boolean expireUnused(long historyId, Date now) {
        return histories.expireUnusedCoupon(historyId, now) == 1;
    }

    public boolean reverse(long historyId, String reason, long operatorUserId) {
        return history.reverseRedemption(historyId, reason, operatorUserId);
    }

    public int useStatus(long historyId) {
        com.chengyinhub.business.domain.SmsCouponHistory row = histories.selectSmsCouponHistoryById(historyId);
        if (row == null || row.getUseStatus() == null) return -1;
        return row.getUseStatus();
    }
}
