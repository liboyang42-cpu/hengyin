package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.BizFunnelDaily;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * P3 每日漏斗汇总 Mapper。
 */
public interface BizFunnelDailyMapper {
    List<BizFunnelDaily> selectBizFunnelDailyList(BizFunnelDaily bizFunnelDaily);

    int deleteByStatDate(String statDate);

    int insertFromEventLog(String statDate);

    Map<String, Object> selectCommunityQualifiedPilotMetric(
            @Param("startDate") String startDate,
            @Param("endDate") String endDate);
}
