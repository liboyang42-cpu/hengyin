package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsActivity;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface ClubCalendarMapper {
    Long upperId(@Param("clubId") Long clubId, @Param("viewerId") Long viewerId,
                 @Param("monthStart") String monthStart, @Param("nextMonthStart") String nextMonthStart);

    List<Long> cursorIds(@Param("clubId") Long clubId, @Param("viewerId") Long viewerId,
                         @Param("monthStart") String monthStart, @Param("nextMonthStart") String nextMonthStart,
                         @Param("afterId") long afterId, @Param("throughId") long throughId);

    List<CmsActivity> page(@Param("clubId") Long clubId, @Param("viewerId") Long viewerId,
                          @Param("monthStart") String monthStart, @Param("nextMonthStart") String nextMonthStart,
                          @Param("afterId") long afterId, @Param("throughId") long throughId);
}
