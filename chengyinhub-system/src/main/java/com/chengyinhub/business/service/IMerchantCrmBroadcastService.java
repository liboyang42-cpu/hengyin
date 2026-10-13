package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.MerchantCrmBroadcastRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmBroadcastPreviewVO;
import com.chengyinhub.business.domain.vo.MerchantCrmBroadcastVO;

/**
 * 商家定向广播(客户页当前筛选一次性通知)。
 * 只发给「已明确同意该商家站内触达」的客户;频次 = 商家每天最多 3 条、客户每天最多 1 条。
 */
public interface IMerchantCrmBroadcastService {
    /** 服务端重算筛选人群与可触达人数,不读客户端 memberId。 */
    MerchantCrmBroadcastPreviewVO preview(Long principalMemberId, MerchantCrmBroadcastRequest request);

    /** 冻结名单并逐人真实投递;同 requestId 幂等重放。 */
    MerchantCrmBroadcastVO send(Long principalMemberId, MerchantCrmBroadcastRequest request);
}
