package com.chengyinhub.web.controller.business;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.business.service.impl.PlatformRedemptionRecordService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.page.TableDataInfo;

/**
 * F0575 平台兑换记录。三个来源三个端点,各自沿用其原有列表权限:
 * 优惠券领取/核销 = business:couponhistory:list;积分商城订单 = business:order:list;
 * 礼物消费 = business:crowdfunding:list。每行带原始业务 ID、持有人、数量、履约状态,不做同名合并。
 */
@RestController
@RequestMapping("/business/redemption-records")
public class PlatformRedemptionRecordController extends BaseController
{
    @Autowired
    private PlatformRedemptionRecordService redemptionRecordService;

    @PreAuthorize("@ss.hasPermi('business:couponhistory:list')")
    @GetMapping("/coupon")
    public TableDataInfo couponClaims(@RequestParam(required = false) Long memberId,
                                      @RequestParam(required = false) Long couponId,
                                      @RequestParam(required = false) Integer useStatus)
    {
        startPage();
        return getDataTable(redemptionRecordService.couponClaims(memberId, couponId, useStatus));
    }

    @PreAuthorize("@ss.hasPermi('business:order:list')")
    @GetMapping("/points-order")
    public TableDataInfo pointsMallOrders(@RequestParam(required = false) Long memberId,
                                          @RequestParam(required = false) String orderSn,
                                          @RequestParam(required = false) Long status)
    {
        startPage();
        return getDataTable(redemptionRecordService.pointsMallOrders(memberId, orderSn, status));
    }

    @PreAuthorize("@ss.hasPermi('business:crowdfunding:list')")
    @GetMapping("/gift")
    public TableDataInfo giftConsumptions(@RequestParam(required = false) Long memberId,
                                          @RequestParam(required = false) Long giftId,
                                          @RequestParam(required = false) Long activityId)
    {
        startPage();
        return getDataTable(redemptionRecordService.giftConsumptions(memberId, giftId, activityId));
    }
}
