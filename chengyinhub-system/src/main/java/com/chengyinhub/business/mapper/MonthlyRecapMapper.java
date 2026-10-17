package com.chengyinhub.business.mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;
public interface MonthlyRecapMapper {
    long count(@Param("memberId") Long memberId, @Param("kind") String kind, @Param("start") String start, @Param("end") String end);
    List<Map<String,Object>> page(@Param("memberId") Long memberId, @Param("kind") String kind,
        @Param("start") String start, @Param("end") String end, @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
