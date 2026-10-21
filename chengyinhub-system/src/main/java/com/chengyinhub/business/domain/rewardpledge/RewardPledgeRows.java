package com.chengyinhub.business.domain.rewardpledge;

/** Row shapes for the pledge ledger. Not a client payload. */
public final class RewardPledgeRows {
    private RewardPledgeRows() {}

    public static final class Policy {
        public String policyCode, ruleVersion, decision;
        public Long actorMemberId;
    }

    public static final class Pledge {
        public String pledgeId, supplyRef, terms, state;
        public long storeId, merchantMemberId, couponId, serviceStart, serviceEnd, redeemStart, redeemEnd, createdAt;
        public int quantity, minorRestricted;
    }

    public static final class Version {
        public String versionId, pledgeId, payloadHash, ruleVersion, state, merchantHash, reviewerHash, terms;
        public int versionNo, quantity, minorRestricted;
        public long serviceStart, serviceEnd, redeemStart, redeemEnd;
        public Long merchantMemberId, merchantConfirmedAt, reviewerMemberId, frozenAt;
    }

    public static final class Stock {
        public String versionId;
        public int quantity, available, reserved, awardedUnredeemed, redeemed, expired, frozenUnits, withdrawn;
        public long versionNo;

        public int sum() {
            return available + reserved + awardedUnredeemed + redeemed + expired + frozenUnits + withdrawn;
        }
    }

    public static final class Intent {
        public long ownerId;
        public String requestId, payloadHash, versionId, branch, subjectId, state, awardId, errorCode;
    }

    public static final class Award {
        public String awardId, versionId, ruleVersion, rewardScope, factRef, state, requestId;
        public long beneficiaryId, createdAt;
        public Long historyId;
    }

    public static final class CaseRow {
        public String caseId, awardId, versionId, reason, state;
        public long ownerId, createdAt;
    }
}
