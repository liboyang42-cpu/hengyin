package com.chengyinhub.business.service.rewardpledge;

import java.util.Date;

/** The existing coupon grant and redemption source. Not a second coupon ledger. */
public interface PledgeCouponPort {
    CouponView load(long couponId);

    /** One call to the existing grant. Null means the coupon row refused issuance. */
    Long grantOnce(long memberId, long couponId);

    /** Existing redeemCoupon result name. */
    String redeem(long historyId, long couponOwnerMemberId, long merchantId, long actorMemberId, String actorRole);

    boolean expireUnused(long historyId, Date now);

    boolean reverse(long historyId, String reason, long operatorUserId);

    int useStatus(long historyId);

    final class CouponView {
        public final long id;
        public final Long memberId;
        public final Long merchantId;
        public final Date startTime;
        public final Date endTime;

        public CouponView(long id, Long memberId, Long merchantId, Date startTime, Date endTime) {
            this.id = id;
            this.memberId = memberId;
            this.merchantId = merchantId;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }
}
