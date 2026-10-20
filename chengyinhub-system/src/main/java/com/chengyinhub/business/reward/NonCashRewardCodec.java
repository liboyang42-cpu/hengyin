package com.chengyinhub.business.reward;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.chengyinhub.business.reward.NonCashRewardContracts.*;

final class NonCashRewardCodec {
    private final ObjectMapper mapper = new ObjectMapper();
    String write(Pledge p) {
        try { return mapper.writeValueAsString(p); }
        catch (Exception e) { throw new IllegalStateException("INVALID_PLEDGE_SNAPSHOT", e); }
    }
    Pledge read(String value) {
        try {
            JsonNode n = mapper.readTree(value), s = n.required("scope");
            return new Pledge(text(n,"pledgeId"),text(n,"pledgeVersion"),text(n,"approvalId"),
                    new Scope(ScopeType.valueOf(text(s,"type")),text(s,"contextId"),text(s,"releaseId"),text(s,"instanceId")),
                    text(n,"rulesVersion"),text(n,"awardSlot"),number(n,"merchantId"),number(n,"storeId"),number(n,"productId"),
                    number(n,"skuId"),number(n,"quantity"),number(n,"validFrom"),number(n,"validUntil"),
                    Allocation.valueOf(text(n,"allocation")),RewardKind.valueOf(text(n,"rewardKind")),text(n,"rewardTitle"),text(n,"redemptionConditions"));
        } catch (Exception e) { throw new IllegalStateException("INVALID_PLEDGE_SNAPSHOT", e); }
    }
    private String text(JsonNode n,String k) { JsonNode v=n.required(k); require(v.isTextual(),"INVALID_PLEDGE_SNAPSHOT");return v.textValue(); }
    private long number(JsonNode n,String k) { JsonNode v=n.required(k);require(v.isIntegralNumber()&&v.canConvertToLong(),"INVALID_PLEDGE_SNAPSHOT");return v.longValue(); }
}
