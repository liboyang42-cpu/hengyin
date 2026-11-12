package com.chengyinhub.business.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 渠道撤销判定结果。
 *
 * <p>{@code revocable=false} 不等于"已经撤销":本域不直接作废已付准入,撤销动作必须由原退款链路完成
 * (见 {@link IMarketingProductFulfillmentService#revokeChannel})。
 */
public final class ChannelRevocationOutcome {

    public String parentOrderNo;
    /** 该渠道单在本域落地的准入行。 */
    public List<Long> registrationIds = new ArrayList<>();
    /** true = 这些行可以交给原退款链路撤销(未核销、未在撤销终态)。 */
    public boolean revocable;
    /** true = 之前已撤销过(幂等命中),本次不做任何事。 */
    public boolean replayed;
    /** revocable=false 时的原因码(可解释,供渠道侧展示)。 */
    public String reasonCode;
    /** 面向人的说明。 */
    public String message;
}
