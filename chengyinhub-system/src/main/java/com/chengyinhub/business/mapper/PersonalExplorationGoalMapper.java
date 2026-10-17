package com.chengyinhub.business.mapper;
import com.chengyinhub.business.domain.PersonalExplorationGoal;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface PersonalExplorationGoalMapper {
    int insert(PersonalExplorationGoal row);
    PersonalExplorationGoal byRequest(@Param("owner") Long owner, @Param("requestId") String requestId);
    PersonalExplorationGoal byId(@Param("owner") Long owner, @Param("id") Long id);
    List<PersonalExplorationGoal> page(@Param("owner") Long owner, @Param("beforeId") Long beforeId, @Param("limit") int limit);
    int cancel(@Param("owner") Long owner, @Param("id") Long id);
    long progress(@Param("owner") Long owner, @Param("metric") String metric,
        @Param("start") String start, @Param("end") String end, @Param("asOf") String asOf);
}
