package com.chengyinhub.business.collection;

import com.chengyinhub.business.payout.SellerOfRecordPolicy;

/**
 * The outcome of {@link CollectionPolicyEngine#decide}. {@code subMchid} is set only for {@link CollectionPolicy#SPLIT}.
 * {@code reason} is a stable code shown to operations (never to players).
 */
public record CollectionDecision(CollectionPolicy policy, SellerOfRecordPolicy.SellerType sellerType, Long sellerId,
                                 String subMchid, String reason) {

    public static final String OFFICIAL = "OFFICIAL";
    public static final String PLATFORM_PRODUCT = "PLATFORM_PRODUCT";
    public static final String MODEL_OFF = "MODEL_OFF";
    public static final String SUB_MERCHANT = "SUB_MERCHANT";
    public static final String SELLER_UNKNOWN = "SELLER_UNKNOWN";
    public static final String SELLER_NOT_BOUND = "SELLER_NOT_BOUND";
    public static final String POLICY_REFUSE = "POLICY_REFUSE";
    public static final String SPLIT_RAILS_OFF = "SPLIT_RAILS_OFF";
    public static final String SUB_MCHID_IS_PLATFORM = "SUB_MCHID_IS_PLATFORM";
    public static final String DUES_SPLIT_NOT_BUILT = "DUES_SPLIT_NOT_BUILT";
    public static final String APP_CHANNEL_SPLIT_NOT_BUILT = "APP_CHANNEL_SPLIT_NOT_BUILT";

    public boolean platform() { return policy == CollectionPolicy.PLATFORM; }

    public boolean split() { return policy == CollectionPolicy.SPLIT; }

    public boolean refused() { return policy == CollectionPolicy.REFUSE; }

    static CollectionDecision platform(String reason) {
        return new CollectionDecision(CollectionPolicy.PLATFORM, SellerOfRecordPolicy.SellerType.PLATFORM, null, null, reason);
    }

    static CollectionDecision refuse(SellerOfRecordPolicy.SellerType type, Long sellerId, String reason) {
        return new CollectionDecision(CollectionPolicy.REFUSE,
                type == null ? SellerOfRecordPolicy.SellerType.UNKNOWN : type, sellerId, null, reason);
    }
}
