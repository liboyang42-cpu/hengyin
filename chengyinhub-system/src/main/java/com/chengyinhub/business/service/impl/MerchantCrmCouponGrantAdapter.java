package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.vo.MerchantCrmCouponOptionVO;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 将 CRM 券触达收口到真实 sms_coupon_history grant，并强制复核 owner/有效期/库存。 */
@Service
public class MerchantCrmCouponGrantAdapter {
    @Autowired private SmsCouponMapper smsCouponMapper;
    @Autowired private ISmsCouponHistoryService couponHistoryService;

    public SmsCoupon requireOwnedActive(Long ownerMemberId, Long merchantRowId, Long couponId) {
        requireMerchantScope(ownerMemberId, merchantRowId);
        if (couponId == null || couponId <= 0) throw new ServiceException("请选择有效优惠券", 400);
        SmsCoupon coupon = smsCouponMapper.selectOwnedActiveCoupon(ownerMemberId, merchantRowId, couponId);
        if (coupon == null) throw new ServiceException("优惠券不存在、不属于当前商家或已失效", 404);
        return coupon;
    }

    public Long grant(Long ownerMemberId, Long merchantRowId, Long memberId, Long couponId) {
        if (memberId == null || memberId <= 0) {
            throw new GrantRejectedException("ASSET_INVALID", "领取会员无效", null);
        }
        SmsCoupon coupon;
        try {
            coupon = requireOwnedActive(ownerMemberId, merchantRowId, couponId);
        } catch (ServiceException e) {
            throw new GrantRejectedException(Integer.valueOf(404).equals(e.getCode())
                    ? "COUPON_NOT_ISSUED" : "ASSET_INVALID", e.getMessage(), e);
        }
        Long receipt = couponHistoryService.grantCouponWithReceipt(memberId, coupon);
        if (receipt == null) {
            throw new GrantRejectedException("COUPON_NOT_ISSUED", "优惠券未发出，可能已停发、失效或库存不足", null);
        }
        return receipt;
    }

    public List<MerchantCrmCouponOptionVO> list(Long ownerMemberId, Long merchantRowId) {
        requireMerchantScope(ownerMemberId, merchantRowId);
        List<SmsCoupon> rows = smsCouponMapper.selectOwnedActiveCoupons(ownerMemberId, merchantRowId, 50);
        List<MerchantCrmCouponOptionVO> out = new ArrayList<>();
        if (rows == null) return out;
        for (SmsCoupon row : rows) {
            MerchantCrmCouponOptionVO item = new MerchantCrmCouponOptionVO();
            item.setId(row.getId());
            item.setName(row.getName());
            item.setEndTime(row.getEndTime());
            if (row.getPublishCount() != null && row.getPublishCount() > 0) {
                item.setRemainingCount(Math.max(0L, row.getPublishCount()
                        - (row.getReceiveCount() == null ? 0L : row.getReceiveCount())));
            }
            out.add(item);
        }
        return out;
    }

    public static final class GrantRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String failureCode;

        public GrantRejectedException(String failureCode, String message, Throwable cause) {
            super(message, cause);
            this.failureCode = failureCode;
        }

        public String getFailureCode() {
            return failureCode;
        }
    }

    private void requireMerchantScope(Long ownerMemberId, Long merchantRowId) {
        if (ownerMemberId == null || ownerMemberId <= 0 || merchantRowId == null || merchantRowId <= 0) {
            throw new ServiceException("商家身份无效，请重新登录", 403);
        }
    }
}
