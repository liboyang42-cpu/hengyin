package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * M9 节点级游玩尝试日志 play_attempt_log(服务端埋点,不依赖客户端)。
 */
public class PlayAttemptLog {
    public static final int ATTEMPT_TYPE_ANSWER = 1;
    public static final int ATTEMPT_TYPE_PUZZLE_HINT = 5;
    public static final int RESULT_ABANDONED = 2;

    private Long id;
    private Long memberId;
    private Long topicId;
    private Long nodeId;
    private Long activityId;
    /** 1答题 2扫码 3GPS 4拍照 5谜题提示 6偏好题组 */
    private Integer attemptType;
    /** 普通尝试:1过 0错 2放弃；谜题提示:1=Hint1 2=Hint2 */
    private Integer result;
    /** 答错时用户答案(供 AI 出题评估) */
    private String wrongAnswer;
    /** 本次尝试用时(秒,可空) */
    private Integer durationS;
    /** 偏好题选择与确定性结果 JSON */
    private String resultJson;
    /** 请求幂等键；普通尝试为空 */
    private String idempotencyKey;
    /** 高级动作原请求指纹；历史 NULL 不得推测或释放其幂等身份。 */
    private String requestFingerprint;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public Integer getAttemptType() { return attemptType; }
    public void setAttemptType(Integer attemptType) { this.attemptType = attemptType; }

    public Integer getResult() { return result; }
    public void setResult(Integer result) { this.result = result; }

    public String getWrongAnswer() { return wrongAnswer; }
    public void setWrongAnswer(String wrongAnswer) { this.wrongAnswer = wrongAnswer; }

    public Integer getDurationS() { return durationS; }
    public void setDurationS(Integer durationS) { this.durationS = durationS; }

    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
