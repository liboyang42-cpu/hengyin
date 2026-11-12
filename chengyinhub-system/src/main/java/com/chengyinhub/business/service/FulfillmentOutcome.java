package com.chengyinhub.business.service;

import java.util.ArrayList;
import java.util.List;

/** 成交结果。 */
public final class FulfillmentOutcome {

    public String parentOrderNo;

    /** 按 lines 顺序;首行持有 requestId / 支付事实 / 渠道总额。 */
    public List<Long> registrationIds = new ArrayList<>();

    /** true = 命中幂等重放:调用方不得再次发放权益或再次扣款。 */
    public boolean replayed;

    /**
     * true = 本次有"首次使用起算"的行尚未激活,这些行**没有**创建准入。
     * 激活走 {@link IMarketingProductFulfillmentService#activateOnFirstUse}。
     */
    public boolean pendingActivation;

    /** 尚未激活的主题 id(仅 pendingActivation=true 时非空)。 */
    public List<Long> pendingActivationTopicIds = new ArrayList<>();
}
