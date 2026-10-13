package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.NearbyNodeVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 地图 Mapper —— 周边节点地理查询(只读)。
 *
 */
public interface ApiMapMapper {

    /**
     * 查询给定坐标半径范围内的活动节点(按距离升序)。
     * 经纬度列为 VARCHAR,SQL 内 CAST 后用 Haversine 计算直线距离。
     *
     * @param lng         查询点经度
     * @param lat         查询点纬度
     * @param radius      半径(米)
     * @param limit       返回数量上限
     * @param status      可选:活动状态过滤(null 不过滤)
     * @param auditStatus 可选:审核状态过滤(null 不过滤)
     */
    List<NearbyNodeVO> selectNearbyNodes(@Param("lng") double lng,
                                         @Param("lat") double lat,
                                         @Param("radius") double radius,
                                         @Param("limit") int limit,
                                         @Param("status") Integer status,
                                         @Param("auditStatus") Integer auditStatus);
}
