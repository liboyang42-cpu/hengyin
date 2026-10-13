package com.chengyinhub.business.service;

import java.util.Map;

/**
 * M6 随机掉落服务。
 */
public interface IRewardDropService {
    String SCENE_TOPIC_NODE = "topic_node";
    String SCENE_ROAM_MERCHANT_POI = "roam_merchant_poi";

    /**
     * 节点首次完成时掷骰。命中且成功占位(reward_drop_log uk)才返回掉落描述,否则 null。
     * 基础概率读 sys_config(reward.drop.base.prob.bp,默认1500=15%);灰度受 feature.flag.drops 控。
     * uk 保证重放/并发不多掉。
     *
     * @param nodeXp 本节点基础探索值(XP倍率掉落据此算 extraXp)
     * @return 掉落描述 {dropType, ...} 或 null(未掉/已掉/未命中)
     */
    default Map<String, Object> rollDrop(Long memberId, Long topicId, Long nodeId, Long activityId, long nodeXp) {
        return rollDrop(memberId, topicId, nodeId, activityId, SCENE_TOPIC_NODE, nodeXp);
    }

    /**
     * 在明确场景中掷骰；sceneKey 同时用于规则分流与日志幂等/审计。
     */
    Map<String, Object> rollDrop(Long memberId, Long topicId, Long nodeId, Long activityId,
                                 String sceneKey, long nodeXp);
}
