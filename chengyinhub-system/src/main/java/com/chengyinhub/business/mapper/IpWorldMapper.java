package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.IpWorld;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * P3 IP世界 Mapper。
 */
public interface IpWorldMapper {
    List<IpWorld> selectPublishedWorlds(@Param("cityCode") String cityCode);

    List<IpWorld> selectAdminWorldList(IpWorld query);

    int insertIpWorld(IpWorld world);

    int updateIpWorld(IpWorld world);
}
