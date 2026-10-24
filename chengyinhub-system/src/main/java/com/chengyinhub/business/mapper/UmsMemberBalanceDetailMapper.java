package com.chengyinhub.business.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import org.apache.ibatis.annotations.Param;

/**
 * 用户余额明细Mapper接口
 * 
 * @author webxh
 * @date 2025-10-20
 */
public interface UmsMemberBalanceDetailMapper 
{

    List<Map<String, Object>> statisticsChangeBalance(Map<String, Object> params);

    public Integer  selectJoinNum(Map<String, Object> params);

    /**
     * 查询用户余额明细
     * 
     * @param id 用户余额明细主键
     * @return 用户余额明细
     */
    public UmsMemberBalanceDetail selectUmsMemberBalanceDetailById(Long id);

    /**
     * 查询用户余额明细列表
     * 
     * @param umsMemberBalanceDetail 用户余额明细
     * @return 用户余额明细集合
     */
    public List<UmsMemberBalanceDetail> selectUmsMemberBalanceDetailList(UmsMemberBalanceDetail umsMemberBalanceDetail);

    /**
     * 新增用户余额明细
     * 
     * @param umsMemberBalanceDetail 用户余额明细
     * @return 结果
     */
    public int insertUmsMemberBalanceDetail(UmsMemberBalanceDetail umsMemberBalanceDetail);

    /**
     * 修改用户余额明细
     * 
     * @param umsMemberBalanceDetail 用户余额明细
     * @return 结果
     */
    public int updateUmsMemberBalanceDetail(UmsMemberBalanceDetail umsMemberBalanceDetail);

    /**
     * 删除用户余额明细
     * 
     * @param id 用户余额明细主键
     * @return 结果
     */
    public int deleteUmsMemberBalanceDetailById(Long id);

    /**
     * 批量删除用户余额明细
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteUmsMemberBalanceDetailByIds(Long[] ids);


    /**
     * 获取用户本周积分收入总和
     * @param memberId 用户ID
     * @return 本周积分收入总和
     */
    BigDecimal selectWeekBalanceIncome(@Param("memberId") Long memberId);

    /**
     * 获取用户收入分解（按事件类型汇总）
     * @param memberId 用户ID
     * @return Map with topicRevenue, inviteRevenue, brandRevenue
     */
    Map<String, Object> selectIncomeBreakdown(@Param("memberId") Long memberId);

    /**
     * 按事件类型获取累计收益
     * @param memberId 用户ID
     * @param eventType 事件类型
     * @return 累计收益
     */
    BigDecimal selectIncomeByEventType(@Param("memberId") Long memberId, @Param("eventType") Long eventType);

    /**
     * 按事件类型+事件ID获取累计收益
     * @param memberId 用户ID
     * @param eventType 事件类型
     * @param eventId 事件ID
     * @return 累计收益
     */
    BigDecimal selectIncomeByEventTypeAndEventId(@Param("memberId") Long memberId,
                                                  @Param("eventType") Long eventType,
                                                  @Param("eventId") Long eventId);

    /** 商家看板累计收入(change_type=1 全量 SUM)。 */
    BigDecimal selectDashboardIncomeTotal(@Param("memberId") Long memberId);

    /** 商家看板 since 起按自然日分桶的收入:每行 day(yyyy-MM-dd) / amount。 */
    List<Map<String, Object>> selectDashboardIncomeDaily(@Param("memberId") Long memberId,
                                                         @Param("since") java.util.Date since);
}
