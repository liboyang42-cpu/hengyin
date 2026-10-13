package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 活动/主题报名统计Mapper接口
 *
 * @author webxh
 * @date 2025-10-20
 */
public interface CmsRegistrationStatisticsMapper {
    /**
     * 根据 owner_type 和 owner_id 统计票价
     * @param params 包含 ownerType、ownerId 等参数的 Map
     * @return 统计结果列表（每个元素为一组 owner_type + owner_id 的统计数据）
     */
    List<Map<String, Object>> statisticsSalesAmoutByOwner(Map<String, Object> params);

    List<Map<String, Object>> statisticsSalesAmoutByOwners(@Param("ownerType") Integer ownerType,
                                                           @Param("ownerIds") List<Long> ownerIds);
}
