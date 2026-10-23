package com.chengyinhub.business.service;

import java.util.Date;

/**
 * Doc2 F1a 任务结果的完成证据来源。F1a 只定合同:同一逻辑 taskId 无论走原玩法还是
 * alternativeTaskType(SCAN/PHOTO/QUIZ,F1d 实现)都只记一次、同分;防作弊(F1c)可另注册 @Primary 实现收紧。
 */
public interface OrientTaskEvidenceVerifier {

    /** @return 服务端认定的完成时间(须不早于 startedAt);没有可信完成事实返回 null。 */
    Date verifiedCompletion(Context context);

    final class Context {
        public final Long memberId;
        public final Long activityId;
        public final Long topicId;
        public final Long sessionId;
        public final String playInstanceId;
        public final Long taskId;
        public final String alternativeTaskType;
        public final Date startedAt;

        public Context(Long memberId, Long activityId, Long topicId, Long sessionId, String playInstanceId,
                       Long taskId, String alternativeTaskType, Date startedAt) {
            this.memberId = memberId;
            this.activityId = activityId;
            this.topicId = topicId;
            this.sessionId = sessionId;
            this.playInstanceId = playInstanceId;
            this.taskId = taskId;
            this.alternativeTaskType = alternativeTaskType;
            this.startedAt = startedAt;
        }
    }
}
