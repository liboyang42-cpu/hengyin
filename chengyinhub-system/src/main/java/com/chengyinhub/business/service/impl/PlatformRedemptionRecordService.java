package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.PlatformRedemptionRecordMapper;

/**
 * F0575 平台兑换记录(只读)。三类来源分开查询、分开分页,不做任何同名合并汇总:
 * 名称相同的券/商品/礼物可能是不同业务对象,合并会丢掉原始业务 ID 与各自的履约状态。
 */
@Service
public class PlatformRedemptionRecordService
{
    @Autowired
    private PlatformRedemptionRecordMapper mapper;

    public List<Map<String, Object>> couponClaims(Long memberId, Long couponId, Integer useStatus)
    {
        return mapper.selectCouponClaims(memberId, couponId, useStatus);
    }

    public List<Map<String, Object>> pointsMallOrders(Long memberId, String orderSn, Long status)
    {
        return mapper.selectPointsMallOrders(memberId, orderSn == null ? null : orderSn.trim(), status);
    }

    public List<Map<String, Object>> giftConsumptions(Long memberId, Long giftId, Long activityId)
    {
        return mapper.selectGiftConsumptions(memberId, giftId, activityId);
    }
}
