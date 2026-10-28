package com.chengyinhub.business.service;

import java.util.Map;

/**
 * Doc2 F1a 城市定向(product_type=1)会话生命周期:prepare → start → task result → 终局(abandon / disqualify)。
 * 复用 topic_route_session(phase 列)+ orient_session_clock;时间、结算只认服务端。
 * 所有写入都要求幂等键;同键同指纹重放原回执,同键异指纹拒绝。失败抛 OrientSessionException。
 */
public interface IOrientSessionService {
    boolean enabled();
    Map<String, Object> prepare(Long memberId, Long topicId, Long activityId, String idempotencyKey);
    Map<String, Object> start(Long memberId, Long sessionId, Long ackConfigVersion, String idempotencyKey);
    Map<String, Object> abandon(Long memberId, Long sessionId, String idempotencyKey);
    /** 供 F1c 防作弊等服务端调用;不对玩家开放。 */
    Map<String, Object> disqualify(Long memberId, Long sessionId, String reasonCode, String idempotencyKey);
    Map<String, Object> taskResult(Long memberId, Long sessionId, Long taskId, String alternativeTaskType,
                                   String idempotencyKey);
    /** 只读列出本人已有局;activityId=null 不限定场次,不猜测当前局。 */
    Map<String, Object> current(Long memberId, Long topicId, Long activityId);
    Map<String, Object> view(Long memberId, Long sessionId);
    Map<String, Object> receipt(Long memberId, Long sessionId, String idempotencyKey);
}
