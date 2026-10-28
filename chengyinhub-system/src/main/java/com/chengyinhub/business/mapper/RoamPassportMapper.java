package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamSession;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 本人已结算事实的只读投影；不读取位置、照片或分享快照。 */
public interface RoamPassportMapper {
    List<RoamSession> page(@Param("owner") Long owner, @Param("before") Long before, @Param("limit") int limit);
    RoamSession detail(@Param("owner") Long owner, @Param("id") Long id);
    Map<String, Object> summary(@Param("owner") Long owner);
}
