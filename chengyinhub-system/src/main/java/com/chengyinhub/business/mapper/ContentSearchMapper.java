package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.ContentSearchItemVO;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * O-14 统一内容搜索投影 content_search_document。
 */
public interface ContentSearchMapper {
    int upsertVisibleTopics(@Param("now") Date now);

    int upsertVisibleActivities(@Param("now") Date now);

    int upsertVisiblePosts(@Param("now") Date now);

    int hideStaleTopics(@Param("now") Date now);

    int hideStaleActivities(@Param("now") Date now);

    int hideStalePosts(@Param("now") Date now);

    List<ContentSearchItemVO> selectUnifiedSearch(@Param("keyword") String keyword,
                                                  @Param("types") List<String> types,
                                                  @Param("cityCode") String cityCode,
                                                  @Param("viewerId") Long viewerId);
}
