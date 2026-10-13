package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamAiContent;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 漫游无形陪伴 V1 内容库；玩家侧查询只取审核通过且启用的内容。 */
public interface RoamAiContentMapper {

    RoamAiContent selectApprovedPoiMeaning(@Param("poiId") Long poiId);

    List<RoamAiContent> selectApprovedRouteStories();

    List<RoamAiContent> selectRoamAiContentList(@Param("contentType") String contentType,
                                                 @Param("auditStatus") Integer auditStatus,
                                                 @Param("poiId") Long poiId);

    RoamAiContent selectRoamAiContentById(@Param("id") Long id);

    int insertRoamAiContent(RoamAiContent content);

    int updateRoamAiContent(RoamAiContent content);

    int updateRoamAiContentAuditStatus(@Param("id") Long id, @Param("auditStatus") Integer auditStatus);

    int deleteRoamAiContentById(@Param("id") Long id);
}
