package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.SmsCoupon;
import org.apache.ibatis.annotations.Param;

/**
 * 优惠券Mapper接口
 * 
 * @author webxh
 * @date 2025-09-01
 */
public interface SmsCouponMapper 
{
    /** CRM 触达仅可读取当前 owner 自有、商家范围且有效有库存的券。 */
    public List<SmsCoupon> selectOwnedActiveCoupons(@Param("ownerMemberId") Long ownerMemberId,
                                                    @Param("merchantRowId") Long merchantRowId,
                                                    @Param("limit") Integer limit);

    public SmsCoupon selectOwnedActiveCoupon(@Param("ownerMemberId") Long ownerMemberId,
                                              @Param("merchantRowId") Long merchantRowId,
                                              @Param("couponId") Long couponId);

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
     * 删除优惠券
     * 
     * @param id 优惠券主键
     * @return 结果
     */
    public int deleteSmsCouponById(Long id);

    /**
     * 批量删除优惠券
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteSmsCouponByIds(Long[] ids);


    /**
     * 批量更新指定用户的过期优惠券状态
     * @param memberId 用户ID（发布优惠券的用户）
     */
    public int updateExpiredCouponsByMemberId(Long memberId);

    /** 按会员统计未删除优惠券数（配额） */
    public int selectSmsCouponNumByMemberId(Long memberId);

    /** 领取时 receive_count +1（原子） */
    public int incrementReceiveCount(Long id);

    /** C1 限量占坑:仅当 receive_count &lt; publish_count 时 +1,返回受影响行数(0=已抢光,并发安全) */
    public int tryIncrementReceiveCount(Long id);

    /** 发放占库:当前状态非失效/停发且当前库存允许时 +1,0=拒发;统计接口保持独立。 */
    public int tryReserveIssuableCoupon(Long id);

    /** 核销时 use_count +1（原子） */
    public int incrementUseCount(Long id);

    /** 撤销核销时 use_count -1（原子,use_count>0 守卫防冲成负数） */
    public int decrementUseCount(Long id);

    /**
     * 批量读券状态(钱包读侧投影用):只取 id/status 两列,避免为每行多查一次。
     *
     * @param ids 券 id 集合,调用方保证非空
     */
    public List<SmsCoupon> selectCouponStatusByIds(@Param("ids") List<Long> ids);

    /**
     * 商家停发:仅限本人(owner)名下、未删除、未开始或进行中的券,条件更新 status→4。
     * 拿 0 行 = 并发下状态已变(已结束/已失效/已停发)或非本人券,由调用方判因。
     *
     * @return 受影响行数(1=本次停发,0=未改动)
     */
    public int stopIssuingCoupon(@Param("id") Long id, @Param("ownerMemberId") Long ownerMemberId);
}
