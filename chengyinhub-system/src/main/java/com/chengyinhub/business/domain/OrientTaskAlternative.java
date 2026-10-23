package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.util.Date;

/** 同一逻辑任务的替代完成方式(orient_task_alternative)。刻意没有分数字段:分数挂 taskId。 */
public class OrientTaskAlternative implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long topicId;
    private Long taskId;
    private String alternativeTaskType;
    private String promptText;
    private String answerHash;
    private Integer enabled;
    private Long createBy;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getAlternativeTaskType() { return alternativeTaskType; }
    public void setAlternativeTaskType(String alternativeTaskType) { this.alternativeTaskType = alternativeTaskType; }
    public String getPromptText() { return promptText; }
    public void setPromptText(String promptText) { this.promptText = promptText; }
    public String getAnswerHash() { return answerHash; }
    public void setAnswerHash(String answerHash) { this.answerHash = answerHash; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
