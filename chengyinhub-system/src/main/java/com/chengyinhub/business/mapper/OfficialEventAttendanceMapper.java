package com.chengyinhub.business.mapper;
import java.util.Map;
import java.util.Date;
import org.apache.ibatis.annotations.Param;
public interface OfficialEventAttendanceMapper {
 Map<String,Object> config(@Param("event") Long event);
 int saveConfig(@Param("event") Long event, @Param("json") String json, @Param("actor") Long actor, @Param("version") long version);
 Map<String,Object> mine(@Param("event") Long event, @Param("actor") Long actor);
 Map<String,Object> credential(@Param("event") Long event, @Param("code") String code);
 int issue(@Param("event") Long event, @Param("actor") Long actor, @Param("participation") Long participation, @Param("code") String code);
 int rotate(@Param("participation") Long participation, @Param("code") String code);
 int checkin(@Param("participation") Long participation, @Param("actor") Long actor, @Param("at") Date at);
}
