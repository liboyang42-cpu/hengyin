package com.chengyinhub.business.payout;

import org.springframework.stereotype.Component;

/** 默认服务商：未选择任何灵活用工平台。永远拒绝，不产生任何打款。 */
@Component
public class DisabledCreatorPayoutProvider implements CreatorPayoutProvider {
    @Override public String code() { return "none"; }
    @Override public Result submit(Request request) { throw new IllegalStateException("CREATOR_PAYOUT_PROVIDER_DISABLED"); }
    @Override public Result query(String outPayoutNo) { throw new IllegalStateException("CREATOR_PAYOUT_PROVIDER_DISABLED"); }
}
