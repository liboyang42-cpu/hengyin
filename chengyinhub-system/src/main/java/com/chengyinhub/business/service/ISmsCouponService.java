package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.SmsCoupon;

/**
 * 优惠券Service接口
 * 
 * @author webxh
 * @date 2025-09-01
 */
public interface ISmsCouponService 
{
    /**
     * 查询优惠券
     * 
     * @param id 优惠券主键
     * @return 优惠券
     */
    public SmsCoupon selectSmsCouponById(Long id);

    /**
     * 查询优惠券列表
     * 
     * @param smsCoupon 优惠券
     * @return 优惠券集合
     */
    public List<SmsCoupon> selectSmsCouponList(SmsCoupon smsCoupon);

    /**
     * 新增优惠券
     * 
     * @param smsCoupon 优惠券
     * @return 结果
     */
    public int insertSmsCoupon(SmsCoupon smsCoupon);

    /**
     * 修改优惠券
     * 
     * @param smsCoupon 优惠券
     * @return 结果
     */
    public int updateSmsCoupon(SmsCoupon smsCoupon);

    /**
     * 批量删除优惠券
     * 
     * @param ids 需要删除的优惠券主键集合
     * @return 结果
     */
    public int deleteSmsCouponByIds(Long[] ids);

    /**
     * 删除优惠券信息
     * 
     * @param id 优惠券主键
     * @return 结果
     */
    public int deleteSmsCouponById(Long id);


    /**
     * 批量更新指定用户的过期优惠券状态
     * @param memberId 用户ID（发布优惠券的用户）
     */
    public int updateExpiredCouponsByMemberId(Long memberId);

    /** 按会员统计未删除优惠券数（配额） */
    public int selectSmsCouponNumByMemberId(Long memberId);

    /** 核销时 use_count +1（原子） */
    public int incrementUseCount(Long id);

    /**
     * 批量读券状态(id→status),给钱包读侧做展示投影用;空入参返回空 Map,不查库。
     * 只读两列,合并到 sms_coupon_history 行上,避免逐行 selectSmsCouponById。
     */
    public java.util.Map<Long, Integer> selectCouponStatuses(java.util.List<Long> ids);

    /**
     * 商家停发自己的券:status(0/1)→4 的条件更新,不发已领的、只阻新增发放。
     * 归属/范围校验由调用方在拿到券实体后做;这里 CAS 只用 owner + 状态兜底。
     *
     * @return 1=本次停发成功;0=并发下状态已变或非本人券,调用方回读判因
     */
    public int stopIssuingCoupon(Long id, Long ownerMemberId);

    /** M0-2 生成优惠券动态二维码 token = historyId.epochSec.md5(签名)，60s 由前端刷新 */
    public String generateCouponQrToken(Long historyId);

    /** M0-2 校验并解析动态二维码 token，返回 couponHistoryId；无效/过期返回 null */
    public Long verifyCouponQrToken(String token);
}
