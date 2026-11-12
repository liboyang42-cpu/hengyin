package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.dto.GiftClaimRequest;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** 礼品领取来源：签发待领取码，领取一次 CAS，成功后由原注册服务产出准入。 */
public interface IGiftEntitlementService {

    /** 下单发权益时调用；返回明文领取码（只此一次），哈希落库。 */
    String issue(MarketingProductOrder order, Date now);

    /**
     * 领取。校验码/收件人/状态后 CAS 0→1，再调用 M2-F 的权威 grant 为领取人创建
     * {@code cms_registration}，把 id 回写 {@code claimed_registration_id}。
     * 若桥接端口不可用，明确 503 且不标记已领取完成（不伪造可玩）。
     */
    Map<String, Object> claim(long callerMemberId, GiftClaimRequest request);

    List<Map<String, Object>> giftsBoughtBy(long callerMemberId, int limit);

    List<Map<String, Object>> giftsClaimedBy(long callerMemberId, int limit);

    /** 退款撤销：码作废/未使用可撤销；原 registration 撤销由 M2-F 权威服务执行。 */
    void revokeForOrder(MarketingProductOrder order, String reason, Date now);
}
