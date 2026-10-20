package com.chengyinhub.business.service.coupon;

/**
 * NEW PROPOSED PORT, NOT WIRED. No existing merchant-owner check proves player eligibility.
 * An implementation must validate an already committed source receipt, distinct actor and
 * beneficiary, tenant/merchant ownership, exact disclosed digest and stable source/nonce.
 * Current permission/source rows must be locked in the caller's transaction through commit.
 * A remote response alone is not an implementation of this transaction/authority contract.
 */
public interface CouponTermsGrantAuthority {
    Approval lockCurrentApproved(Intent intent);
    enum Disposition { COMMITTED_ELIGIBLE, EVIDENCE_ONLY, FALLBACK_COMPLETED }
    final class Intent {
        public final long issuerMemberId, merchantId, actorMemberId, beneficiaryMemberId, couponId;
        public final String sourceReceiptId, nonce, disclosedTermsDigest;
        public Intent(long issuer, long merchant, long actor, long beneficiary, long coupon,
                      String receipt, String nonce, String disclosedTermsDigest) {
            if (issuer <= 0 || merchant <= 0 || actor <= 0 || beneficiary <= 0 || coupon <= 0
                    || !key(receipt) || !key(nonce) || disclosedTermsDigest == null || !disclosedTermsDigest.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Exact source, identities, nonce and disclosed terms required");
            this.issuerMemberId = issuer; this.merchantId = merchant; this.actorMemberId = actor;
            this.beneficiaryMemberId = beneficiary; this.couponId = coupon; this.sourceReceiptId = receipt;
            this.nonce = nonce; this.disclosedTermsDigest = disclosedTermsDigest;
        }
        private static boolean key(String value) { return value != null && value.matches("[A-Za-z0-9:._-]{1,128}"); }
        public String digest() {
            return CouponTermsSnapshot.sha256("coupon-terms-grant-v1\n" + issuerMemberId + "\n" + merchantId + "\n"
                    + actorMemberId + "\n" + beneficiaryMemberId + "\n" + couponId + "\n" + sourceReceiptId + "\n" + nonce + "\n" + disclosedTermsDigest);
        }
    }
    final class Approval {
        public final String intentDigest;
        public final Disposition disposition;
        public final long validUntilEpochMillis;
        public Approval(Intent intent, Disposition disposition, long validUntilEpochMillis) {
            this.intentDigest = intent.digest(); this.disposition = disposition; this.validUntilEpochMillis = validUntilEpochMillis;
        }
    }
}
