package com.chengyinhub.business.mapper;
import com.chengyinhub.business.domain.ExplorationAnniversary;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface ExplorationAnniversaryMapper {
    List<ExplorationAnniversary> page(@Param("owner") Long owner, @Param("monthDay") String monthDay,
        @Param("yearStart") String yearStart, @Param("beforeAt") String beforeAt,
        @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
