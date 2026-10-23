package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.util.Date;

/** 逻辑任务完成事实(orient_task_completion):会话作用域内每个 taskId 恰好一行,无论走哪条路径。 */
public class OrientTaskCompletion implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long memberId;
    private Long activityId;
    private Long topicId;
    private String playInstanceId;
    private Long taskId;
    private String completedVia;
    private Long sourceAttemptId;
    private Date completedTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getPlayInstanceId() { return playInstanceId; }
    public void setPlayInstanceId(String playInstanceId) { this.playInstanceId = playInstanceId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getCompletedVia() { return completedVia; }
    public void setCompletedVia(String completedVia) { this.completedVia = completedVia; }
    public Long getSourceAttemptId() { return sourceAttemptId; }
    public void setSourceAttemptId(Long sourceAttemptId) { this.sourceAttemptId = sourceAttemptId; }
    public Date getCompletedTime() { return completedTime; }
    public void setCompletedTime(Date completedTime) { this.completedTime = completedTime; }
}
