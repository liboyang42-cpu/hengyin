package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.vo.MyCouponUsabilityVO;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.service.ISmsCouponService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家自己已持有优惠券的**只读**可用性投影(一个端点用,不另设接口层)。
 *
 * <p>判定顺序刻意与 {@code SmsCouponHistoryServiceImpl.redeemCoupon} 一致:
 * 已核销 → 已过期(领取记录 end_time) → 平台手动失效(status=3) → 未开始 → 可用。
 * 商家停发(status=4)的券已领的照常可核销,所以这里也判可用 —— 与「发券闸 ≠ 核销闸」同一口径。
 *
 * <p><b>纯读</b>:不调用 updateExpiredCouponsByMemberId,不做任何状态回写;核销仍以
 * 动态码 + CAS + 同事务结算/审计为唯一权威。这里判「可用」不代表核销一定成功(并发下
 * 可能已被核销),只用于钱包/券包的展示与解释。
 *
 * <p>只投影调用方**自己**的领取记录,不枚举可领券、不查后台私有/未发布券。
 */
@Service
public class CouponUsabilityService {

    @Autowired
    private SmsCouponHistoryMapper smsCouponHistoryMapper;

    @Autowired(required = false)
    private ISmsCouponService smsCouponService;

    /**
     * 列出该会员已持有的券及其当前可用性。
     *
     * @param memberId 持有人(必须来自登录态,不接受前端指定他人)
     * @return 最快到期的在前(end_time 升序,无到期日排最后;同到期日按领取记录 id 倒序);无券返回空列表
     */
    public List<MyCouponUsabilityVO> listMine(Long memberId) {
        List<MyCouponUsabilityVO> out = new ArrayList<>();
        if (memberId == null || memberId <= 0 || smsCouponHistoryMapper == null) {
            return out;
        }
        SmsCouponHistory probe = new SmsCouponHistory();
        probe.setMemberId(memberId);
        probe.setDelFlag(0);
        List<SmsCouponHistory> rows = smsCouponHistoryMapper.selectSmsCouponHistoryList(probe);
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        Date now = new Date();
        Map<Long, SmsCoupon> couponCache = new HashMap<>();
        for (SmsCouponHistory row : rows) {
            if (row == null || row.getCouponId() == null) continue;
            if (row.getDelFlag() != null && row.getDelFlag() != 0) continue;
            // 防御:只投影调用方自己的行,即使上游查询条件被改坏也不越权。
            if (row.getMemberId() != null && !memberId.equals(row.getMemberId())) continue;
            out.add(toVO(row, coupon(row.getCouponId(), couponCache), now));
        }
        out.sort(CouponUsabilityService::compareSoonestExpiryFirst);
        return out;
    }

    /** 最快到期的在前;无到期日排最后;同到期日按领取记录 id 倒序(新领的在前)。 */
    private static int compareSoonestExpiryFirst(MyCouponUsabilityVO left, MyCouponUsabilityVO right) {
        Date leftEnd = left.getEndTime();
        Date rightEnd = right.getEndTime();
        if (leftEnd == null && rightEnd == null) return compareIdsDesc(left, right);
        if (leftEnd == null) return 1;
        if (rightEnd == null) return -1;
        int byEnd = leftEnd.compareTo(rightEnd);
        return byEnd != 0 ? byEnd : compareIdsDesc(left, right);
    }

    private static int compareIdsDesc(MyCouponUsabilityVO left, MyCouponUsabilityVO right) {
        Long leftId = left.getHistoryId();
        Long rightId = right.getHistoryId();
        if (leftId == null && rightId == null) return 0;
        if (leftId == null) return 1;
        if (rightId == null) return -1;
        return rightId.compareTo(leftId);
    }

    /**
     * 同一张券模板在本次投影里只读一次。券被删到查不到时缓存 null 也保留,
     * 避免每行重复查库(computeIfAbsent 不会缓存 null)。
     */
    private SmsCoupon coupon(Long couponId, Map<Long, SmsCoupon> cache) {
        if (cache.containsKey(couponId)) {
            return cache.get(couponId);
        }
        SmsCoupon loaded = smsCouponService == null ? null : smsCouponService.selectSmsCouponById(couponId);
        cache.put(couponId, loaded);
        return loaded;
    }

    private MyCouponUsabilityVO toVO(SmsCouponHistory row, SmsCoupon coupon, Date now) {
        MyCouponUsabilityVO vo = new MyCouponUsabilityVO();
        vo.setHistoryId(row.getId());
        vo.setCouponId(row.getCouponId());
        vo.setStartTime(row.getStartTime());
        vo.setEndTime(row.getEndTime());
        vo.setUseStatus(row.getUseStatus());
        if (coupon != null) {
            vo.setName(coupon.getName());
            vo.setCouponType(coupon.getCouponType());
            vo.setAmount(coupon.getAmount());
            vo.setDescription(coupon.getDescription());
            vo.setScopeType(coupon.getScopeType());
            vo.setMerchantId(Integer.valueOf(1).equals(coupon.getScopeType()) ? coupon.getMerchantId() : null);
        }
        String reason = reason(row, coupon, now);
        vo.setReason(reason);
        vo.setUsable(MyCouponUsabilityVO.USABLE.equals(reason));
        return vo;
    }

    /** 与核销闸同序;只判展示态。 */
    private static String reason(SmsCouponHistory row, SmsCoupon coupon, Date now) {
        if (coupon == null) {
            return MyCouponUsabilityVO.COUPON_MISSING;
        }
        if (Integer.valueOf(1).equals(row.getUseStatus())) {
            return MyCouponUsabilityVO.USED;
        }
        if (Integer.valueOf(2).equals(row.getUseStatus())) {
            return MyCouponUsabilityVO.EXPIRED;
        }
        if (row.getEndTime() != null && row.getEndTime().before(now)) {
            return MyCouponUsabilityVO.EXPIRED;
        }
        // 3=平台手动失效;4=商家停发只管新增发放,已领的仍可核销 ⇒ 到这里继续往下判。
        if (Integer.valueOf(3).equals(coupon.getStatus())) {
            return MyCouponUsabilityVO.DISABLED;
        }
        if (row.getStartTime() != null && row.getStartTime().after(now)) {
            return MyCouponUsabilityVO.NOT_STARTED;
        }
        return MyCouponUsabilityVO.USABLE;
    }
}
