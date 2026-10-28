package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 主题的可玩时段与开局前准备(文档二 UI 实施 L8,表 cms_topic_play_setup,一主题一行)。
 * 没有行 = 不限时段、没有准备项。
 */
public class TopicPlaySetup {

    private Long topicId;

    /** any / day / night / custom。 */
    private String windowMode;

    /** 自定义开始 HH:mm,仅 custom。 */
    private String windowStart;

    /** 自定义结束 HH:mm,仅 custom。 */
    private String windowEnd;

    /** 准备项 JSON 字符串数组。 */
    private String prepItemsJson;

    private Date createTime;

    private Date updateTime;

    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }

    public String getWindowMode() { return windowMode; }
    public void setWindowMode(String windowMode) { this.windowMode = windowMode; }

    public String getWindowStart() { return windowStart; }
    public void setWindowStart(String windowStart) { this.windowStart = windowStart; }

    public String getWindowEnd() { return windowEnd; }
    public void setWindowEnd(String windowEnd) { this.windowEnd = windowEnd; }

    public String getPrepItemsJson() { return prepItemsJson; }
    public void setPrepItemsJson(String prepItemsJson) { this.prepItemsJson = prepItemsJson; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
