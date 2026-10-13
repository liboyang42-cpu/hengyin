package com.chengyinhub.business.service;

import java.util.Map;

/**
 * 案件册 / 背包只读读侧(R8-CASEBOOK)。
 *
 * <p>一局的权威事实只有两处:本局 {@code topic_route_session.state_json}(变量 / tag)
 * 与 {@code topic_route_action}(动作流水);奖励领取与核销一律复用既有记录
 * (player_node_progress / sms_coupon_history / node_medal_claim),不新建表、不写任何行。
 */
public interface IPlayJourneyReadService {

    /**
     * 案件册:持有的事实、三态关系、代价 tag、按时间还原的动作日志、可选发现进度。
     *
     * @throws com.chengyinhub.common.exception.ServiceException 主题不存在 / 本局还没开始
     */
    default Map<String, Object> casebook(Long memberId, Long topicId) {
        return casebook(memberId, topicId, null);
    }
    Map<String, Object> casebook(Long memberId, Long topicId, Long activityId);

    /**
     * 背包:本主题各节点的奖励与领取状态(none / pending / claimed)。
     *
     * @throws com.chengyinhub.common.exception.ServiceException 主题不存在
     */
    default Map<String, Object> backpack(Long memberId, Long topicId) {
        return backpack(memberId, topicId, null);
    }
    Map<String, Object> backpack(Long memberId, Long topicId, Long activityId);
}
