package com.chengyinhub.business.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/** 城市定向替代任务提交(orient_task_attempt)。 */
public class OrientTaskAttempt implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long memberId;
    private Long activityId;
    private Long topicId;
    private String playInstanceId;
    private Long taskId;
    private String alternativeTaskType;
    private String idempotencyKey;
    private String requestFingerprint;
    private String picUrl;
    private String reportCoordSys;
    private BigDecimal wgs84Lng;
    private BigDecimal wgs84Lat;
    private Integer distanceM;
    private Integer nodeGeoDirty;
    private Integer status;
    private Long reviewBy;
    private Date reviewTime;
    private Date createTime;
    private Date updateTime;

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
    public String getAlternativeTaskType() { return alternativeTaskType; }
    public void setAlternativeTaskType(String alternativeTaskType) { this.alternativeTaskType = alternativeTaskType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public String getPicUrl() { return picUrl; }
    public void setPicUrl(String picUrl) { this.picUrl = picUrl; }
    public String getReportCoordSys() { return reportCoordSys; }
    public void setReportCoordSys(String reportCoordSys) { this.reportCoordSys = reportCoordSys; }
    public BigDecimal getWgs84Lng() { return wgs84Lng; }
    public void setWgs84Lng(BigDecimal wgs84Lng) { this.wgs84Lng = wgs84Lng; }
    public BigDecimal getWgs84Lat() { return wgs84Lat; }
    public void setWgs84Lat(BigDecimal wgs84Lat) { this.wgs84Lat = wgs84Lat; }
    public Integer getDistanceM() { return distanceM; }
    public void setDistanceM(Integer distanceM) { this.distanceM = distanceM; }
    public Integer getNodeGeoDirty() { return nodeGeoDirty; }
    public void setNodeGeoDirty(Integer nodeGeoDirty) { this.nodeGeoDirty = nodeGeoDirty; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public Date getReviewTime() { return reviewTime; }
    public void setReviewTime(Date reviewTime) { this.reviewTime = reviewTime; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
