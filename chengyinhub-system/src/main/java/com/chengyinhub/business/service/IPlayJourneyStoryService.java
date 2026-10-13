package com.chengyinhub.business.service;

import java.util.Map;

/**
 * 主题级剧情配置的玩家只读求值(R8-STORY):阶段结算与结局。
 *
 * <p>一局状态只读 {@code topic_route_session.state_json};条件一律走
 * {@link com.chengyinhub.business.service.support.TopicRouteEngine#conditionsPass}。
 * 只读自己的局,不写任何表。
 */
public interface IPlayJourneyStoryService {

    /**
     * 本章节阶段结算:本章节点全部完成后才可取。
     *
     * @return changed(按 when 取句) / open / costs(本局带注册 label 的 tag) / hp / luck
     */
    default Map<String, Object> stageEnd(Long memberId, Long topicId, Long chapterId) {
        return stageEnd(memberId, topicId, null, chapterId);
    }
    Map<String, Object> stageEnd(Long memberId, Long topicId, Long activityId, Long chapterId);

    /**
     * 本局结局:本局 COMPLETED 后才可取。
     *
     * @return code/title/summary/epilogues(relation 三态)/exhibits/badge/costs/found/total
     */
    default Map<String, Object> ending(Long memberId, Long topicId) {
        return ending(memberId, topicId, null);
    }
    Map<String, Object> ending(Long memberId, Long topicId, Long activityId);
}
