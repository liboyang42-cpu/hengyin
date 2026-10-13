package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TopicRouteAction;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 案件册 / 背包读侧专用只读查询(R8-CASEBOOK)。
 *
 * <p>动作流水与主题级剧情配置都不属任何现有 mapper 的写侧,单独开一条只读出口,
 * 免得动 {@code TopicRouteSessionMapper}(B5/B7/B8 三方共用的写侧)。
 */
public interface PlayJourneyReadMapper {

    /**
     * 本局全部路线动作,按发生顺序(create_time + id)。案件册据此还原时间线;
     * 同一 actionId 至多一行(库侧 uk_topic_route_action),重放不会新增行。
     */
    List<TopicRouteAction> selectActionsBySession(@Param("sessionId") Long sessionId);

    /**
     * 主题级剧情配置 journey_story_json 原样取出。
     *
     * <p>该列由 R8-STORY 迁移新增;本分支可能还没有它。取不到(列不存在)时调用方
     * 按「未配置」处理(found=0,total=0),不阻断案件册其余部分。未配置时返回 null。
     */
    String selectJourneyStoryJson(@Param("topicId") Long topicId);
}
