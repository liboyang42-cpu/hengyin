package com.chengyinhub.business.mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;
import java.util.Map;
public interface RoamOrphanLocationRetentionMapper {
 List<Map<String,Object>> tiles(@Param("cutoff") Date cutoff,@Param("limit") int limit);
 List<Map<String,Object>> pois(@Param("cutoff") Date cutoff,@Param("limit") int limit);
 List<Map<String,Object>> shops(@Param("cutoff") Date cutoff,@Param("limit") int limit);
}
