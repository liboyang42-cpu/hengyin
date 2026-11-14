package com.chengyinhub.business.service.rewardpledge;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Award;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Pledge;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Version;

/**
 * The app's NonCashRewardService wire (Core/NonCashRewardService.swift RewardWire): award ids are 64 lowercase hex,
 * rewardKind is PHYSICAL, quantity is 1, times are epoch milliseconds, and validityStatus is derived from asOf the same
 * way the app re-derives it. The subject lives only in factRef {@code THEME:{topicId}:session:{sessionId}}.
 */
public final class NonCashWire {
    private static final SecureRandom RANDOM = new SecureRandom();

    private NonCashWire() {}

    public static String newAwardId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }

    public static boolean isAwardId(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /** @param useStatus sms_coupon_history.use_status: 0 unused, 1 used, 2 expired, -1 unknown. */
    public static Map<String, Object> reward(Award award, Version version, Pledge pledge, int useStatus, long asOf) {
        String contextId;
        String instanceId;
        String[] fact = award.factRef.split(":");
        if (fact.length == 4 && "THEME".equals(fact[0]) && "session".equals(fact[2])) {
            contextId = fact[1];
            instanceId = fact[3];
        } else {
            // COMPENSATION:{originalAwardId}:{compensationKey}: the original award and the key name the instance.
            int split = award.factRef.lastIndexOf(':');
            contextId = award.factRef.substring(0, Math.max(split, 1));
            instanceId = split > 0 ? award.factRef.substring(split + 1) : award.factRef;
        }
        String state;
        if (useStatus == 1) state = "REDEEMED";
        else if (useStatus == 2 || "RETURNED".equals(award.state)) state = "EXPIRED";
        else state = "AWARDED";
        String validity = asOf < version.redeemStart ? "UPCOMING" : asOf >= version.redeemEnd ? "ELAPSED" : "IN_WINDOW";
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("awardId", award.awardId);
        body.put("contextType", "THEME");
        body.put("contextId", contextId);
        body.put("releaseId", award.versionId);
        body.put("instanceId", instanceId);
        body.put("rulesVersion", award.ruleVersion);
        body.put("merchantId", String.valueOf(pledge.merchantMemberId));
        body.put("storeId", String.valueOf(pledge.storeId));
        body.put("rewardKind", "PHYSICAL");
        body.put("rewardTitle", pledge.supplyRef);
        body.put("quantity", 1);
        body.put("validFrom", version.redeemStart);
        body.put("validUntil", version.redeemEnd);
        body.put("redemptionConditions", version.terms);
        body.put("state", state);
        body.put("awardedAt", award.createdAt);
        body.put("asOf", asOf);
        body.put("validityStatus", validity);
        body.put("fulfillmentStatus", "UNVERIFIED");
        return body;
    }
}
