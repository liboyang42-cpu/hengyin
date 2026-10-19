package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.SmsCouponHistory;
import org.apache.ibatis.annotations.Param;

/**
 * 优惠券领取使用记录Mapper接口
 * 
 * @author webxh
 * @date 2025-09-01
 */
public interface SmsCouponHistoryMapper 
{
    /**
     * 查询优惠券领取使用记录
     * 
     * @param id 优惠券领取使用记录主键
     * @return 优惠券领取使用记录
     */
    public SmsCouponHistory selectSmsCouponHistoryById(Long id);

    /** CAS 失败后的当前读，避免 RR 快照误判核销结果。 */
    public SmsCouponHistory selectSmsCouponHistoryByIdForUpdate(Long id);

    /** 未使用且处于 [start_time, end_time] 有效期内才取得核销唯一处理权(start/end 均含端点)。 */
    public int claimUnusedCoupon(@Param("id") Long id, @Param("useTime") java.util.Date useTime);

    /** 仅把尚未使用的过期券推进为已过期。 */
    public int expireUnusedCoupon(@Param("id") Long id, @Param("updateTime") java.util.Date updateTime);

    /**
     * 撤销核销的条件置位:use_status 1→0(券恢复可再次使用)。
     * 返回 0 行 = 该行当前不是已核销态(重复撤销/从未核销)——这是撤销唯一的幂等闸,
     * 调用方拿到 0 行必须立即返回,不得继续回退计数/作废分成。
     */
    public int reverseUsedCoupon(@Param("id") Long id, @Param("reversedAt") java.util.Date reversedAt);

    /**
     * 查询优惠券领取使用记录列表
     * 
     * @param smsCouponHistory 优惠券领取使用记录
     * @return 优惠券领取使用记录集合
     */
    public List<SmsCouponHistory> selectSmsCouponHistoryList(SmsCouponHistory smsCouponHistory);

    /**
     * 新增优惠券领取使用记录
     * 
     * @param smsCouponHistory 优惠券领取使用记录
     * @return 结果
     */
    public int insertSmsCouponHistory(SmsCouponHistory smsCouponHistory);

    /**
     * 修改优惠券领取使用记录
     * 
     * @param smsCouponHistory 优惠券领取使用记录
     * @return 结果
     */
    public int updateSmsCouponHistory(SmsCouponHistory smsCouponHistory);

    /**
     * 删除优惠券领取使用记录
     * 
     * @param id 优惠券领取使用记录主键
     * @return 结果
     */
    public int deleteSmsCouponHistoryById(Long id);

    /**
     * 批量删除优惠券领取使用记录
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteSmsCouponHistoryByIds(Long[] ids);

    /**
     * 批量更新指定用户的过期优惠券状态
     * @param memberId 用户ID（发布优惠券的用户）
     */
    public int updateExpiredCouponsByMemberId(Long memberId);

    /** 拍板4 冲回:这位玩家这主题通关券对应的领取记录 id;没有返 null。 */
    public Long selectCompletionRewardHistoryId(@Param("memberId") Long memberId, @Param("topicId") Long topicId);

    public int voidUnusedCoupon(@Param("id") Long id, @Param("note") String note);

    /** F0572:领取时冻结的面额;NULL=迁移前领取 */
    public java.math.BigDecimal selectClaimRuleAmount(@Param("id") Long id);

    /** F0572:按 use_status 统计已领取券,每行 {useStatus,total} */
    public List<java.util.Map<String, Object>> countClaimsByUseStatus(@Param("couponId") Long couponId);

    /** F0572:平台失效受影响持券(未使用/未删除/未过期) */
    public List<SmsCouponHistory> selectUnusedHoldersByCouponId(@Param("couponId") Long couponId,
                                                                @Param("now") java.util.Date now,
                                                                @Param("limit") int limit);

    public int countUnusedHoldersByCouponId(@Param("couponId") Long couponId, @Param("now") java.util.Date now);
}
