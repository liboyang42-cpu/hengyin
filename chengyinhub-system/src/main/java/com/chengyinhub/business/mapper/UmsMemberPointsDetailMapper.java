package com.chengyinhub.business.mapper;

import java.util.List;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;

/**
 * 用户积分明细Mapper接口
 *
 * @author ruoyi
 * @date 2025-08-26
 */
public interface UmsMemberPointsDetailMapper
{


    public int selectUmsMemberPointsDetailCount(UmsMemberPointsDetail umsMemberPointsDetail);


    /**
     * 查询用户积分明细
     *
     * @param id 用户积分明细主键
     * @return 用户积分明细
     */
    public UmsMemberPointsDetail selectUmsMemberPointsDetailById(Long id);

    /**
     * 查询用户积分明细列表
     *
     * @param umsMemberPointsDetail 用户积分明细
     * @return 用户积分明细集合
     */
    public List<UmsMemberPointsDetail> selectUmsMemberPointsDetailList(UmsMemberPointsDetail umsMemberPointsDetail);

    /**
     * 新增用户积分明细
     *
     * @param umsMemberPointsDetail 用户积分明细
     * @return 结果
     */
    public int insertUmsMemberPointsDetail(UmsMemberPointsDetail umsMemberPointsDetail);

    /**
     * 通用写入口：写入奖励业务键；重复键抛 DuplicateKeyException，其他 SQL 错误原样上抛。
     *
     * <p>刻意不写 H8 的四个快照列 —— 它的调用方（核销奖励积分）从来不设它们，
     * 而无条件引用只由 ORDER 1/2 迁移建的列会让「迁移没跑」变成核销接口 500。
     */
    public int insertUmsMemberPointsDetailWithBusinessKey(UmsMemberPointsDetail umsMemberPointsDetail);

    /** F1b 城市定向局终结算专用:只由 OrientScoringServiceImpl 调用。 */
    public int insertOrientSettlementPointsDetail(UmsMemberPointsDetail umsMemberPointsDetail);

    /**
     * H8 专用写入口：作品结算积分流水，无条件写四个快照列与全局 topic 幂等键。
     *
     * <p>只允许 {@code PointsAwardServiceImpl.awardPublisherSettlement} 调用。
     * publisher_settlement_topic_id 必须真写进去，否则 uk_publisher_settlement_topic 是空索引；
     * 列不存在时必须原样抛 1054，不得 catch、不得把快照降级成 null。
     */
    public int insertPublisherSettlementPointsDetail(UmsMemberPointsDetail umsMemberPointsDetail);

    /** 精确读回命名空间奖励键，用于判断 DuplicateKeyException 是否真的是同一业务重放。 */
    public UmsMemberPointsDetail selectByRewardBusinessKey(@Param("memberId") Long memberId,
                                                            @Param("rewardBusinessKey") String rewardBusinessKey);

    /** 回读全局事件键，用于 event 15 跨会员的 topic 级幂等判定。 */
    public UmsMemberPointsDetail selectByEventTypeAndEventId(@Param("eventType") Long eventType,
                                                              @Param("eventId") Long eventId);

    /**
     * 修改用户积分明细
     *
     * @param umsMemberPointsDetail 用户积分明细
     * @return 结果
     */
    public int updateUmsMemberPointsDetail(UmsMemberPointsDetail umsMemberPointsDetail);

    /**
     * 删除用户积分明细
     *
     * @param id 用户积分明细主键
     * @return 结果
     */
    public int deleteUmsMemberPointsDetailById(Long id);

    /**
     * 批量删除用户积分明细
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsMemberPointsDetailByIds(Long[] ids);

    /**
     * 获取用户本周积分收入总和
     * @param memberId 用户ID
     * @return 本周积分收入总和
     */
    BigDecimal selectWeekPointsIncome(@Param("memberId") Long memberId);

    /** 拍板4 冲回:锁住某次核销真正发出去的那一笔积分(收入行)。 */
    public UmsMemberPointsDetail selectGrantForClawback(@Param("memberId") Long memberId,
                                                        @Param("eventType") Long eventType,
                                                        @Param("eventId") Long eventId);

    /** 拍板4 冲回:原发放行摘出判重键(event_id 置空,来源记 rewardBusinessKey)。 */
    public int detachGrant(@Param("id") Long id, @Param("rewardBusinessKey") String rewardBusinessKey);
}
