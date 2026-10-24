package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.IncomeBrandStatsDTO;
import com.chengyinhub.business.domain.dto.IncomeBreakdownDTO;
import com.chengyinhub.business.domain.dto.IncomeStatsDTO;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;

/**
 * 用户余额明细Service业务层处理
 *
 * @author webxh
 * @date 2025-10-20
 */
@Service
public class UmsMemberBalanceDetailServiceImpl implements IUmsMemberBalanceDetailService
{
    @Autowired
    private UmsMemberBalanceDetailMapper umsMemberBalanceDetailMapper;

    @Autowired
    @Lazy
    private IUmsMemberService umsMemberService;

    @Autowired
    @Lazy
    private ICmsTopicService cmsTopicService;

    /**
     * 查询用户余额明细
     * 
     * @param id 用户余额明细主键
     * @return 用户余额明细
     */
    @Override
    public UmsMemberBalanceDetail selectUmsMemberBalanceDetailById(Long id)
    {
        return umsMemberBalanceDetailMapper.selectUmsMemberBalanceDetailById(id);
    }


    @Override
    public List<Map<String, Object>> statisticsChangeBalance(Map<String, Object> params)
    {
        return umsMemberBalanceDetailMapper.statisticsChangeBalance(params);
    }


    @Override
    public Integer selectJoinNum(Map<String, Object> params)
    {
        return umsMemberBalanceDetailMapper.selectJoinNum(params);
    }



    /**
     * 查询用户余额明细列表
     * 
     * @param umsMemberBalanceDetail 用户余额明细
     * @return 用户余额明细
     */
    @Override
    public List<UmsMemberBalanceDetail> selectUmsMemberBalanceDetailList(UmsMemberBalanceDetail umsMemberBalanceDetail)
    {
        return umsMemberBalanceDetailMapper.selectUmsMemberBalanceDetailList(umsMemberBalanceDetail);
    }

    /**
     * 新增用户余额明细
     * 
     * @param umsMemberBalanceDetail 用户余额明细
     * @return 结果
     */
    @Override
    public int insertUmsMemberBalanceDetail(UmsMemberBalanceDetail umsMemberBalanceDetail)
    {
        umsMemberBalanceDetail.setCreateTime(DateUtils.getNowDate());
        return umsMemberBalanceDetailMapper.insertUmsMemberBalanceDetail(umsMemberBalanceDetail);
    }

    /**
     * 修改用户余额明细
     * 
     * @param umsMemberBalanceDetail 用户余额明细
     * @return 结果
     */
    @Override
    public int updateUmsMemberBalanceDetail(UmsMemberBalanceDetail umsMemberBalanceDetail)
    {
        umsMemberBalanceDetail.setUpdateTime(DateUtils.getNowDate());
        return umsMemberBalanceDetailMapper.updateUmsMemberBalanceDetail(umsMemberBalanceDetail);
    }

    /**
     * 批量删除用户余额明细
     * 
     * @param ids 需要删除的用户余额明细主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberBalanceDetailByIds(Long[] ids)
    {
        return umsMemberBalanceDetailMapper.deleteUmsMemberBalanceDetailByIds(ids);
    }

    /**
     * 删除用户余额明细信息
     * 
     * @param id 用户余额明细主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberBalanceDetailById(Long id)
    {
        return umsMemberBalanceDetailMapper.deleteUmsMemberBalanceDetailById(id);
    }

    /**
     * 获取用户本周积分收入
     * @param memberId 用户ID
     * @return 本周积分收入
     */
    @Override
    public BigDecimal getWeekBalanceIncome(Long memberId) {
        return umsMemberBalanceDetailMapper.selectWeekBalanceIncome(memberId);
    }

    @Override
    public IncomeBreakdownDTO getIncomeBreakdown(Long memberId) {
        Map<String, Object> map = umsMemberBalanceDetailMapper.selectIncomeBreakdown(memberId);
        IncomeBreakdownDTO dto = new IncomeBreakdownDTO();
        if (map != null) {
            dto.setTopicRevenue(toBigDecimal(map.get("topicRevenue")));
            dto.setInviteRevenue(toBigDecimal(map.get("inviteRevenue")));
            dto.setBrandRevenue(toBigDecimal(map.get("brandRevenue")));
        }
        return dto;
    }

    @Override
    public IncomeStatsDTO getTopicIncomeStats(Long memberId) {
        IncomeStatsDTO dto = new IncomeStatsDTO();
        dto.setTotalRevenue(umsMemberBalanceDetailMapper.selectIncomeByEventType(memberId, 1L));
        dto.setItemCount(cmsTopicService.selectCmsTopicNumByMemberId(memberId));
        return dto;
    }

    @Override
    public IncomeStatsDTO getInviteIncomeStats(Long memberId) {
        IncomeStatsDTO dto = new IncomeStatsDTO();
        dto.setTotalRevenue(umsMemberBalanceDetailMapper.selectIncomeByEventType(memberId, 2L));
        UmsMember query = new UmsMember();
        query.setInviteUserId(String.valueOf(memberId));
        List<UmsMember> list = umsMemberService.selectUmsMemberList(query);
        dto.setItemCount(list != null ? list.size() : 0);
        return dto;
    }

    @Override
    public IncomeBrandStatsDTO getBrandIncomeStats(Long memberId) {
        IncomeBrandStatsDTO dto = new IncomeBrandStatsDTO();
        dto.setTotalRevenue(umsMemberBalanceDetailMapper.selectIncomeByEventType(memberId, 2L));
        dto.setTopicCount(cmsTopicService.selectCmsTopicNumByMemberId(memberId));
        dto.setTotalExposure(0L);
        return dto;
    }

    @Override
    public BigDecimal selectIncomeByEventTypeAndEventId(Long memberId, Long eventType, Long eventId) {
        return umsMemberBalanceDetailMapper.selectIncomeByEventTypeAndEventId(memberId, eventType, eventId);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        return new BigDecimal(value.toString());
    }

    @Override
    public BigDecimal sumDashboardIncome(Long memberId)
    {
        BigDecimal total = umsMemberBalanceDetailMapper.selectDashboardIncomeTotal(memberId);
        return total == null ? BigDecimal.ZERO : total;
    }

    @Override
    public Map<String, BigDecimal> sumDashboardIncomeByDay(Long memberId, java.util.Date since)
    {
        Map<String, BigDecimal> byDay = new java.util.HashMap<>();
        List<Map<String, Object>> rows = umsMemberBalanceDetailMapper.selectDashboardIncomeDaily(memberId, since);
        if (rows == null) return byDay;
        for (Map<String, Object> row : rows)
        {
            Object day = row.get("day");
            Object amount = row.get("amount");
            if (day == null || amount == null) continue;
            byDay.put(String.valueOf(day), new BigDecimal(String.valueOf(amount)));
        }
        return byDay;
    }
}
