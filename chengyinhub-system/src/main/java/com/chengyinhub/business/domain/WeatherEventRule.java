package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 天气与环境触发规则实体（对应表 {@code weather_event_rule}）。
 * 用于声明「当环境满足条件且在时段窗口内时，解锁指定点位/展示限时任务」。
 */
public class WeatherEventRule {

    private Long id;
    private Long topicId;
    private String ruleName;
    private String conditionKey;
    private String conditionValues;
    private Long unlockNodeId;
    private String taskLabel;
    private String windowStart;
    private String windowEnd;
    private Integer enabled;
    private Integer delFlag;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getConditionKey() { return conditionKey; }
    public void setConditionKey(String conditionKey) { this.conditionKey = conditionKey; }

    public String getConditionValues() { return conditionValues; }
    public void setConditionValues(String conditionValues) { this.conditionValues = conditionValues; }

    public Long getUnlockNodeId() { return unlockNodeId; }
    public void setUnlockNodeId(Long unlockNodeId) { this.unlockNodeId = unlockNodeId; }

    public String getTaskLabel() { return taskLabel; }
    public void setTaskLabel(String taskLabel) { this.taskLabel = taskLabel; }

    public String getWindowStart() { return windowStart; }
    public void setWindowStart(String windowStart) { this.windowStart = windowStart; }

    public String getWindowEnd() { return windowEnd; }
    public void setWindowEnd(String windowEnd) { this.windowEnd = windowEnd; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
