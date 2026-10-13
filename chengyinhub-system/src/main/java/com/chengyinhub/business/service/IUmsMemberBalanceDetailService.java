package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.dto.IncomeBrandStatsDTO;
import com.chengyinhub.business.domain.dto.IncomeBreakdownDTO;
import com.chengyinhub.business.domain.dto.IncomeStatsDTO;

/**
 * 用户余额明细Service接口
 *
 * @author webxh
 * @date 2025-10-20
 */
public interface IUmsMemberBalanceDetailService
{

    /**
     * 获取用户本周积分收入
     * @param memberId 用户ID
     * @return 本周积分收入
     */
    BigDecimal getWeekBalanceIncome(Long memberId);


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
     * 批量删除用户余额明细
     *
     * @param ids 需要删除的用户余额明细主键集合
     * @return 结果
     */
    public int deleteUmsMemberBalanceDetailByIds(Long[] ids);

    /**
     * 删除用户余额明细信息
     *
     * @param id 用户余额明细主键
     * @return 结果
     */
    public int deleteUmsMemberBalanceDetailById(Long id);

    /**
     * 获取用户收入分解（主题/邀请/品牌）
     * @param memberId 用户ID
     * @return 收入分解DTO
     */
    IncomeBreakdownDTO getIncomeBreakdown(Long memberId);

    /**
     * 获取主题创作统计
     * @param memberId 用户ID
     * @return 主题统计DTO
     */
    IncomeStatsDTO getTopicIncomeStats(Long memberId);

    /**
     * 获取邀请商户统计
     * @param memberId 用户ID
     * @return 邀请统计DTO
     */
    IncomeStatsDTO getInviteIncomeStats(Long memberId);

    /**
     * 获取品牌参与统计
     * @param memberId 用户ID
     * @return 品牌统计DTO
     */
    IncomeBrandStatsDTO getBrandIncomeStats(Long memberId);

    /**
     * 按事件类型+事件ID获取收益
     */
    BigDecimal selectIncomeByEventTypeAndEventId(Long memberId, Long eventType, Long eventId);
}
