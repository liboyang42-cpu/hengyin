package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface PersonalExplorationExportMapper {
    Long upper(@Param("owner") Long owner,@Param("start") String start,@Param("end") String end,@Param("asOf") String asOf);
    int boundedCount(@Param("owner") Long owner,@Param("start") String start,@Param("end") String end,@Param("asOf") String asOf,@Param("upper") long upper);
    List<Map<String,Object>> page(@Param("owner") Long owner,@Param("viewerMemberId") Long viewerMemberId,
        @Param("start") String start,@Param("end") String end,@Param("asOf") String asOf,
        @Param("upper") long upper,@Param("before") Long before,@Param("limit") int limit);
}
