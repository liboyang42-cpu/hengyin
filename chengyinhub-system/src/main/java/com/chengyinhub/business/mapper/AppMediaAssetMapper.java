package com.chengyinhub.business.mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Map;
public interface AppMediaAssetMapper {
    java.util.List<Map<String,Object>> selectOwnedRecent(@Param("memberId") Long memberId);
    java.util.List<Map<String,Object>> selectOwnedBefore(@Param("memberId") Long memberId,@Param("beforeId") Long beforeId);
    int insert(Map<String,Object> asset);
    Map<String,Object> select(@Param("id") Long id);
    Map<String,Object> selectByObjectKey(@Param("objectKey") String objectKey);
    int insertApprovedImage(Map<String,Object> asset);
    int insertApprovedSystemImage(Map<String,Object> asset);
    Map<String,Object> selectForUpdate(@Param("id") Long id);
    int bindAuditTask(@Param("id") Long id,@Param("version") Integer version,@Param("taskId") Long taskId);
    int decide(@Param("id") Long id,@Param("version") Integer version,@Param("taskId") Long taskId,@Param("state") String state,@Param("reason") String reason);
    int retry(@Param("id") Long id,@Param("memberId") Long memberId,@Param("version") Integer version);
}
